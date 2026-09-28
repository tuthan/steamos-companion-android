package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.SteamBridgeState
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPollerTest {
    private val host = ActiveHost(
        "host-1", "client-1", "192.0.2.5:18443", "sha256:" + "a".repeat(64), "token",
        listOf("status.read"), StoredWakeTarget(false, null, null, null, null), 0,
    )
    private val status = VersionedPayload.Status(
        "host-1", "boot-1", WireValue.Known(SteamBridgeState.READY), emptyMap(),
        WireValue.Known(SunshineState.UNKNOWN), JsonValue.ObjectValue(emptyMap()),
    )

    @Test fun manualRefreshCoalescesAndStopCancelsForegroundWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val second = CountDownLatch(1)
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val poller = StatusPoller(host, PairedStatusGateway { _, _ ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, now) }
            try {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                } else second.countDown()
                PairedStatusRead(status, null, null)
            } finally { active.decrementAndGet() }
        }, System::nanoTime) { }
        try {
            poller.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(5) { poller.refresh() }
            release.countDown()
            assertTrue(second.await(2, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            assertEquals(1, maximum.get())
            poller.stop()
            poller.refresh()
            // Wait beyond the connected cadence to prove ON_STOP removed the scheduled cycle.
            Thread.sleep(StatusPoller.CONNECTED_DELAY_MS + 250)
            assertEquals(2, calls.get())
        } finally { poller.close() }
    }

    @Test fun tickingClockMarksStaleAndUnreachableWinsHeadline() {
        val now = AtomicLong(1_000_000_000L)
        val first = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val calls = AtomicInteger()
        val poller = StatusPoller(host, PairedStatusGateway { _, _ ->
            if (calls.incrementAndGet() == 1) PairedStatusRead(status, null, null)
            else throw IllegalStateException("offline")
        }, now::get) { state ->
            if (state.headline == StatusHeadline.CONNECTED) first.countDown()
            if (state.unreachable) failed.countDown()
        }
        try {
            poller.start()
            assertTrue(first.await(2, TimeUnit.SECONDS))
            assertEquals(StatusObservationSource.from(host), poller.state.source)
            now.addAndGet(StatusPoller.STALE_NANOS + 1)
            assertTrue(await(2_000) { poller.state.stale })
            poller.refresh()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(poller.state.stale)
            assertEquals(StatusHeadline.UNREACHABLE, poller.state.headline)
            poller.stop()
            assertFalse(poller.state.reading)
        } finally { poller.close() }
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
