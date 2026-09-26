package io.github.tuthan.steamoscompanion.core.transport

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionState
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.ports.SocketFactory
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.RequestValidationException
import io.github.tuthan.steamoscompanion.core.protocol.fingerprintMatches
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.ChannelBinding
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.ServerOnlyTlsAuthentication
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsFatalAlertReceived
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

data class TransportTarget(val endpoint: HostEndpoint, val network: LanPath)

data class UnverifiedDiscoveryCandidate(
    val target: TransportTarget,
    val hostId: String,
    val name: String?,
    val certificateFingerprint: String,
)

sealed interface DiscoveryProbeResult {
    data class Candidate(val value: UnverifiedDiscoveryCandidate) : DiscoveryProbeResult
    data class Rejected(val status: Int, val error: String, val message: String) : DiscoveryProbeResult
    data class UnsupportedVersion(val version: Long) : DiscoveryProbeResult
    data class Failed(val failure: TransportFailure) : DiscoveryProbeResult
}

enum class TransportFailure {
    VALIDATION,
    PERMISSION,
    LEGACY_PERMISSION_MAY_BE_REQUIRED,
    DNS,
    CONNECT,
    PIN,
    TLS12_REQUIRED,
    CANCELLED,
    TIMEOUT,
    IO,
    PROTOCOL,
}

sealed interface RequestOutcome {
    /** A 2xx response has been fully framed, parsed, and decoded. 202 still means accepted, not completed. */
    data class Reply(val response: ApiReply, val status: Int, val retryAfterMillis: Long?) : RequestOutcome
    data class Rejected(val response: ApiReply.Rejected, val retryAfterMillis: Long?) : RequestOutcome
    data class NotSent(val failure: TransportFailure) : RequestOutcome
    /** A POST may have reached the host but no validated terminal response was obtained. */
    data class Unknown(val failure: TransportFailure, val status: Int? = null) : RequestOutcome
    /** Initial pairing may have created a pending request, but its one-time session/id was not received. */
    data class PairingUnresolved(val failure: TransportFailure, val status: Int? = null) : RequestOutcome
}

/**
 * One-request HTTPS transport for a host identified by a sha256:<lowercase DER digest> pin.
 * Call from a worker thread. TLS, request writes, and response reads share one absolute deadline.
 */
