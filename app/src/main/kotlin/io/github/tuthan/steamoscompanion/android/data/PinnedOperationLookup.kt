package io.github.tuthan.steamoscompanion.android.data

import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.OperationLookup
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget

/** Explicit foreground GET; it never retries or replays the operation's POST. */
class PinnedOperationLookup(
    private val transport: PinnedHttpTransport,
    private val networks: NetworkAccess,
) : OperationLookup {
    override fun get(host: ActiveHost, operationId: String): RequestOutcome = lookup(host, operationId, null)

    override fun get(host: ActiveHost, operationId: String, cancellation: CancellationSignal): RequestOutcome =
        lookup(host, operationId, cancellation)

    private fun lookup(host: ActiveHost, operationId: String, cancellation: CancellationSignal?): RequestOutcome {
        if (cancellation?.isCancelled == true) return RequestOutcome.NotSent(TransportFailure.CANCELLED)
        val paths = try {
            networks.activeLanPaths().let { active ->
                val preferred = host.wakeTarget.interfaceName
                if (preferred == null) active else active.sortedBy { it.interfaceName != preferred }
            }
        } catch (_: SecurityException) { return RequestOutcome.NotSent(TransportFailure.PERMISSION) }
        if (paths.isEmpty()) return RequestOutcome.NotSent(TransportFailure.CONNECT)
        val request = ApiRequest(HttpMethod.GET, ApiRoute.OPERATION.path.replace("{id}", operationId), bearerToken = host.bearerToken)
        val endpoint = HostEndpoint.parse(host.endpoint)
        var last: RequestOutcome = RequestOutcome.NotSent(TransportFailure.CONNECT)
        for (path in paths) {
            if (cancellation?.isCancelled == true) return RequestOutcome.NotSent(TransportFailure.CANCELLED)
            val result = transport.execute(TransportTarget(endpoint, path), host.certificatePin, request, cancellation)
            if (result is RequestOutcome.Reply || result is RequestOutcome.Rejected) return result
            if (result is RequestOutcome.NotSent && result.failure == TransportFailure.PIN) last = result
            else if (last !is RequestOutcome.NotSent || last.failure != TransportFailure.PIN) last = result
        }
        return last
    }
}
