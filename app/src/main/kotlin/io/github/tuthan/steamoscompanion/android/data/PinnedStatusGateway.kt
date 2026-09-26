package io.github.tuthan.steamoscompanion.android.data

import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.PairedStatusGateway
import io.github.tuthan.steamoscompanion.core.client.PairedStatusRead
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import java.io.IOException

/** Reads only from the saved endpoint, pin, and bearer; dependent reads have no separate timer. */
class PinnedStatusGateway(
    private val transport: PinnedHttpTransport,
    private val networks: NetworkAccess,
    private val repository: EncryptedStateRepository,
) : PairedStatusGateway {
    override fun read(host: ActiveHost, cancellation: CancellationSignal): PairedStatusRead {
        require("status.read" in host.grantedScopes) { "Status permission was not granted" }
        val endpoint = HostEndpoint.parse(host.endpoint)
        val path = networks.activeLanPaths().firstOrNull() ?: throw IOException("No local network")
        val target = TransportTarget(endpoint, path)
        fun fetch(route: ApiRoute): VersionedPayload? {
            val request = ApiRequest(HttpMethod.GET, route.path, bearerToken = host.bearerToken)
            return when (val outcome = transport.execute(target, host.certificatePin, request, cancellation)) {
                is RequestOutcome.Reply -> (outcome.response as? ApiReply.Versioned)?.payload
                is RequestOutcome.Rejected -> throw IOException("Host rejected ${route.path}: ${outcome.response.error}")
                is RequestOutcome.NotSent -> throw IOException("${route.path}: ${outcome.failure}")
                is RequestOutcome.Unknown -> throw IOException("${route.path}: ${outcome.failure}")
                is RequestOutcome.PairingUnresolved -> throw IOException("Unexpected pairing response")
            }
        }
        val status = fetch(ApiRoute.STATUS) as? VersionedPayload.Status
            ?: throw IOException("Invalid status response")
        if (status.hostId != host.hostId) throw IOException("Host identity changed")
        val saved = (repository.load() as? StateLoadResult.Ready)?.state?.activeHost
            ?: throw IOException("Paired state is unavailable")
        if (saved.hostId != host.hostId || saved.clientId != host.clientId ||
            saved.endpoint != host.endpoint || saved.certificatePin != host.certificatePin ||
            saved.pairedAtMillis != host.pairedAtMillis
        ) throw IOException("Active pairing changed during status read")
        if (saved.lastObservedBootId != status.bootId) {
            val stored = repository.recordObservedBootId(host, status.bootId)
            if (stored !is StateCommitResult.Saved) throw IOException("Active pairing changed during status read")
        }
        if (cancellation.isCancelled) throw IOException("Status read cancelled")
        val outputs = runCatching { fetch(ApiRoute.DISPLAY_OUTPUTS) as? VersionedPayload.DisplayOutputs }.getOrNull()
        if (cancellation.isCancelled) throw IOException("Status read cancelled")
        val order = runCatching { fetch(ApiRoute.DISPLAY_ORDER) as? VersionedPayload.DisplayOrder }.getOrNull()
        return PairedStatusRead(status, outputs, order)
    }
}
