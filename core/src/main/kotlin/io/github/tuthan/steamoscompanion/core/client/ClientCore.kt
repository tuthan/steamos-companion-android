package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanResult
import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanner
import io.github.tuthan.steamoscompanion.core.discovery.ManualProbeResult
import io.github.tuthan.steamoscompanion.core.pairing.PairingAction
import io.github.tuthan.steamoscompanion.core.pairing.PairingCoordinator
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import java.io.Closeable
import java.util.concurrent.CompletableFuture

/**
 * Stable JVM-facing entry point for discovery, pairing, and persisted host observations.
 * Network and CPU work keeps the threading behavior of [DiscoveryScanner] and [PairingCoordinator].
 */
class ClientCore(
    private val repository: EncryptedStateRepository,
    private val discovery: DiscoveryScanner,
    private val pairing: PairingCoordinator,
) : Closeable {
    fun scan(
        paths: List<LanPath>? = null,
        budgetMillis: Long = DiscoveryScanner.DEFAULT_SCAN_BUDGET_MS,
        cancellation: CancellationSignal? = null,
    ): DiscoveryScanResult = if (paths == null) {
        discovery.scan(budgetMillis = budgetMillis, externalCancellation = cancellation)
    } else {
        discovery.scan(paths = paths, budgetMillis = budgetMillis, externalCancellation = cancellation)
    }

    fun probeManualAddress(
        endpoint: String,
        path: LanPath,
        cancellation: CancellationSignal? = null,
    ): ManualProbeResult = discovery.probeManual(endpoint, path, cancellation)

    fun startNoncePairing(
        candidate: UnverifiedDiscoveryCandidate,
        cancellation: CancellationSignal = CancellationSignal(),
    ): CompletableFuture<PairingAction> = pairing.startNonce(candidate, cancellation = cancellation)

    fun startPayloadPairing(
        payload: String,
        networkId: String?,
        cancellation: CancellationSignal = CancellationSignal(),
    ): CompletableFuture<PairingAction> = pairing.startPayload(payload, networkId, cancellation = cancellation)

    fun requestInitial(generation: Long, cancellation: CancellationSignal = CancellationSignal()): CompletableFuture<PairingAction> =
        pairing.requestInitial(generation, cancellation)

    fun poll(generation: Long, cancellation: CancellationSignal = CancellationSignal()): CompletableFuture<PairingAction> =
        pairing.poll(generation, cancellation)

    fun cancel(generation: Long, cancellation: CancellationSignal = CancellationSignal()): CompletableFuture<PairingAction> =
        pairing.cancel(generation, cancellation)

    fun resumePairing(): CompletableFuture<PairingAction> = pairing.resume()

    /**
     * Persist the boot ID from a successfully parsed, authenticated status reply. Callers must
     * pass the exact active-host snapshot used for the certificate-pinned request; the repository
     * rejects it if pairing changed while the request was in flight.
     */
    fun recordAuthenticatedStatus(expectedHost: ActiveHost, status: VersionedPayload.Status): StateCommitResult {
        if (status.hostId != expectedHost.hostId) return StateCommitResult.Rejected(StateInvariantCode.INVALID_IDENTITY)
        return repository.recordObservedBootId(expectedHost, status.bootId)
    }

    fun networkChanged() = discovery.networkChanged()

    override fun close() = discovery.close()
}
