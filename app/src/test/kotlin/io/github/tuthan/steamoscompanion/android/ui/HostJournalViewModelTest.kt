package io.github.tuthan.steamoscompanion.android.ui

import android.os.Looper
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.HostJournalGateway
import io.github.tuthan.steamoscompanion.core.client.HostJournalState
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.HostJournal
import io.github.tuthan.steamoscompanion.core.protocol.HostJournalRecord
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The in-memory holder behind the Host journal section: it shows "loading" for the pairing it asked about,
 * keeps the records only for that pairing, drops them (and any read in flight) when the pairing changes,
 * and never lets a late result for an old pairing reappear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostJournalViewModelTest {
    private val models = mutableListOf<HostJournalViewModel>()

    @After fun clearModels() = models.forEach { model ->
        // onCleared is protected; clear() is the supported drop, and the loader's worker is a daemon thread.
        model.clear()
    }

    private fun journal(id: String) = HostJournal(listOf(
        HostJournalRecord(id, "power.suspend", "succeeded", null, null, null, null, null, null, null, emptyMap())))

    private fun model(gateway: HostJournalGateway) =
        HostJournalViewModel(gateway, System::nanoTime, minSpacingMillis = 0).also { models += it }

    private fun awaitState(model: HostJournalViewModel, done: (HostJournalState?) -> Boolean): HostJournalState? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done(model.state)) return model.state
            Thread.sleep(10)
        }
        return model.state
    }

    @Test fun aRequestShowsLoadingForThatPairingThenTheRecords() {
        val release = CountDownLatch(1)
        val model = model { _, _ -> release.await(5, TimeUnit.SECONDS); journal("a") }
        assertNull(model.state)
        model.request(host())
        assertEquals(HostJournalState.Loading(StatusObservationSource.from(host())), model.state)
        release.countDown()
        val state = awaitState(model) { it is HostJournalState.Ready }
        assertEquals(HostJournalState.Ready(StatusObservationSource.from(host()), journal("a")), state)
    }

    @Test fun anUnavailableHostIsAStateNotAFailure() {
        val model = model { _, _ -> null }
        model.request(host())
        assertEquals(HostJournalState.Unavailable(StatusObservationSource.from(host())),
            awaitState(model) { it is HostJournalState.Unavailable })
    }

    @Test fun clearingDropsTheRecordsAndAnyReadInFlight() {
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val model = model { _, signal ->
            reads.incrementAndGet()
            release.await(5, TimeUnit.SECONDS)
            if (signal.isCancelled) null else journal("late")
        }
        model.request(host())
        awaitState(model) { reads.get() == 1 }
        model.clear()
        assertNull("cleared at once", model.state)
        release.countDown()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("a late result for the old pairing never reappears", model.state)
    }

    @Test fun aResultAlreadyPostedToTheMainThreadWhenThePairingChangesIsDropped() {
        val delivered = CountDownLatch(1)
        val model = model { _, _ -> journal("posted").also { delivered.countDown() } }
        model.request(host())
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        // Give the loader time to hand the result to the (paused) main looper, but do not let the looper run it yet.
        Thread.sleep(300)
        model.clear()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("the posted result for the old pairing must not repopulate the cleared state", model.state)
    }

    @Test fun recordsAreClearedWhenThePairingChangesEvenAfterTheyArrived() {
        val model = model { _, _ -> journal("kept") }
        model.request(host())
        assertTrue(awaitState(model) { it is HostJournalState.Ready } is HostJournalState.Ready)
        model.clear()
        assertNull(model.state)
    }

    @Test fun aResultForAnEarlierPairingNeverReplacesTheCurrentOne() {
        val first = CountDownLatch(1)
        val model = model { asked, signal ->
            if (asked.bearerToken.contains("-0002-")) {
                first.await(5, TimeUnit.SECONDS)
                // Even an implementation that ignored the cancellation signal must not leak into the new pairing.
                journal("OLD")
            } else journal("NEW")
        }
        val old = host()
        val next = host(token = "fixture-token-0003-xxxxxxxxxxxxxxxxxxxxxxxx")
        model.request(old)
        Thread.sleep(100)
        model.request(next)
        first.countDown()
        val state = awaitState(model) { it is HostJournalState.Ready }
        assertEquals(HostJournalState.Ready(StatusObservationSource.from(next), journal("NEW")), state)
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HostJournalState.Ready(StatusObservationSource.from(next), journal("NEW")), model.state)
    }

    @Test fun theGatewayIsTheOnlyThingAskedAndOnlyWhenRequested() {
        val reads = AtomicInteger()
        val model = model { _, _ -> reads.incrementAndGet(); journal("a") }
        Thread.sleep(200)
        assertEquals("nothing is read until the screen asks", 0, reads.get())
        model.request(host())
        awaitState(model) { it is HostJournalState.Ready }
        Thread.sleep(300)
        assertEquals("and no timer asks again", 1, reads.get())
    }

    // --- the owner's export asks once more, without taking away what is held --------------------------------------------

    @Test fun anExportKeepsTheRecordsAlreadyHeldWhileItsOwnReadIsInFlightAndRunsItsHookAfterTheResult() {
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val model = model { _, _ ->
            if (reads.incrementAndGet() == 1) journal("first") else { release.await(5, TimeUnit.SECONDS); journal("second") }
        }
        model.request(host())
        awaitState(model) { it is HostJournalState.Ready }
        val source = StatusObservationSource.from(host())

        val seenByTheHook = mutableListOf<HostJournalState?>()
        val hooks = AtomicInteger()
        model.requestForExport(host()) { hooks.incrementAndGet(); seenByTheHook += model.state }
        awaitState(model) { reads.get() == 2 }
        assertEquals("the held records stay while the export's read runs", HostJournalState.Ready(source, journal("first")), model.state)
        assertEquals(0, hooks.get())

        release.countDown()
        awaitState(model) { it == HostJournalState.Ready(source, journal("second")) }
        assertEquals(1, hooks.get())
        assertEquals("the hook sees the result already applied", listOf<HostJournalState?>(HostJournalState.Ready(source, journal("second"))), seenByTheHook)
        assertEquals(2, reads.get())
    }

    @Test fun anExportOnAScreenThatNeverLoadedStillGetsItsHookAndNeverShowsLoading() {
        val release = CountDownLatch(1)
        val model = model { _, _ -> release.await(5, TimeUnit.SECONDS); journal("a") }
        val hooks = AtomicInteger()
        model.requestForExport(host()) { hooks.incrementAndGet() }
        assertNull("nothing is shown as loading for an export", model.state)
        release.countDown()
        awaitState(model) { it is HostJournalState.Ready }
        assertEquals(1, hooks.get())
        // A later result does not run the hook again.
        model.request(host())
        awaitState(model) { it is HostJournalState.Ready }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, hooks.get())
    }

    @Test fun anUnavailableAnswerStillReleasesTheExportsHook() {
        val model = model { _, _ -> null }
        val hooks = AtomicInteger()
        model.requestForExport(host()) { hooks.incrementAndGet() }
        awaitState(model) { it is HostJournalState.Unavailable }
        assertEquals(1, hooks.get())
    }

    @Test fun clearingDropsAPendingExportHookAndAnExportAskedOnceReadsOnce() {
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val model = model { _, _ -> reads.incrementAndGet(); release.await(5, TimeUnit.SECONDS); journal("late") }
        val hooks = AtomicInteger()
        model.requestForExport(host()) { hooks.incrementAndGet() }
        awaitState(model) { reads.get() == 1 }
        model.clear()
        release.countDown()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, hooks.get())
        assertNull(model.state)
        assertEquals("no timer asks again", 1, reads.get())
    }

    @Test fun anExportHookLeftOverFromBeforeAClearNeverFiresForALaterRead() {
        val model = model { _, _ -> journal("a") }
        val stale = AtomicInteger()
        model.requestForExport(host()) { stale.incrementAndGet() }
        model.clear()
        // The pairing comes back and the screen asks again: that result is not the old export's.
        model.request(host())
        awaitState(model) { it is HostJournalState.Ready }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, stale.get())
    }

    private fun host(token: String = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx") = ActiveHost(
        hostId = "host-fixture0001xxxxxxxxxxxxx", clientId = "client-android", endpoint = "deck.local:18443",
        certificatePin = "sha256:" + "ab".repeat(32), bearerToken = token,
        grantedScopes = listOf("status.read"),
        wakeTarget = StoredWakeTarget(false, "", null, null, "No usable wake interface"), pairedAtMillis = 1_780_000_000_000,
    )
}
