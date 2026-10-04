package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HostScopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words are the Decky host's own (`host/frontend/index.js`, `accessRole`, `accessSummary`, `accessNote`):
 * the owner reads the same role and permissions on the host and on this phone.
 */
class HostAccessPresentationTest {
    private val status = HostScopes.STATUS_READ
    private val power = HostScopes.POWER_CONTROL
    private val display = HostScopes.DISPLAY_CONTROL

    @Test fun allThreeScopesAreFullAccessWithNoNote() {
        val view = HostAccessPresentation.view(listOf(status, power, display))
        assertEquals("Full access", view.role)
        assertEquals("Status · Power · Display", view.summary)
        assertEquals("Full access · Status · Power · Display", view.line)
        assertNull(view.note)
    }

    @Test fun statusReadAloneIsViewOnly() {
        val view = HostAccessPresentation.view(listOf(status))
        assertEquals("View only", view.role)
        assertEquals("Status", view.summary)
        assertEquals("View only · Status", view.line)
        assertEquals("The owner of the host chose this and can change it there.", view.note)
    }

    @Test fun noScopesIsNoAccessAndSaysNoPermissions() {
        val view = HostAccessPresentation.view(emptyList())
        assertEquals("No access", view.role)
        assertEquals("No permissions", view.summary)
        assertEquals("No access · No permissions", view.line)
        assertEquals(HostAccessPresentation.OWNER_NOTE, view.note)
    }

    @Test fun anythingElseIsCustomInTheCanonicalOrderWhateverTheInputOrder() {
        assertEquals("Custom · Status · Power", HostAccessPresentation.view(listOf(status, power)).line)
        assertEquals("Custom · Status · Display", HostAccessPresentation.view(listOf(display, status)).line)
        assertEquals("Custom · Power · Display", HostAccessPresentation.view(listOf(display, power)).line)
        assertEquals("Custom · Power", HostAccessPresentation.view(listOf(power)).line)
        assertEquals("Custom · Display", HostAccessPresentation.view(listOf(display)).line)
        assertEquals("Full access · Status · Power · Display", HostAccessPresentation.view(listOf(display, power, status)).line)
        listOf(listOf(status, power), listOf(power), listOf(display, status)).forEach {
            assertEquals(HostAccessPresentation.OWNER_NOTE, HostAccessPresentation.view(it).note)
        }
    }

    @Test fun scopesTheHostDoesNotGrantAreNeverNamed() {
        val view = HostAccessPresentation.view(listOf(status, "sunshine.control", "admin"))
        assertEquals("View only · Status", view.line)
        assertEquals("No access · No permissions", HostAccessPresentation.view(listOf("sunshine.control")).line)
    }

    @Test fun aDisabledControlSaysWhichPermissionTheOwnerHasNotAllowed() {
        assertEquals("The host owner has not allowed Power for this pairing.", HostAccessPresentation.denied(power))
        assertEquals("The host owner has not allowed Display for this pairing.", HostAccessPresentation.denied(display))
    }

    @Test fun allowsReadsTheHeldScopesOnly() {
        assertTrue(HostAccessPresentation.allows(listOf(status, power), power))
        assertFalse(HostAccessPresentation.allows(listOf(status, power), display))
        assertFalse(HostAccessPresentation.allows(emptyList(), status))
    }

    @Test fun onlyAFailedForbiddenEntryGetsTheOwnersWords() {
        val host = testActiveHost()
        val request = testPowerRequest("refusal-1")
        val entry = JournalEntry.create(1, host, request,
            BoundedJson.encode(request.body!!), 100)
        val forbidden = entry.copy(phase = JournalPhase.FAILED, rejectionCode = JournalRejectionCode.FORBIDDEN)
        assertEquals("The host owner has not allowed this action for this pairing.", HostAccessPresentation.refusal(forbidden))
        assertNull(HostAccessPresentation.refusal(forbidden.copy(phase = JournalPhase.UNKNOWN)))
        JournalRejectionCode.entries.filter { it != JournalRejectionCode.FORBIDDEN }.forEach {
            assertNull(it.name, HostAccessPresentation.refusal(forbidden.copy(rejectionCode = it)))
        }
        assertNull(HostAccessPresentation.refusal(forbidden.copy(rejectionCode = null)))
    }
}
