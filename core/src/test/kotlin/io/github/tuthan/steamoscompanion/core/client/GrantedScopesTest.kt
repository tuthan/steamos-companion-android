package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.asLong
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner of the host can change a pairing's scopes after pairing. A status read replaces the stored scopes with what
 * the host reports (the host's word wins, up or down), every gate reads that stored value, and a 403 the host sends
 * before this client has learned of the change is shown plainly and leaves the pairing exactly as it was.
 */
class GrantedScopesTest {
    private val clock = object : Clock {
        override fun wallTimeMillis() = 100L
        override fun monotonicNanos() = 2_000_000_000L
    }
    private val store = MemoryStateStore()
    private val cipher = JvmAesGcmStateCipher()
    private val repository = EncryptedStateRepository(store, cipher)
    private val journal = OperationJournal(repository, clock)
    private val controls = ControlDispatcher(repository, journal, clock)
    private val displays = DisplayDispatcher(repository, journal, clock)
    private val host = testActiveHost()
    private val neverSends = JournalMutationSender { _, _, _ -> error("must not send") }

    private fun seed(active: ActiveHost = host) {
        assertTrue(repository.transact { it.copy(activeHost = active) } is StateCommitResult.Saved)
    }

    private fun stored(): ActiveHost = (repository.load() as StateLoadResult.Ready).state.activeHost!!

    /** What a successful status read does with the host's report. */
    private fun read(name: String, expected: ActiveHost = host): StateCommitResult? =
        repository.recordReportedScopes(expected, status(name).credentialScopes)

    // --- the store replace -----------------------------------------------------------------

    @Test fun aViewOnlyReportReplacesFullScopesAndPersists() {
        seed()
        assertTrue(read("view-only-credential") is StateCommitResult.Saved)
        assertEquals(listOf("status.read"), stored().grantedScopes)
        // A fresh load from the encrypted bytes agrees: the value survives a process restart.
        assertEquals(listOf("status.read"), (EncryptedStateRepository(store, cipher).load() as StateLoadResult.Ready)
            .state.activeHost!!.grantedScopes)
    }

    @Test fun theHostsWordWinsInBothDirections() {
        seed()
        read("view-only-credential")
        assertEquals(listOf("status.read"), stored().grantedScopes)
        read("scopes-changed-by-owner")
        assertEquals(listOf("status.read", "display.control"), stored().grantedScopes)
        read("sunshine-running")
        assertEquals(listOf("status.read", "power.control", "display.control"), stored().grantedScopes)
        read("view-only-credential")
        assertEquals(listOf("status.read"), stored().grantedScopes)
    }

    @Test fun anEmptyReportIsStoredAsNoAccessAndTheStateStillLoads() {
        seed()
        assertTrue(repository.recordReportedScopes(host, emptyList()) is StateCommitResult.Saved)
        assertEquals(emptyList<String>(), stored().grantedScopes)
        assertEquals(StateLoadResult.Ready::class, repository.load()::class)
    }

    @Test fun aHostThatDoesNotSayLeavesTheStoredScopesAndTheFileAlone() {
        seed()
        read("view-only-credential")
        val writes = store.writeCount
        val older = status("sunshine-running").copy(credentialScopes = null)
        assertNull(repository.recordReportedScopes(host, older.credentialScopes))
        assertEquals(listOf("status.read"), stored().grantedScopes)
        assertEquals(writes, store.writeCount)
    }

    @Test fun aReportThatMatchesWhatIsStoredWritesNothing() {
        seed()
        read("sunshine-running")
        val writes = store.writeCount
        val revision = (repository.load() as StateLoadResult.Ready).state.revision
        repeat(5) { assertTrue(read("sunshine-running") is StateCommitResult.Saved) }
        assertEquals(writes, store.writeCount)
        assertEquals(revision, (repository.load() as StateLoadResult.Ready).state.revision)
    }

    @Test fun aReplacedPairingIsNeverGivenTheOldPairingsScopes() {
        val rotated = host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        seed(rotated)
        assertEquals(StateCommitResult.Rejected(StateInvariantCode.INVALID_IDENTITY), read("view-only-credential", expected = host))
        assertEquals(host.grantedScopes, stored().grantedScopes)
    }

    @Test fun nothingButGrantableScopesInOrderWithoutDuplicatesIsEverStored() {
        seed()
        for (bad in listOf(listOf("admin"), listOf("status.read", "status.read"), listOf("status.read", "sunshine.control"),
            listOf("status.read", "power.control", "display.control", "status.read"))) {
            assertEquals(bad.toString(), StateCommitResult.Rejected(StateInvariantCode.INVALID_FIELD),
                repository.recordReportedScopes(host, bad))
        }
        assertEquals(host.grantedScopes, stored().grantedScopes)
    }

    @Test fun otherSnapshotWritesNeverBringBackScopesTheHostRemoved() {
        seed()
        read("view-only-credential")
        // The poller keeps the ActiveHost it started with; its later writes must not revert what the host said.
        assertTrue(repository.recordObservedBootId(host, "boot-2") is StateCommitResult.Saved)
        assertEquals(listOf("status.read"), stored().grantedScopes)
        assertEquals("boot-2", stored().lastObservedBootId)
        assertTrue(repository.recordRecoveryProfiles(host, emptyList()) is StateCommitResult.Saved)
        assertEquals(listOf("status.read"), stored().grantedScopes)
    }

    // --- every gate reads the stored value -------------------------------------------------

    @Test fun powerAndSunshineFollowTheStoredScopeNotTheSnapshotTheCallerHolds() {
        seed()
        val confirmation = PowerConfirmation.forHost(host, PowerVerb.SUSPEND)
        read("view-only-credential")
        // `host` is the stale full-scope snapshot, as in the poller; the stored pairing is view only.
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.MISSING_SCOPE),
            controls.power(host, confirmation, observation(), neverSends))
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.MISSING_SCOPE),
            controls.recoverSunshine(host, observation(), neverSends))
        read("scopes-changed-by-owner")
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.MISSING_SCOPE),
            controls.power(host, confirmation, observation(), neverSends))
        read("sunshine-running")
        assertTrue(controls.power(host, confirmation, observation(), JournalMutationSender { _, _, _ ->
            RequestOutcome.NotSent(TransportFailure.CONNECT)
        }) is ControlDispatchResult.Dispatched)
    }

    @Test fun everyDisplayRequestIsBlockedWithoutDisplayControlAndNoneOtherwise() {
        seed()
        val inventory = outputs("with-active-preview")
        val seen = observation(outputs = inventory, order = order("available"))
        val output = inventory.outputs.single()
        val saveCurrent = requireNotNull(SaveCurrentConfirmation.forOutput(host, output))
        val keep = KeepPreviewConfirmation.forPreview(host, requireNotNull(inventory.preview))
        val orderOutputs = requireNotNull(seen.lastRead?.order).snapshot.outputKeys
        val restart = OrderRestartConfirmation(StatusObservationSource.from(host), orderOutputs, 1)
        val requests = mapOf<String, () -> DisplayDispatchResult>(
            "preview" to { displays.preview(host, seen, DisplaySelection(output.id, output.generation, output.currentModeId), neverSends) },
            "saveCurrent" to { displays.saveCurrent(host, seen, saveCurrent, neverSends) },
            "restoreSaved" to { displays.restoreSaved(host, seen, "profile-fixture0001xxxxxxxxxxxxx", neverSends) },
            "revertPreview" to { displays.revertPreview(host, seen, neverSends) },
            "keepPreview" to { displays.keepPreview(host, seen, keep, neverSends) },
            "saveOrder" to { displays.saveOrder(host, seen, orderOutputs, 1, neverSends) },
            "saveOrderAndRestart" to { displays.saveOrderAndRestart(host, seen, restart, neverSends) },
            "resetOrder" to { displays.resetOrder(host, seen, neverSends) },
        )
        for (scopes in listOf(listOf("status.read"), listOf("status.read", "power.control"), emptyList())) {
            repository.recordReportedScopes(host, scopes)
            requests.forEach { (name, request) ->
                assertEquals("$name with $scopes", DisplayDispatchResult.Blocked(DisplayBlock.MISSING_SCOPE), request())
            }
        }
        for (scopes in listOf(listOf("status.read", "display.control"), host.grantedScopes)) {
            repository.recordReportedScopes(host, scopes)
            requests.forEach { (name, request) ->
                // Another block may still apply (a preview is active, the draft is stale), but never the scope.
                assertNotEquals("$name with $scopes", DisplayDispatchResult.Blocked(DisplayBlock.MISSING_SCOPE), request())
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun observation(
        outputs: VersionedPayload.DisplayOutputs = outputs("available"),
        order: VersionedPayload.DisplayOrder? = null,
    ) = StatusPollState(
        lastRead = PairedStatusRead(status("sunshine-stopped"), outputs, order),
        lastSuccessMonotonicNanos = 1_000_000_000L, source = StatusObservationSource.from(host),
    )

    private fun status(name: String): VersionedPayload.Status =
        decoded(ApiRoute.STATUS, "status/$name") as VersionedPayload.Status

    private fun outputs(name: String) = decoded(ApiRoute.DISPLAY_OUTPUTS, "display-outputs/$name") as VersionedPayload.DisplayOutputs
    private fun order(name: String) = decoded(ApiRoute.DISPLAY_ORDER, "display-order-get/$name") as VersionedPayload.DisplayOrder

    private fun decoded(route: ApiRoute, name: String): VersionedPayload {
        val body = fixtureResponse(name).second
        return (ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload
    }

    private fun fixtureResponse(name: String): Pair<Int, JsonValue.ObjectValue> {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/$name.json"))
        val response = document["response"].asObject("response")
        return response["status"].asLong("status").toInt() to response["body"].asObject("body")
    }
}
