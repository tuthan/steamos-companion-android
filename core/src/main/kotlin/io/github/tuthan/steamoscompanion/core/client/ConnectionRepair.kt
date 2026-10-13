package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanner
import io.github.tuthan.steamoscompanion.core.discovery.ManualProbeResult
import io.github.tuthan.steamoscompanion.core.ports.HostPaths
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal

sealed interface ConnectionRepairResult {
    data class Saved(val host: ActiveHost) : ConnectionRepairResult
    data class IdentityChanged(val announcedHostId: String, val announcedPin: String) : ConnectionRepairResult
    data class ProbeFailed(val reason: String) : ConnectionRepairResult
    data class StateFailed(val result: StateCommitResult) : ConnectionRepairResult

    /**
     * Find device again searched the network and the saved host did not answer. [partial] means the search did not
     * cover every address; [qualifiedPermission] is the API 36 case that cannot prove the compatibility restriction.
     */
    data class NotFound(val partial: Boolean, val qualifiedPermission: Boolean) : ConnectionRepairResult
}

/** A new address is committed only after discovery proves the saved host ID and certificate pin. */
class ConnectionRepair(
    private val repository: EncryptedStateRepository,
    private val discovery: DiscoveryScanner,
) {
    /**
     * Searches the local networks for the saved host. Only an answer with the saved host ID *and* the saved certificate
     * pin may move the address; the same host ID with another pin is reported as a changed identity and never trusted.
     * The search is the scanner's own, so a network change or `cancelActiveScan()` ends it; the follow-up verification of
     * the winning address is a separate probe with its own signal, because a timed-out scan leaves its signal cancelled.
     */
    fun findAgain(expected: ActiveHost): ConnectionRepairResult {
        val scan = discovery.scan()
        val sameHost = scan.candidates.filter { it.hostId == expected.hostId }
        val verified = sameHost.firstOrNull { it.certificateFingerprint == expected.certificatePin }
        if (verified == null) {
            val changed = sameHost.firstOrNull()
            return if (changed != null) {
                ConnectionRepairResult.IdentityChanged(changed.hostId, changed.certificateFingerprint)
            } else ConnectionRepairResult.NotFound(scan.partial, scan.qualifiedPermissionFailure)
        }
        return verifyAndUpdateAddress(expected, verified.target.endpoint.authority, verified.target.network)
    }

    /**
     * Edit address over every network that can reach [address]: its own LAN first, then any tunnel. The first answer that
     * is not a failed probe is final, so a changed identity or a stale pairing is never retried on another network.
     */
    fun verifyAndUpdateAddress(
        expected: ActiveHost,
        address: String,
        paths: List<LanPath>,
        preferredInterface: String? = null,
    ): ConnectionRepairResult {
        var last: ConnectionRepairResult = ConnectionRepairResult.ProbeFailed("No local network")
        for (path in HostPaths.candidates(paths, address, preferredInterface)) {
            val result = verifyAndUpdateAddress(expected, address, path)
            if (result !is ConnectionRepairResult.ProbeFailed) return result
            last = result
        }
        return last
    }

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
