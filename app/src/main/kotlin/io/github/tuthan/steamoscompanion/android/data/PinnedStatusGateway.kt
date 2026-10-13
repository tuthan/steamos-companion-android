package io.github.tuthan.steamoscompanion.android.data

import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.PairedStatusGateway
import io.github.tuthan.steamoscompanion.core.client.PairedStatusRead
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.ports.LegacyLocalNetworkFailure
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.ResponseTiming
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import java.io.IOException

/** Reads only from the saved endpoint, pin, and bearer; dependent reads have no separate timer. */
class PinnedStatusGateway(
    private val transport: PinnedHttpTransport,
    private val networks: NetworkAccess,
    private val repository: EncryptedStateRepository,
) : PairedStatusGateway {
    override fun read(host: ActiveHost, cancellation: CancellationSignal): PairedStatusRead {
        // The host enforces status.read itself. A pairing it now reports without it must keep polling, or a later
        // grant could never be learned; this snapshot's scopes may also be older than what the host last said.
        val endpoint = HostEndpoint.parse(host.endpoint)
        val paths = networks.activeLanPaths().let { active ->
            val preferred = host.wakeTarget.interfaceName
            if (preferred == null) active else active.sortedBy { it.interfaceName != preferred }
        }
        if (paths.isEmpty()) throw IOException("No local network")
        var lastFailure: IOException? = null
        var pinFailure: IOException? = null
        var qualifiedFailure: IOException? = null
        for (path in paths) {
            if (cancellation.isCancelled) throw IOException("Status read cancelled")
            try {
                return readOnPath(host, endpoint, path, cancellation)
            } catch (error: IOException) {
                if (cancellation.isCancelled) throw IOException("Status read cancelled", error)
                lastFailure = error
                if (error is PinnedCertificateChanged) pinFailure = error
                if (error is LegacyLocalNetworkFailure) qualifiedFailure = error
            }
        }
        throw pinFailure ?: qualifiedFailure ?: lastFailure ?: IOException("Status read failed")
    }

    private fun readOnPath(
        host: ActiveHost,
        endpoint: HostEndpoint,
        path: LanPath,
        cancellation: CancellationSignal,
    ): PairedStatusRead {
        val target = TransportTarget(endpoint, path)
        fun fetch(route: ApiRoute): Pair<VersionedPayload?, ResponseTiming?> {
            val request = ApiRequest(HttpMethod.GET, route.path, bearerToken = host.bearerToken)
            return when (val outcome = transport.execute(target, host.certificatePin, request, cancellation)) {
                is RequestOutcome.Reply -> {
                    val payload = (outcome.response as? ApiReply.Versioned)?.payload
                        ?: throw IOException("Host protocol version is unsupported for ${route.path}")
                    payload to outcome.timing
                }
                is RequestOutcome.Rejected -> throw IOException("Host rejected ${route.path}: ${outcome.response.error}")
                is RequestOutcome.NotSent -> throw failure(route, outcome.failure)
                is RequestOutcome.Unknown -> throw failure(route, outcome.failure, outcome.diagnostic)
                is RequestOutcome.PairingUnresolved -> throw IOException("Unexpected pairing response")
            }
        }
        val (statusPayload, timing) = fetch(ApiRoute.STATUS)
        val status = statusPayload as? VersionedPayload.Status
            ?: throw IOException("Invalid status response")
        if (status.hostId != host.hostId) throw IllegalStateException("Host identity changed; re-pairing is required")
        val saved = (repository.load() as? StateLoadResult.Ready)?.state?.activeHost
            ?: throw IllegalStateException("Paired state is unavailable")
        if (saved.hostId != host.hostId || saved.clientId != host.clientId ||
            saved.endpoint != host.endpoint || saved.certificatePin != host.certificatePin ||
            saved.pairedAtMillis != host.pairedAtMillis || saved.bearerToken != host.bearerToken
        ) throw IllegalStateException("Active pairing changed during status read")
        if (cancellation.isCancelled) throw IOException("Status read cancelled")
        if (saved.lastObservedBootId != status.bootId) {
            val stored = repository.recordObservedBootId(host, status.bootId)
            if (stored !is StateCommitResult.Saved) throw IllegalStateException("Active pairing changed during status read")
        }
        if (cancellation.isCancelled) throw IOException("Status read cancelled")
        // The owner can change this pairing's scopes after pairing; the host's word wins, up or down. A host that
        // does not say (an older one) leaves what is stored alone.
        val adopted = repository.recordReportedScopes(host, status.credentialScopes)
        if (adopted != null && adopted !is StateCommitResult.Saved) {
            throw IllegalStateException("Granted permissions could not be saved for this pairing")
        }
        if (cancellation.isCancelled) throw IOException("Status read cancelled")
        var outputsError: String? = null
        val (outputsPayload, outputsTiming) = try { fetch(ApiRoute.DISPLAY_OUTPUTS) }
        catch (error: PinnedCertificateChanged) { throw error }
        catch (error: IOException) {
            outputsError = error.message ?: "Display output request failed"
            null to null
        }
        val outputs = outputsPayload as? VersionedPayload.DisplayOutputs
        if (outputsPayload != null && outputs == null) outputsError = "Unexpected display output response"
        if (cancellation.isCancelled) throw IOException("Status read cancelled")
        if (outputs != null && outputs.available && !outputs.legacyShape) {
            val stored = repository.recordRecoveryProfiles(host, outputs.profiles)
            if (stored !is StateCommitResult.Saved) throw IllegalStateException("Recovery inventory could not be saved for this pairing")
        }
        if (cancellation.isCancelled) throw IOException("Status read cancelled")
        val order = try { fetch(ApiRoute.DISPLAY_ORDER).first as? VersionedPayload.DisplayOrder }
        catch (error: PinnedCertificateChanged) { throw error }
        catch (_: IOException) { null }
        return PairedStatusRead(status, outputs, order, timing, outputsTiming, outputsError)
    }

    private fun failure(route: ApiRoute, reason: TransportFailure, diagnostic: String? = null): IOException =
        statusReadFailure(route.path, reason, diagnostic)
}

/** How a transport failure on a status route reaches the screen: a changed pin, the qualified API 36 case, or a plain error. */
internal fun statusReadFailure(path: String, reason: TransportFailure, diagnostic: String? = null): IOException = when (reason) {
    TransportFailure.PIN -> PinnedCertificateChanged("$path: certificate changed; re-pairing is required")
    // The API 36 case that cannot prove the compatibility restriction carries its qualified wording to the screen.
    TransportFailure.LEGACY_PERMISSION_MAY_BE_REQUIRED -> LegacyLocalNetworkFailure()
    else -> IOException("$path: $reason${diagnostic?.let { " ($it)" }.orEmpty()}")
}

class PinnedCertificateChanged(message: String) : IOException(message)
