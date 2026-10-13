package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.ConnectionRepairResult
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StateInvariantCode
import io.github.tuthan.steamoscompanion.core.client.StatePersistenceFailure
import io.github.tuthan.steamoscompanion.core.client.StateResetResult
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.discovery.ManualProbeResult
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure logic behind Rename, Edit address, Find device again, Reset local pairing and the manual-address message. */
class ConnectionEditingTest {
    private val host = ActiveHost("host-1", "client-1", "192.0.2.5:18443", "sha256:" + "a".repeat(64), "token",
        listOf("status.read"), StoredWakeTarget(false, null, null, null, null), 1)

    @Test fun aNameIsTrimmedCappedAndNeverContainsAControlCharacter() {
        assertEquals("Living-room Deck", DeviceNameInput.alias("  Living-room Deck \n"))
        assertNull("blank clears the alias", DeviceNameInput.alias("   "))
        assertNull(DeviceNameInput.alias(""))
        assertEquals("ab", DeviceNameInput.sanitize("a\u0000b\u0007"))
        assertEquals(64, DeviceNameInput.sanitize("x".repeat(200)).length)
        // Whatever typing can produce is something the alias store accepts.
        val typed = DeviceNameInput.alias("\u0001" + "é".repeat(100))!!
        assertTrue(typed.length <= 64 && typed.none(Char::isISOControl))
    }

    @Test fun theAddressDraftStartsFromTheSavedEndpointAndOnlyAValidOneYieldsAnAuthority() {
        val draft = AddressDraft.from("192.0.2.5:18443")
        assertEquals("192.0.2.5", draft.host)
        assertEquals("18443", draft.port)
        assertEquals("192.0.2.5:18443", draft.authority)

        assertEquals("192.0.2.9:9000", draft.withHost("192.0.2.9").withPort("9000").authority)
        assertNull("no port", draft.withPort("").authority)
        assertNull("port zero", draft.withPort("0").authority)
        assertNull("port too large", draft.withPort("70000").authority)
        assertNull("no host", draft.withHost("").authority)
        assertEquals("whitespace never enters the host", "deck.local", draft.withHost(" deck.\nlocal ").host)
        assertEquals("only digits enter the port", "123", draft.withPort("1a2b3").port)
        assertEquals("a host with a path is not an endpoint", null, draft.withHost("deck.local/x").authority)
    }

    @Test fun anUnreadableSavedEndpointFallsBackToTheDefaultPort() {
        val draft = AddressDraft.from("not an endpoint")
        assertEquals("", draft.host)
        assertEquals("18443", draft.port)
    }

    @Test fun everyRepairOutcomeSaysWhetherTheSavedAddressChanged() {
        val (saved, savedTone) = ConnectionRepairMessages.forResult(
            ConnectionRepairResult.Saved(host.copy(endpoint = "192.0.2.8:18443")))
        assertEquals("Address updated to 192.0.2.8:18443", saved)
        assertEquals(StatusTone.GOOD, savedTone)

        val (changed, changedTone) = ConnectionRepairMessages.forResult(
            ConnectionRepairResult.IdentityChanged("host-1", "sha256:" + "b".repeat(64)))
        assertTrue(changed.startsWith("Device identity changed"))
        assertTrue("the saved address stays", changed.contains("not changed"))
        assertTrue("it points at pairing again, never at trusting", changed.contains("pair again"))
        assertEquals(StatusTone.BLOCKED, changedTone)

        val (probe, probeTone) = ConnectionRepairMessages.forResult(ConnectionRepairResult.ProbeFailed("connect"))
        assertTrue(probe.contains("not changed"))
        assertEquals(StatusTone.WARNING, probeTone)

        val (stale, _) = ConnectionRepairMessages.forResult(ConnectionRepairResult.StateFailed(
            StateCommitResult.Rejected(StateInvariantCode.STALE_GENERATION)))
        assertTrue(stale.contains("Pairing changed"))
        val (unsaved, _) = ConnectionRepairMessages.forResult(ConnectionRepairResult.StateFailed(
            StateCommitResult.Failed(StatePersistenceFailure.IO)))
        assertTrue(unsaved.contains("current address remains"))

        val (missing, _) = ConnectionRepairMessages.forResult(ConnectionRepairResult.NotFound(false, false))
        assertTrue(missing.contains("not found"))
        val (partial, _) = ConnectionRepairMessages.forResult(ConnectionRepairResult.NotFound(true, false))
        assertTrue(partial.contains("weren't searched"))
    }

    @Test fun theApi36FailureIsNamedWhereTheTransportClassifiedIt() {
        val api36 = LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE
        assertEquals(api36, ConnectionRepairMessages.forResult(ConnectionRepairResult.ProbeFailed(api36)).first)
        assertEquals(api36, ConnectionRepairMessages.forResult(ConnectionRepairResult.NotFound(true, true)).first)
        assertEquals(api36, manualProbeMessage(ManualProbeResult.Failed(api36, qualifiedPermission = true)))
        assertEquals("No SteamOS Companion server responded at this address",
            manualProbeMessage(ManualProbeResult.Failed("connect")))
        assertEquals("No SteamOS Companion server responded at this address",
            manualProbeMessage(ManualProbeResult.Rejected(404, "not_found")))
        assertNull(manualProbeMessage(ManualProbeResult.Candidate(
            io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate(
                io.github.tuthan.steamoscompanion.core.transport.TransportTarget(
                    io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint.parse("192.0.2.5:18443"),
                    io.github.tuthan.steamoscompanion.core.ports.LanPath("w", null, emptyList(), emptyList())),
                "h", null, "sha256:" + "a".repeat(64)))))
    }

    @Test fun resetReportsSuccessOnlyWhenTheFileAndKeyAreBothGone() {
        val (done, doneTone) = resetLocalPairingMessage(StateResetResult.Cleared)
        assertEquals("Local pairing reset. Pair again to continue.", done)
        assertEquals(StatusTone.GOOD, doneTone)
        for (failure in StatePersistenceFailure.entries) {
            val (text, tone) = resetLocalPairingMessage(StateResetResult.Failed(failure))
            assertEquals("Could not remove the saved pairing; try again.", text)
            assertEquals(StatusTone.WARNING, tone)
        }
    }

    @Test fun theQualifiedWordingResourceStaysEqualToThePolicyConstant() {
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        val match = Regex("""<string name="ui_nearby_device_permission_maybe_required">(.*?)</string>""").find(strings)!!
        assertEquals(LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE, match.groupValues[1])
    }
}
