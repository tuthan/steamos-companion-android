package io.github.tuthan.steamoscompanion.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rename locally: a name is stored on this phone only, per pairing, and never contains anything the host sees. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalAliasStoreTest {
    private val store = LocalAliasStore(ApplicationProvider.getApplicationContext<Context>())

    private fun host(hostId: String = "host-fixture0001xxxxxxxxxxxxx", clientId: String = "client-android") = ActiveHost(
        hostId, clientId, "deck.local:18443", "sha256:" + "ab".repeat(32), "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
        listOf("status.read"), StoredWakeTarget(false, "", null, null, null), 1_780_000_000_000)

    @Test fun aNameIsTrimmedStoredAndReadBack() {
        store.set(host(), "  Living-room Deck ")
        assertEquals("Living-room Deck", store.get(host()))
    }

    @Test fun aBlankNameClearsTheAliasSoTheEndpointShowsAgain() {
        store.set(host(), "Deck")
        store.set(host(), "   ")
        assertNull(store.get(host()))
        store.set(host(), "Deck")
        store.set(host(), null)
        assertNull(store.get(host()))
    }

    @Test fun theLimitsTheRenameDialogEnforcesAreTheOnesTheStoreEnforces() {
        // Typing can never produce what the store refuses: 64 characters, no control characters.
        store.set(host(), "x".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { store.set(host(), "x".repeat(65)) }
        assertThrows(IllegalArgumentException::class.java) { store.set(host(), "bad\nname") }
        assertEquals("x".repeat(64), store.get(host()))
    }

    @Test fun eachPairingHasItsOwnNameAndForgettingOneKeepsTheOther() {
        store.set(host(), "Deck")
        store.set(host(hostId = "host-other0001xxxxxxxxxxxxxxx"), "Desktop")
        store.set(host(clientId = "client-other"), "Same host, other client")
        assertEquals("Deck", store.get(host()))
        assertEquals("Desktop", store.get(host(hostId = "host-other0001xxxxxxxxxxxxxxx")))
        store.forget(host())
        assertNull(store.get(host()))
        assertEquals("Desktop", store.get(host(hostId = "host-other0001xxxxxxxxxxxxxxx")))
        assertEquals("Same host, other client", store.get(host(clientId = "client-other")))
    }

    @Test fun theStoredKeyHoldsNoIdentifierInTheClear() {
        store.set(host(), "Deck")
        val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("device_aliases", Context.MODE_PRIVATE)
        assertEquals(1, preferences.all.size)
        assertEquals(false, preferences.all.keys.single().contains("host-fixture"))
    }
}
