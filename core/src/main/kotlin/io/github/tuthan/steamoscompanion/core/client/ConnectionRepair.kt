package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanner
import io.github.tuthan.steamoscompanion.core.discovery.ManualProbeResult
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal

sealed interface ConnectionRepairResult {
    data class Saved(val host: ActiveHost) : ConnectionRepairResult
    data class IdentityChanged(val announcedHostId: String, val announcedPin: String) : ConnectionRepairResult
    data class ProbeFailed(val reason: String) : ConnectionRepairResult
    data class StateFailed(val result: StateCommitResult) : ConnectionRepairResult
}

/** A new address is committed only after discovery proves the saved host ID and certificate pin. */
class ConnectionRepair(
    private val repository: EncryptedStateRepository,
    private val discovery: DiscoveryScanner,
) {
    fun verifyAndUpdateAddress(
        expected: ActiveHost,
        address: String,
        path: LanPath,
        cancellation: CancellationSignal = CancellationSignal(),
    ): ConnectionRepairResult {
        val endpoint = try { HostEndpoint.parse(address) }
        catch (error: IllegalArgumentException) { return ConnectionRepairResult.ProbeFailed(error.message ?: "Invalid address") }
        val candidate = when (val probe = discovery.probeManual(endpoint.authority, path, cancellation)) {
            is ManualProbeResult.Candidate -> probe.value
            is ManualProbeResult.Rejected -> return ConnectionRepairResult.ProbeFailed(probe.error)
            is ManualProbeResult.Failed -> return ConnectionRepairResult.ProbeFailed(probe.reason)
        }
        if (candidate.hostId != expected.hostId || candidate.certificateFingerprint != expected.certificatePin) {
            return ConnectionRepairResult.IdentityChanged(candidate.hostId, candidate.certificateFingerprint)
        }
        val saved = repository.transact { state ->
            val current = state.activeHost
                ?: throw StateInvariantException(StateInvariantCode.INVALID_IDENTITY, "Paired host is missing")
            if (current.hostId != expected.hostId || current.clientId != expected.clientId ||
                current.endpoint != expected.endpoint || current.certificatePin != expected.certificatePin ||
                current.pairedAtMillis != expected.pairedAtMillis || current.bearerToken != expected.bearerToken
            ) throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Pairing changed during address verification")
            state.copy(activeHost = current.copy(endpoint = endpoint.authority))
        }
        return when (saved) {
            is StateCommitResult.Saved -> ConnectionRepairResult.Saved(saved.state.activeHost!!)
            else -> ConnectionRepairResult.StateFailed(saved)
        }
    }
}
