package io.github.tuthan.steamoscompanion.android.ui

import android.os.Looper
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.LinkFactsGateway
import io.github.tuthan.steamoscompanion.core.client.LinkFactsReading
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.LinkFacts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The in-memory holder behind the Display screen's advisory, and what the owner's export asks of it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkFactsViewModelTest {
    private val models = mutableListOf<LinkFactsViewModel>()

    @After fun clearModels() = models.forEach { it.clear() }

    private fun facts(reason: String) = LinkFacts(true, null, reason, emptyList())

    private fun model(gateway: LinkFactsGateway) =
        LinkFactsViewModel(gateway, System::nanoTime, minSpacingMillis = 0).also { models += it }

    private fun await(model: LinkFactsViewModel, done: (LinkFactsReading?) -> Boolean): LinkFactsReading? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done(model.reading)) return model.reading
            Thread.sleep(10)
        }
        return model.reading
    }

    @Test fun aRequestDeliversTheReadingForThatPairingAndTopology() {
        val model = model { _, _ -> facts("a") }
        assertNull(model.reading)
        model.request(host(), "t1")
        assertEquals(LinkFactsReading(StatusObservationSource.from(host()), "t1", facts("a")), await(model) { it != null })
    }

    @Test fun anExportKeepsTheReadingAlreadyHeldWhileItsOwnReadIsInFlightAndRunsItsHookAfterTheResult() {
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val model = model { _, _ -> if (reads.incrementAndGet() == 1) facts("first") else { release.await(5, TimeUnit.SECONDS); facts("second") } }
        model.request(host(), "t1")
        await(model) { it != null }
        val source = StatusObservationSource.from(host())

        val seenByTheHook = mutableListOf<LinkFactsReading?>()
        val hooks = AtomicInteger()
        model.requestForExport(host(), "t1") { hooks.incrementAndGet(); seenByTheHook += model.reading }
        await(model) { reads.get() == 2 }
        assertEquals("the held reading stays while the export's read runs", LinkFactsReading(source, "t1", facts("first")), model.reading)
        assertEquals(0, hooks.get())

        release.countDown()
        await(model) { it?.facts == facts("second") }
        assertEquals(1, hooks.get())
        assertEquals("the hook sees the reading already applied", listOf<LinkFactsReading?>(LinkFactsReading(source, "t1", facts("second"))), seenByTheHook)
    }

    @Test fun theExportsHookRunsOnceAndNotForEveryLaterReading() {
        val reads = AtomicInteger()
        val model = model { _, _ -> facts("read ${reads.incrementAndGet()}") }
        val hooks = AtomicInteger()
        model.requestForExport(host(), "t1") { hooks.incrementAndGet() }
        await(model) { it != null }
        assertEquals(1, hooks.get())
        model.request(host(), "t2")
        await(model) { it?.topology == "t2" }
        model.request(host(), "t3")
        await(model) { it?.topology == "t3" }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("later readings are not the export's", 1, hooks.get())
    }

    @Test fun anExportHookLeftOverFromBeforeAClearNeverFiresForALaterReading() {
        val model = model { _, _ -> facts("a") }
        val stale = AtomicInteger()
        model.requestForExport(host(), "t1") { stale.incrementAndGet() }
        model.clear()
        model.request(host(), "t2")
        await(model) { it?.topology == "t2" }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, stale.get())
    }

    @Test fun anAnswerOfNoFactsStillReleasesTheExportsHook() {
        val model = model { _, _ -> null }
        val hooks = AtomicInteger()
        model.requestForExport(host(), "t1") { hooks.incrementAndGet() }
        await(model) { it != null }
        assertEquals(LinkFactsReading(StatusObservationSource.from(host()), "t1", null), model.reading)
        assertEquals(1, hooks.get())
    }

    @Test fun clearingDropsAPendingExportHookAndNothingReadsAgainOnItsOwn() {
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val model = model { _, _ -> reads.incrementAndGet(); release.await(5, TimeUnit.SECONDS); facts("late") }
        val hooks = AtomicInteger()
        model.requestForExport(host(), "t1") { hooks.incrementAndGet() }
        await(model) { reads.get() == 1 }
        model.clear()
        release.countDown()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, hooks.get())
        assertNull(model.reading)
        assertEquals(1, reads.get())
    }

    private fun host() = ActiveHost(
        hostId = "host-fixture0001xxxxxxxxxxxxx", clientId = "client-android", endpoint = "deck.local:18443",
        certificatePin = "sha256:" + "ab".repeat(32), bearerToken = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
        grantedScopes = listOf("status.read"),
        wakeTarget = StoredWakeTarget(false, "", null, null, "No usable wake interface"), pairedAtMillis = 1_780_000_000_000,
    )
}
