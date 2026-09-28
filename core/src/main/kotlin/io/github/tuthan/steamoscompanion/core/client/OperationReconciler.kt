package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal

fun interface OperationLookup {
    /** A read-only, certificate-pinned GET with the saved credential. */
    fun get(host: ActiveHost, operationId: String): RequestOutcome

    /** Implementations with cancellable transport should close an in-flight GET on stop. */
    fun get(host: ActiveHost, operationId: String, cancellation: CancellationSignal): RequestOutcome =
        get(host, operationId)
}

sealed interface ReconcileResult {
    data class Updated(val result: JournalUpdateResult) : ReconcileResult
    data class Unchanged(val reason: ReconcileHold) : ReconcileResult
}

enum class ReconcileHold {
    STORE_UNAVAILABLE, PAIRING_CHANGED, ENTRY_NOT_FOUND, ENTRY_TERMINAL,
    OPERATION_ID_MISSING, LOOKUP_FAILED, OPERATION_IDENTITY_MISMATCH,
    CANCELLED,
}

/** Foreground-only operation lookup. No mutation is ever replayed. */
class OperationReconciler(
    private val repository: EncryptedStateRepository,
    private val journal: OperationJournal,
    private val lookup: OperationLookup,
) {
    fun reconcile(expectedHost: ActiveHost, localGeneration: Long, cancellation: CancellationSignal? = null): ReconcileResult {
        if (cancellation?.isCancelled == true) return ReconcileResult.Unchanged(ReconcileHold.CANCELLED)
        val state = (repository.load() as? StateLoadResult.Ready)?.state
            ?: return ReconcileResult.Unchanged(ReconcileHold.STORE_UNAVAILABLE)
        val active = state.activeHost
        if (active == null || !samePairing(active, expectedHost)) {
            return ReconcileResult.Unchanged(ReconcileHold.PAIRING_CHANGED)
        }
        val entry = state.journal.firstOrNull { it.localGeneration == localGeneration }
            ?: return ReconcileResult.Unchanged(ReconcileHold.ENTRY_NOT_FOUND)
        if (!entry.belongsTo(active)) return ReconcileResult.Unchanged(ReconcileHold.PAIRING_CHANGED)
        if (!entry.phase.unresolved) return ReconcileResult.Unchanged(ReconcileHold.ENTRY_TERMINAL)
        val operationId = entry.operationId ?: return ReconcileResult.Unchanged(ReconcileHold.OPERATION_ID_MISSING)
        val response = if (cancellation == null) lookup.get(active, operationId)
            else lookup.get(active, operationId, cancellation)
        if (cancellation?.isCancelled == true) return ReconcileResult.Unchanged(ReconcileHold.CANCELLED)
        return when (response) {
            is RequestOutcome.Reply -> {
                val operation = ((response.response as? ApiReply.Versioned)?.payload as? VersionedPayload.Operation)?.operation
                    ?: return ReconcileResult.Unchanged(ReconcileHold.LOOKUP_FAILED)
                if (operation.id != operationId || operation.requestId != entry.requestId ||
                    operation.kind != entry.expectedOperationKind()
                ) return ReconcileResult.Unchanged(ReconcileHold.OPERATION_IDENTITY_MISMATCH)
                reconciled(journal.applyOperationSnapshot(
                    active, entry.localGeneration, entry.hostId, entry.clientId, entry.originalEndpoint,
                    entry.originalCertificatePin, operation.id, operation.requestId, operation.kind,
                    operation.state, operation.outcome,
                ))
            }
            is RequestOutcome.Rejected -> if (response.response.status == 404) {
                reconciled(journal.markOperationNotFound(
                    active, entry.localGeneration, entry.hostId, entry.clientId, entry.originalEndpoint,
                    entry.originalCertificatePin, operationId, entry.requestId,
                ))
            } else ReconcileResult.Unchanged(ReconcileHold.LOOKUP_FAILED)
            else -> ReconcileResult.Unchanged(ReconcileHold.LOOKUP_FAILED)
        }
    }

    private fun reconciled(result: JournalUpdateResult): ReconcileResult =
        if (result == JournalUpdateResult.Rejected(StateInvariantCode.STALE_GENERATION)) {
            ReconcileResult.Unchanged(ReconcileHold.PAIRING_CHANGED)
        } else ReconcileResult.Updated(result)

    private fun samePairing(a: ActiveHost, b: ActiveHost): Boolean =
        a.hostId == b.hostId && a.clientId == b.clientId && a.endpoint == b.endpoint &&
            a.certificatePin == b.certificatePin && a.pairedAtMillis == b.pairedAtMillis && a.bearerToken == b.bearerToken
}
