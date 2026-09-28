package io.github.tuthan.steamoscompanion.android.data

import android.app.Activity
import android.os.Bundle
import android.os.Process
import androidx.test.runner.AndroidJUnitRunner
import io.github.tuthan.steamoscompanion.android.CompanionApplication
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.CandidatePhase
import io.github.tuthan.steamoscompanion.core.client.ConnectionReplacement
import io.github.tuthan.steamoscompanion.core.client.DisplayRecoveryPresentation
import io.github.tuthan.steamoscompanion.core.client.JournalEntry
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.JournalRejectionCode
import io.github.tuthan.steamoscompanion.core.client.JournalUpdateResult
import io.github.tuthan.steamoscompanion.core.client.OperationJournal
import io.github.tuthan.steamoscompanion.core.client.PairingCandidate
import io.github.tuthan.steamoscompanion.core.client.PairingStateTransactions
import io.github.tuthan.steamoscompanion.core.client.PairingVariant
import io.github.tuthan.steamoscompanion.core.client.PendingDisplayResult
import io.github.tuthan.steamoscompanion.core.client.PreviewPresentation
import io.github.tuthan.steamoscompanion.core.client.StagedCredential
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.DisplayRecoveryProfile
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject

/**
 * Manual four-command journey on a fresh dedicated emulator. Each of the first three commands
 * kills this target-app process after a durable commit; the next command starts a new process.
 * Ordinary Android tests still run through the inherited AndroidJUnitRunner path.
 */
class ProcessDeathInstrumentation : AndroidJUnitRunner() {
    private var stage: String? = null

    override fun onCreate(arguments: Bundle) {
        stage = arguments.getString("process_death_stage")
        super.onCreate(arguments)
    }

