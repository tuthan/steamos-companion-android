package io.github.tuthan.steamoscompanion.core.client

import java.util.UUID

sealed interface ReplacementDiscardResult {
    data class RemovedOnHost(val entry: JournalEntry) : ReplacementDiscardResult
    /** The candidate was discarded locally, but the new host may still list this client. */
    data class HostRemovalUnconfirmed(val entry: JournalEntry) : ReplacementDiscardResult
    data class NotDiscarded(val result: JournalDispatchResult) : ReplacementDiscardResult
}

/** Final owner decision for an already approved, durably staged candidate. */
class ConnectionReplacement(
    private val transactions: PairingStateTransactions,
    private val journal: OperationJournal,
) {
    fun confirm(expectedOldHost: ActiveHost, candidateGeneration: Long): StateCommitResult =
        transactions.acceptApprovedCandidateFor(candidateGeneration, expectedOldHost)

    /** Called only while foregrounded, after the owner declines the replacement. */
    fun discard(candidateGeneration: Long, sender: JournalMutationSender): ReplacementDiscardResult =
        when (val result = journal.revokeAndDiscardApprovedCandidate(candidateGeneration, UUID.randomUUID().toString(), sender)) {
            is JournalDispatchResult.Finished -> if (result.entry.phase == JournalPhase.SUCCEEDED) {
                ReplacementDiscardResult.RemovedOnHost(result.entry)
            } else {
                ReplacementDiscardResult.HostRemovalUnconfirmed(result.entry)
            }
            is JournalDispatchResult.ResultNotConfirmed -> ReplacementDiscardResult.HostRemovalUnconfirmed(result.entry)
            else -> ReplacementDiscardResult.NotDiscarded(result)
        }
}
