package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.android.ui.PersistentReload.Navigation
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** What re-reading the saved pairing does to the session and the screen: reset, retry, edit address, new pairing. */
class PersistentReloadTest {
    private val paired = ActiveHost("host-1", "client-1", "192.0.2.5:18443", "sha256:" + "a".repeat(64), "token-one",
        listOf("status.read"), StoredWakeTarget(false, null, null, null, null), 1_000)

    private fun decide(hadProblem: Boolean = false, hasProblem: Boolean = false, current: ActiveHost?, next: ActiveHost?) =
        PersistentReload.decide(hadProblem, hasProblem, current, next)

    @Test fun nothingChangedDoesNothing() {
        assertEquals(PersistentReload.Decision(false, Navigation.NONE), decide(current = paired, next = paired))
        assertEquals(PersistentReload.Decision(false, Navigation.NONE), decide(current = null, next = null))
    }

    @Test fun aResetThatLeavesNothingPairedLeavesTheProblemScreenForTheUnpairedFlow() {
        assertEquals(PersistentReload.Decision(false, Navigation.UNPAIRED),
            decide(hadProblem = true, hasProblem = false, current = null, next = null))
    }

    @Test fun aRetryThatStillFindsTheProblemStaysWhereItIs() {
        assertEquals(PersistentReload.Decision(false, Navigation.NONE),
            decide(hadProblem = true, hasProblem = true, current = null, next = null))
    }

    @Test fun aRetryThatOpensTheSavedPairingRestartsTheSessionAndShowsThePairedScreens() {
        assertEquals(PersistentReload.Decision(true, Navigation.PAIRED),
            decide(hadProblem = true, hasProblem = false, current = null, next = paired))
    }

    @Test fun editingTheAddressRestartsTheSessionOnTheNewEndpointAndKeepsTheScreen() {
        val moved = paired.copy(endpoint = "192.0.2.9:18443")
        assertEquals(PersistentReload.Decision(true, Navigation.NONE), decide(current = paired, next = moved))
    }

    @Test fun aChangedWakeTargetOrScopeKeepsTheSessionAndTheScreen() {
        val withWake = paired.copy(wakeTarget = StoredWakeTarget(true, "e45f01aabbcc", "eth0", "192.0.2.9", null))
        assertEquals(PersistentReload.Decision(false, Navigation.NONE), decide(current = paired, next = withWake))
        assertEquals(PersistentReload.Decision(false, Navigation.NONE),
            decide(current = paired, next = paired.copy(grantedScopes = listOf("status.read", "power.control"))))
    }

    @Test fun aDifferentPairingRestartsTheSessionAndStartsFromTheOverview() {
        assertEquals(PersistentReload.Decision(true, Navigation.PAIRED),
            decide(current = paired, next = paired.copy(hostId = "host-2")))
        assertEquals(PersistentReload.Decision(true, Navigation.PAIRED),
            decide(current = paired, next = paired.copy(bearerToken = "token-two")))
        assertEquals(PersistentReload.Decision(true, Navigation.PAIRED),
            decide(current = paired, next = paired.copy(certificatePin = "sha256:" + "b".repeat(64))))
    }

    @Test fun forgettingThePairingClosesTheSessionAndReturnsToTheUnpairedFlow() {
        assertEquals(PersistentReload.Decision(true, Navigation.UNPAIRED), decide(current = paired, next = null))
    }
}