    override fun onStart() {
        val stage = stage ?: return super.onStart()
        try {
            val application = targetContext.applicationContext as CompanionApplication
            callApplicationOnCreate(application)
            require(application.runtime.startupJournalRecovery == JournalUpdateResult.Saved)
            val repository = AndroidAtomicStateStore.createRepository(targetContext)
            when (stage) {
                "seed_preview" -> seedPreview(repository)
                "approve_candidate" -> approveCandidate(repository)
                "swap_candidate" -> swapCandidate(repository)
                "verify_swap" -> verifySwap(repository)
                else -> error("Unknown process-death stage")
            }
            val evidence = Bundle().apply {
                putString("stage", stage)
                putInt("pid", Process.myPid())
                putString("result", "passed")
            }
            sendStatus(0, evidence)
            if (stage == "verify_swap") {
                finish(Activity.RESULT_OK, evidence)
            } else {
                // Leave the persisted state intact; the next invocation must recreate the app process.
                Thread.sleep(300)
                Process.killProcess(Process.myPid())
            }
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stage", stage)
                putString("result", "failed")
                putString("failure_type", error.javaClass.simpleName)
            })
        }
    }

    private fun seedPreview(repository: io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository) {
        require(repository.load() == StateLoadResult.Empty) { "Journey requires a fresh app install" }
        val old = oldHost()
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to string("death-preview"), "output_id" to string("1"),
                "mode_id" to string("target-mode"), "generation" to JsonValue.NumberValue("4")),
            old.bearerToken)
        val entry = JournalEntry.create(1, old, request, BoundedJson.encode(requireNotNull(request.body)), 100)
            .copy(phase = JournalPhase.ACCEPTED, operationId = "op-death-preview")
        val restoreRequest = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_RESTORE.path,
            jsonObject("request_id" to string("death-restore-readback"), "source" to string("preview")),
            old.bearerToken)
        val failedRestore = JournalEntry.create(2, old, restoreRequest,
            BoundedJson.encode(requireNotNull(restoreRequest.body)), 101)
            .copy(phase = JournalPhase.FAILED, rejectionCode = JournalRejectionCode.READBACK_NOT_CONFIRMED,
                resultSummary = "Rejected HTTP 409: readback_not_confirmed")
        val powerRequest = ApiRequest(HttpMethod.POST, ApiRoute.POWER.path,
            jsonObject("request_id" to string("death-power-prepared"), "action" to string("restart")),
            old.bearerToken)
        val prepared = JournalEntry.create(3, old, powerRequest,
            BoundedJson.encode(requireNotNull(powerRequest.body)), 102)
        require(repository.transact { it.copy(activeHost = old, journal = listOf(entry, failedRestore, prepared)) } is StateCommitResult.Saved)
        require(repository.recordRecoveryProfiles(old, listOf(profile())) is StateCommitResult.Saved)
        val candidate = PairingCandidate(
            generation = 0, variant = PairingVariant.VERIFICATION,
            clientId = "death-client-new", clientName = "Android Companion",
            requestedScopes = listOf("status.read", "display.control"),
            endpoint = "new-deck.local:18443", certificatePin = "sha256:" + "cd".repeat(32),
            hostId = null, createdAtMillis = 1_790_000_000_000, expiresAtMillis = null,
            phase = CandidatePhase.READY, verificationNonce = "AbCdEfGhIjKlMnOpQrStUv",
        )
        require(PairingStateTransactions(repository).beginCandidate(candidate) is StateCommitResult.Saved)
    }

    private fun approveCandidate(repository: io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository) {
        val state = ready(repository)
        require(state.activeHost == oldHost().copy(cachedRecoveryProfiles = listOf(profile())))
        require(state.activeHost?.cachedRecoveryProfiles == listOf(profile()))
        require(state.journal.single { it.path == ApiRoute.DISPLAY_PREVIEW.path }.operationId == "op-death-preview")
        require(DisplayRecoveryPresentation.latestFailure(oldHost(), state)?.reason ==
            JournalRejectionCode.READBACK_NOT_CONFIRMED)
        require(state.journal.single { it.path == ApiRoute.POWER.path }.phase == JournalPhase.UNKNOWN)
        require(DisplayRecoveryPresentation.project(oldHost(), state, StatusPollState(), 1_000_000_000L) ==
            PreviewPresentation.Pending(PendingDisplayResult.CHECKING_PREVIEW, 1))
        val candidate = requireNotNull(state.candidate)
        require(candidate.phase == CandidatePhase.READY)
        val staged = StagedCredential("death-client-new", "death-host-new", "death-token-new-xxxxxxxx",
            listOf("status.read", "display.control"), StoredWakeTarget(false, null, null, null, null),
            1_790_000_000_050)
        require(PairingStateTransactions(repository).updateCandidate(candidate.generation) {
            it.copy(hostId = staged.hostId, pairingId = "death-pair-new", phase = CandidatePhase.APPROVED_STAGED,
                verificationNonce = null, stagedCredential = staged)
        } is StateCommitResult.Saved)
    }

    private fun swapCandidate(repository: io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository) {
        val state = ready(repository)
        require(state.activeHost == oldHost().copy(cachedRecoveryProfiles = listOf(profile())))
        val candidate = requireNotNull(state.candidate)
        require(candidate.phase == CandidatePhase.APPROVED_STAGED)
        require(state.journal.single { it.path == ApiRoute.DISPLAY_PREVIEW.path }.operationId == "op-death-preview")
        require(DisplayRecoveryPresentation.latestFailure(oldHost(), state)?.reason ==
            JournalRejectionCode.READBACK_NOT_CONFIRMED)
        require(state.journal.single { it.path == ApiRoute.POWER.path }.phase == JournalPhase.UNKNOWN)
        val replacement = ConnectionReplacement(PairingStateTransactions(repository),
            OperationJournal(repository, AndroidClock()))
        require(replacement.confirm(requireNotNull(state.activeHost), candidate.generation) is StateCommitResult.Saved)
    }

    private fun verifySwap(repository: io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository) {
        val state = ready(repository)
        require(state.activeHost?.hostId == "death-host-new")
        require(state.activeHost?.clientId == "death-client-new")
        require(state.activeHost?.cachedRecoveryProfiles == null)
        require(state.candidate == null)
        require(state.journal.size == 3 && state.journal.all { it.hostId == oldHost().hostId })
        require(state.journal.single { it.path == ApiRoute.DISPLAY_PREVIEW.path }.operationId == "op-death-preview")
        require(state.journal.single { it.path == ApiRoute.DISPLAY_RESTORE.path }.rejectionCode ==
            JournalRejectionCode.READBACK_NOT_CONFIRMED)
        require(state.journal.single { it.path == ApiRoute.POWER.path }.phase == JournalPhase.UNKNOWN)
    }

    private fun ready(repository: io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository) =
        (repository.load() as StateLoadResult.Ready).state

    private fun oldHost() = ActiveHost(
        hostId = "death-host-old", clientId = "death-client-old", endpoint = "old-deck.local:18443",
        certificatePin = "sha256:" + "ab".repeat(32), bearerToken = "death-token-old-xxxxxxxx",
        grantedScopes = listOf("status.read", "display.control"),
        wakeTarget = StoredWakeTarget(false, null, null, null, null), pairedAtMillis = 1_790_000_000_000,
    )

    private fun profile() = DisplayRecoveryProfile("death-profile", "1",
        DisplayModeSummary("current-mode", 1920, 1080, "60"))

    private fun string(value: String) = JsonValue.StringValue(value)
}
