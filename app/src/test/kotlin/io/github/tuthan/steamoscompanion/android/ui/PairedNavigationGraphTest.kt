package io.github.tuthan.steamoscompanion.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedNavigationGraphTest {
    @Test
    fun savedNavigationRestoresOnlyAllowlistedNonSecretRouteState() {
        val graph = PairedNavigationGraph()
        graph.navigate(UiRoute(UiDestination.DISCOVERY))
        graph.navigate(
            UiRoute(
                UiDestination.CANDIDATE_DETAILS,
                mapOf(
                    "endpoint" to "deck-1.invalid:18443",
                    "identity_fingerprint" to "sha256:" + "00".repeat(32),
                ),
            ),
        )
        graph.navigate(
            UiRoute(
                UiDestination.PAIRING,
                mapOf("candidate_generation" to "3", "pairing_variant" to "verification"),
            ),
        )
        graph.navigate(UiRoute(UiDestination.REMOTE_OVERVIEW))
        graph.navigate(
            UiRoute(
                UiDestination.DISPLAY,
                mapOf("output_id" to "output-1", "mode_id" to "mode-2", "generation" to "7"),
            ),
        )

        val saved = graph.saveState()
        val restored = PairedNavigationGraph.restore(saved)

        assertEquals(graph.routes, restored.routes)
        assertEquals(UiDestination.DISPLAY, restored.current.destination)
        assertFalse(saved.contains("token", ignoreCase = true))
        assertFalse(saved.contains("secret", ignoreCase = true))
        assertFalse(saved.contains("nonce", ignoreCase = true))
    }

    @Test
    fun pairingSecretsCannotBePlacedInRouteArguments() {
        val error = runCatching {
            UiRoute(UiDestination.PAIRING, mapOf("payload_secret" to "never-save-this"))
        }.exceptionOrNull()
        val userInfoError = runCatching {
            UiRoute(UiDestination.MANUAL_ADDRESS, mapOf("host" to "https://user:password@deck.invalid"))
        }.exceptionOrNull()
        val invalidEndpointError = runCatching {
            UiRoute(UiDestination.CANDIDATE_DETAILS, mapOf("endpoint" to "https://user:password@deck.invalid"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(userInfoError is IllegalArgumentException)
        assertTrue(invalidEndpointError is IllegalArgumentException)
    }

    @Test
    fun pairingRouteAcceptsOnlyTheTwoSupportedVariants() {
        for (variant in listOf("verification", "advanced_payload")) {
            assertEquals(
                variant,
                UiRoute(UiDestination.PAIRING, mapOf("candidate_generation" to "1", "pairing_variant" to variant))
                    .arguments.getValue("pairing_variant"),
            )
        }
        assertTrue(
            runCatching {
                UiRoute(UiDestination.PAIRING, mapOf("candidate_generation" to "1", "pairing_variant" to "legacy_code"))
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun navigationBackRestoresPreviousScreenWithoutRepeatingAnAction() {
        val graph = PairedNavigationGraph(UiRoute(UiDestination.REMOTE_OVERVIEW))
        graph.navigate(UiRoute(UiDestination.POWER))
        graph.navigate(UiRoute(UiDestination.CONNECTION_DETAILS))

        assertTrue(graph.back())
        assertEquals(UiDestination.POWER, graph.current.destination)
        assertTrue(graph.back())
        assertEquals(UiDestination.REMOTE_OVERVIEW, graph.current.destination)
        assertFalse(graph.back())
    }

    @Test
    fun previewAndUnresolvedOperationEachUseTheSharedRecoverySlotAcrossPairedDestinations() {
        val cards = listOf(
            PreviewRecoveryCard("HDMI display", "1280 × 720 · 60 Hz", 12_000),
            UnresolvedOperationCard("Restart", "op-fixture-1", "Result not confirmed"),
        )

        cards.forEach { card ->
            val state = PairedRecoveryState(card)
            UiDestination.entries.filter { it.paired }.forEach { destination ->
                assertEquals(listOf(card), state.cardsFor(destination))
            }
            assertTrue(state.cardsFor(UiDestination.PAIRING).isEmpty())
        }
    }

    @Test
    fun restoredStackRejectsUnknownSecretsAndInvalidTransitions() {
        val unknownArgument = """{"schemaVersion":1,"routes":[{"destination":"pairing","arguments":{"secret":"x"}}]}"""
        val invalidTransition = """{"schemaVersion":1,"routes":[{"destination":"onboarding","arguments":{}},{"destination":"power","arguments":{}}]}"""

        assertTrue(runCatching { PairedNavigationGraph.restore(unknownArgument) }.isFailure)
        assertTrue(runCatching { PairedNavigationGraph.restore(invalidTransition) }.isFailure)
    }
}
