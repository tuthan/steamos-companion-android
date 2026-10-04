package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.HostPaths
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.LinkFacts
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget

/**
 * The advisory `GET /v1/drm` read, from the saved endpoint, pin and bearer only, like the status gateway
 * and the operation lookup. It needs `status.read`, tries the networks that can hold the host in order,
 * and never replays anything: it is a GET with no side effect, and every network is tried at most once.
 *
 * It never throws. A 404 from an older host, any other rejection, a transport failure, a decode
 * problem, a cancellation or a missing permission all return null ("no facts") without a word.
 * Call it from a worker thread.
 */
class PinnedLinkFactsGateway(
    private val transport: PinnedHttpTransport,
    private val networks: NetworkAccess,
) : LinkFactsGateway {
    override fun read(host: ActiveHost, cancellation: CancellationSignal): LinkFacts? = try {
        readOrNull(host, cancellation)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        null
    }

    private fun readOrNull(host: ActiveHost, cancellation: CancellationSignal): LinkFacts? {
        if ("status.read" !in host.grantedScopes || cancellation.isCancelled) return null
        val endpoint = HostEndpoint.parse(host.endpoint)
        val request = ApiRequest(HttpMethod.GET, ApiRoute.DRM.path, bearerToken = host.bearerToken)
        for (path in HostPaths.candidates(networks.activeLanPaths(), host.endpoint, host.wakeTarget.interfaceName)) {
            if (cancellation.isCancelled) return null
            when (val outcome = transport.execute(TransportTarget(endpoint, path), host.certificatePin, request, cancellation)) {
                is RequestOutcome.Reply ->
                    return ((outcome.response as? ApiReply.Versioned)?.payload as? VersionedPayload.Drm)?.facts
                // The host answered: 404 not_found from a host without the route, or any other error envelope.
                is RequestOutcome.Rejected, is RequestOutcome.PairingUnresolved -> return null
                is RequestOutcome.NotSent -> if (outcome.failure in FINAL) return null
                is RequestOutcome.Unknown -> if (outcome.failure in FINAL || outcome.failure == TransportFailure.PROTOCOL) return null
            }
        }
        return null
    }

    private companion object {
        /** No other network can cure these: bad input, a cancelled request, a missing permission. */
        val FINAL = setOf(TransportFailure.VALIDATION, TransportFailure.CANCELLED, TransportFailure.PERMISSION)
    }
}