class PinnedHttpTransport(
    private val clock: Clock,
    private val networkAccess: NetworkAccess,
    private val socketFactory: SocketFactory,
) {
    /**
     * The sole no-prior-pin path. It emits only unauthenticated GET /v1/discovery and binds the
     * response identity to the DER certificate captured from that same TLS connection.
     */
    fun probeDiscovery(
        target: TransportTarget,
        cancellation: CancellationSignal? = null,
        budgetMillis: Long = DISCOVERY_PROBE_BUDGET_MS,
    ): DiscoveryProbeResult {
        require(budgetMillis in 1..MAX_DISCOVERY_PROBE_BUDGET_MS)
        val request = ApiRequest(HttpMethod.GET, ApiRoute.DISCOVERY.path)
        val scope = try {
            RequestDeadline(clock, budgetMillis, cancellation)
        } catch (_: RequestCancelledException) {
            return DiscoveryProbeResult.Failed(TransportFailure.CANCELLED)
        }
        val certificate = AtomicReference<ByteArray?>(null)
        val permissionRevoked = AtomicBoolean(false)
        var permissionWatch: Closeable? = null
        try {
            permissionWatch = watchLocalNetworkPermission(scope, permissionRevoked)
            requireLocalNetworkPermission()
            scope.check()
            val address = resolve(target, scope)
            requireLocalNetworkPermission()
            scope.check()
            val raw = socketFactory.create()
            scope.attach(raw)
            requireLocalNetworkPermission()
            networkAccess.bind(raw, target.network)
            requireLocalNetworkPermission()
            scope.check()
            raw.connect(InetSocketAddress(address, target.endpoint.port), scope.remainingMillis())
            scope.check()
            val tls = platformTls(raw, target.endpoint, null, certificate)
            scope.attach(tls)
            tls.use {
                it.startHandshake()
                scope.check()
                val bytes = ProtocolCodec.encodeRequest(request).body
                Http1RequestWriter.write(it.outputStream, target.endpoint, request, bytes, null, scope) { }
                val response = Http1ResponseParser.read(it.inputStream, ApiRoute.DISCOVERY.maxResponseBytes) {
                    scope.beforeRead(it)
                }.response
                scope.check()
                return when (val decoded = ProtocolCodec.decodeResponse(ApiRoute.DISCOVERY, response)) {
                    is ApiReply.Rejected -> DiscoveryProbeResult.Rejected(decoded.status, decoded.error, decoded.message)
                    is ApiReply.UnsupportedVersion -> DiscoveryProbeResult.UnsupportedVersion(decoded.protocolVersion)
                    is ApiReply.Versioned -> {
                        val discovery = decoded.payload as? io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload.Discovery
                            ?: throw IOException("Discovery response model is invalid")
                        val announcement = discovery.announcement
                        val der = certificate.get() ?: throw IOException("TLS peer certificate was not captured")
                        if (!fingerprintMatches(sha256(der), announcement.certificateFingerprint)) {
                            throw IOException("Discovery fingerprint does not match the TLS certificate")
                        }
                        DiscoveryProbeResult.Candidate(
                            UnverifiedDiscoveryCandidate(target, announcement.hostId, discovery.name, announcement.certificateFingerprint),
                        )
                    }
                }
            }
        } catch (_: RequestCancelledException) {
            return DiscoveryProbeResult.Failed(
                if (permissionRevoked.get()) TransportFailure.PERMISSION else TransportFailure.CANCELLED,
            )
        } catch (_: RequestDeadlineExceededException) {
            return DiscoveryProbeResult.Failed(qualifyLegacyPermissionFailure(TransportFailure.TIMEOUT))
        } catch (_: SecurityException) {
            return DiscoveryProbeResult.Failed(qualifyLegacyPermissionFailure(TransportFailure.PERMISSION))
        } catch (error: Exception) {
            val failure = if (permissionRevoked.get()) TransportFailure.PERMISSION else classify(error, scope)
            return DiscoveryProbeResult.Failed(qualifyLegacyPermissionFailure(failure))
        } finally {
            runCatching { permissionWatch?.close() }
            scope.close()
        }
    }

    fun execute(
        target: TransportTarget,
        pin: String,
        request: ApiRequest,
        cancellation: CancellationSignal? = null,
    ): RequestOutcome {
        val encoded = try {
            ProtocolCodec.encodeRequest(request)
        } catch (_: RequestValidationException) {
            return RequestOutcome.NotSent(TransportFailure.VALIDATION)
        } catch (_: IllegalArgumentException) {
            return RequestOutcome.NotSent(TransportFailure.VALIDATION)
        }
        if (!PIN.matches(pin)) return RequestOutcome.NotSent(TransportFailure.VALIDATION)
        val initialPairing = encoded.route.route == ApiRoute.PAIR_REQUEST &&
            request.body?.fields?.containsKey("pairing_session") != true
        val budget = when {
            initialPairing -> INITIAL_PAIRING_BUDGET_MS
            encoded.route.route == ApiRoute.PAIR_REQUEST -> APPROVAL_POLL_BUDGET_MS
            request.method == HttpMethod.GET &&
                (encoded.route.route == ApiRoute.STATUS || encoded.route.route == ApiRoute.OPERATION) -> STATUS_BUDGET_MS
            else -> DEFAULT_BUDGET_MS
        }
        return exchange(
            target, pin, request, encoded.body, encoded.route.route, encoded.route.operationId,
            initialPairing, budget, cancellation,
        )
    }

    private fun exchange(
        target: TransportTarget,
        pin: String,
        request: ApiRequest,
        body: ByteArray,
        route: ApiRoute,
        expectedOperationId: String?,
        initialPairing: Boolean,
        budgetMillis: Long,
        cancellation: CancellationSignal?,
    ): RequestOutcome {
        val scope = try {
            RequestDeadline(clock, budgetMillis, cancellation)
        } catch (error: RequestCancelledException) {
            return RequestOutcome.NotSent(TransportFailure.CANCELLED)
        }
        val requestBytesMayHaveBeenSent = AtomicBoolean(false)
        var responseStatus: Int? = null
        var retryAfterMillis: Long? = null
        val permissionRevoked = AtomicBoolean(false)
        var permissionWatch: Closeable? = null
        try {
            permissionWatch = watchLocalNetworkPermission(scope, permissionRevoked)
            requireLocalNetworkPermission()
            scope.check()
            val address = resolve(target, scope)
            requireLocalNetworkPermission()
            scope.check()
            val raw = socketFactory.create()
            scope.attach(raw)
            requireLocalNetworkPermission()
            networkAccess.bind(raw, target.network)
            requireLocalNetworkPermission()
            scope.check()
            raw.connect(InetSocketAddress(address, target.endpoint.port), scope.remainingMillis())
            scope.check()

            val response = if (initialPairing) {
                bootstrapRequest(raw, target.endpoint, pin, request, body, scope) { requestBytesMayHaveBeenSent.set(true) }
            } else {
                pinnedPlatformRequest(raw, target.endpoint, pin, request, body, scope) { requestBytesMayHaveBeenSent.set(true) }
            }
            responseStatus = response.status
            if (response.status == 429 || response.headers.keys.any { it.equals("retry-after", true) }) {
                retryAfterMillis = RetryAfter.delayMillis(response.headers, clock.wallTimeMillis())
            }
            val decoded = try {
                ProtocolCodec.decodeResponse(route, response, expectedOperationId)
            } catch (_: Exception) {
                return ambiguous(initialPairing, requestBytesMayHaveBeenSent.get(), TransportFailure.PROTOCOL, responseStatus)
            }
            return when (decoded) {
                is ApiReply.Rejected -> RequestOutcome.Rejected(decoded, retryAfterMillis)
                is ApiReply.UnsupportedVersion -> if (request.method == HttpMethod.POST) {
                    ambiguous(initialPairing, requestBytesMayHaveBeenSent.get(), TransportFailure.PROTOCOL, responseStatus)
                } else {
                    RequestOutcome.Reply(decoded, response.status, retryAfterMillis)
                }
                is ApiReply.Versioned -> RequestOutcome.Reply(decoded, response.status, retryAfterMillis)
            }
        } catch (_: RequestCancelledException) {
            val failure = if (permissionRevoked.get()) TransportFailure.PERMISSION else TransportFailure.CANCELLED
            return ambiguous(initialPairing, requestBytesMayHaveBeenSent.get(), failure, responseStatus)
        } catch (_: RequestDeadlineExceededException) {
            return ambiguous(
                initialPairing,
                requestBytesMayHaveBeenSent.get(),
                qualifyLegacyPermissionFailure(TransportFailure.TIMEOUT),
                responseStatus,
            )
        } catch (_: SecurityException) {
            return ambiguous(
                initialPairing,
                requestBytesMayHaveBeenSent.get(),
                qualifyLegacyPermissionFailure(TransportFailure.PERMISSION),
                responseStatus,
            )
        } catch (error: Exception) {
            val failure = qualifyLegacyPermissionFailure(
                if (permissionRevoked.get()) TransportFailure.PERMISSION else classify(error, scope),
            )
            return ambiguous(initialPairing, requestBytesMayHaveBeenSent.get(), failure, responseStatus)
        } finally {
            runCatching { permissionWatch?.close() }
            scope.close()
        }
    }

    private fun watchLocalNetworkPermission(
        scope: RequestDeadline,
        permissionRevoked: AtomicBoolean,
    ): Closeable {
        val initial = networkAccess.localNetworkPermissionState()
        fun cancelIfRevoked() {
            val current = networkAccess.localNetworkPermissionState()
            val lostGrant = current == LocalNetworkPermissionState.RequiredPermissionMissing ||
                (initial == LocalNetworkPermissionState.Allowed &&
                    current == LocalNetworkPermissionState.LegacyRestrictionUnknown)
            if (lostGrant) {
                permissionRevoked.set(true)
                scope.cancel()
            }
        }

        cancelIfRevoked()
        val monitor = LOCAL_NETWORK_PERMISSION_MONITOR.scheduleWithFixedDelay(
            ::cancelIfRevoked,
            LOCAL_NETWORK_PERMISSION_POLL_MILLIS,
            LOCAL_NETWORK_PERMISSION_POLL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        return Closeable { monitor.cancel(false) }
    }

    private fun requireLocalNetworkPermission() {
        if (networkAccess.localNetworkPermissionState() == LocalNetworkPermissionState.RequiredPermissionMissing) {
            throw SecurityException("Local network permission is required before opening a socket")
        }
    }

    private fun qualifyLegacyPermissionFailure(failure: TransportFailure): TransportFailure =
        if (networkAccess.localNetworkPermissionState() == LocalNetworkPermissionState.LegacyRestrictionUnknown &&
            failure in setOf(TransportFailure.TIMEOUT, TransportFailure.PERMISSION)
        ) {
            TransportFailure.LEGACY_PERMISSION_MAY_BE_REQUIRED
        } else {
            failure
        }

    private fun resolve(target: TransportTarget, scope: RequestDeadline): InetAddress {
        val future = BoundedDnsExecutor.submit { networkAccess.resolve(target.endpoint.host, target.network) }
        scope.track(future)
        return try {
            val addresses = future.get(scope.remainingMillis().toLong(), TimeUnit.MILLISECONDS)
            scope.check()
            addresses.firstOrNull() ?: throw UnknownHostException("Endpoint did not resolve")
        } catch (error: TimeoutException) {
            throw RequestDeadlineExceededException()
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } finally {
            scope.untrack(future)
        }
    }

    private fun pinnedPlatformRequest(
        raw: Socket,
        endpoint: HostEndpoint,
        pin: String,
        request: ApiRequest,
        body: ByteArray,
        scope: RequestDeadline,
        beforeWrite: () -> Unit,
    ): HttpResponse {
        val tls = platformTls(raw, endpoint, pin, null)
        scope.attach(tls)
        try {
            tls.startHandshake()
            scope.check()
            Http1RequestWriter.write(tls.outputStream, endpoint, request, body, null, scope, beforeWrite)
            val parsed = Http1ResponseParser.read(tls.inputStream, request.validate().route.maxResponseBytes) {
                scope.beforeRead(tls)
            }
            scope.check()
            return parsed.response
        } finally {
            runCatching { tls.close() }
        }
    }

    private fun bootstrapRequest(
        raw: Socket,
        endpoint: HostEndpoint,
        pin: String,
        request: ApiRequest,
        body: ByteArray,
        scope: RequestDeadline,
        beforeWrite: () -> Unit,
    ): HttpResponse {
        val client = PinnedBootstrapClient(BcTlsCrypto(SecureRandom()), pin)
        val protocol = TlsClientProtocol(raw.getInputStream(), raw.getOutputStream())
        try {
            protocol.connect(client)
            scope.check()
            if (client.resumed()) throw IOException("Pairing bootstrap must use a full TLS handshake")
            val binding = client.binding() ?: throw Tls12RequiredException()
            if (binding.size < MIN_BINDING_BYTES) throw IOException("TLS channel binding is too short")
            val headerValue = Base64.getUrlEncoder().encodeToString(binding)
            Http1RequestWriter.write(protocol.outputStream, endpoint, request, body, headerValue, scope, beforeWrite)
            val parsed = Http1ResponseParser.read(protocol.inputStream, request.validate().route.maxResponseBytes) {
                scope.beforeRead(raw)
            }
            scope.check()
            return parsed.response
        } finally {
            runCatching { protocol.close() }
            runCatching { raw.close() }
        }
    }

    private fun platformTls(
        raw: Socket,
        endpoint: HostEndpoint,
        pin: String?,
        capturedCertificate: AtomicReference<ByteArray?>?,
    ): SSLSocket {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not accepted")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("Server certificate is missing")
                val encoded = leaf.encoded
                if (pin != null && !fingerprintMatches(sha256(encoded), pin)) {
                    throw CertificateException(PIN_MISMATCH_MESSAGE)
                }
                capturedCertificate?.set(encoded.copyOf())
            }
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        val tls = context.socketFactory.createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket
        val supported = tls.supportedProtocols.toSet()
        val protocols = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
        if (protocols.isEmpty()) throw IOException("No supported TLS 1.2 or TLS 1.3 protocol")
        tls.enabledProtocols = protocols.toTypedArray()
        // Certificate pinning is the trust decision; the host certificate intentionally has no DNS SAN.
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = null }
        return tls
    }

    private fun ambiguous(
        initialPairing: Boolean,
        bytesMayHaveBeenSent: Boolean,
        failure: TransportFailure,
        status: Int?,
    ): RequestOutcome = when {
        !bytesMayHaveBeenSent -> RequestOutcome.NotSent(failure)
        initialPairing -> RequestOutcome.PairingUnresolved(failure, status)
        else -> RequestOutcome.Unknown(failure, status)
    }

    private fun classify(error: Throwable, scope: RequestDeadline? = null): TransportFailure {
        // Socket close is the mechanism used to interrupt a blocked read/write. The resulting
        // SocketException often wins the race against the coroutine-visible cancellation or
        // timeout, so consult the request's terminal state before classifying that I/O wrapper.
        if (scope != null) {
            try {
                scope.check()
            } catch (_: RequestCancelledException) {
                return TransportFailure.CANCELLED
            } catch (_: RequestDeadlineExceededException) {
                return TransportFailure.TIMEOUT
            }
        }
        if (error is RequestCancelledException || error is java.util.concurrent.CancellationException) return TransportFailure.CANCELLED
        if (error is RequestDeadlineExceededException || error is java.net.SocketTimeoutException || error is TimeoutException) {
            return TransportFailure.TIMEOUT
        }
        if (isPinFailure(error)) return TransportFailure.PIN
        if (error is Tls12RequiredException || generateSequence(error) { it.cause }.any {
                (it is TlsFatalAlert && it.alertDescription == AlertDescription.protocol_version) ||
                    (it is TlsFatalAlertReceived && it.alertDescription == AlertDescription.protocol_version)
            }
        ) return TransportFailure.TLS12_REQUIRED
        if (error is SecurityException || generateSequence(error) { it.cause }.any { it is SecurityException }) return TransportFailure.PERMISSION
        if (error is UnknownHostException) return TransportFailure.DNS
        if (error is HttpFramingException || error is io.github.tuthan.steamoscompanion.core.protocol.JsonFormatException) {
            return TransportFailure.PROTOCOL
        }
        if (error is java.net.ConnectException || error is java.net.NoRouteToHostException) return TransportFailure.CONNECT
        return TransportFailure.IO
    }

    private fun isPinFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .any { it.message?.contains(PIN_MISMATCH_MESSAGE) == true || it is TlsFatalAlert && it.alertDescription == AlertDescription.bad_certificate }

    companion object {
        const val DEFAULT_BUDGET_MS = 5_000L
        const val INITIAL_PAIRING_BUDGET_MS = 6_000L
        const val APPROVAL_POLL_BUDGET_MS = 2_500L
        const val STATUS_BUDGET_MS = 2_500L
        const val DISCOVERY_PROBE_BUDGET_MS = 750L
        const val MAX_DISCOVERY_PROBE_BUDGET_MS = 5_000L
        const val MIN_BINDING_BYTES = 12
        const val PIN_MISMATCH_MESSAGE = "Pinned server certificate does not match"
        private val PIN = Regex("sha256:[0-9a-f]{64}")
        private const val LOCAL_NETWORK_PERMISSION_POLL_MILLIS = 100L
        private val LOCAL_NETWORK_PERMISSION_MONITOR = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "companion-lan-permission-monitor").apply { isDaemon = true }
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

private class Tls12RequiredException : IOException("The host does not offer TLS 1.2 for channel-bound pairing")

private class PinnedBootstrapClient(crypto: BcTlsCrypto, private val pin: String) : DefaultTlsClient(crypto) {
    fun binding(): ByteArray? = context.exportChannelBinding(ChannelBinding.tls_unique)
    fun resumed(): Boolean = context.securityParametersConnection.isResumedSession

    override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv12.only()

    override fun getSupportedCipherSuites(): IntArray = intArrayOf(
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
    )

    override fun getAuthentication(): TlsAuthentication = object : ServerOnlyTlsAuthentication() {
        override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
            val leaf = serverCertificate.certificate.getCertificateAt(0).encoded
            if (!fingerprintMatches(MessageDigest.getInstance("SHA-256").digest(leaf), pin)) {
                throw TlsFatalAlert(AlertDescription.bad_certificate)
            }
        }
    }
}
