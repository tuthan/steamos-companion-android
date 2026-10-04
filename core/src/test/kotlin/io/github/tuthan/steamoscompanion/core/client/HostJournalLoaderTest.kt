package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.HostJournal
import io.github.tuthan.steamoscompanion.core.protocol.HostJournalRecord
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cadence and failure policy of the host journal read: spaced, one read per pairing, latest pairing
 * wins, cancellation-safe, and incapable of failing anything. Waits are on latches and queues with generous
 * timeouts; the only timing assertions are lower bounds.
 */
class HostJournalLoaderTest {
    private val host = testActiveHost()
    private val other = testActiveHost(token = "fixture-token-0003-xxxxxxxxxxxxxxxxxxxxxxxx")
    private val loaders = mutableListOf<HostJournalLoader>()

    @After fun closeLoaders() = loaders.forEach(HostJournalLoader::close)

    private fun journal(id: String) = HostJournal(listOf(
        HostJournalRecord(id, "power.suspend", "succeeded", null, null, null, null, null, null, null, emptyMap())))

    private class Recorder(private val behaviour: (Int, ActiveHost, CancellationSignal) -> HostJournal?) : HostJournalGateway {
        val calls = AtomicInteger()
        val startedAtNanos = CopyOnWriteArrayList<Long>()
        val signals = CopyOnWriteArrayList<CancellationSignal>()
        val hosts = CopyOnWriteArrayList<ActiveHost>()
        val entered = LinkedBlockingQueue<Int>()

        override fun read(host: ActiveHost, cancellation: CancellationSignal): HostJournal? {
            val number = calls.incrementAndGet()
            startedAtNanos += System.nanoTime()
            signals += cancellation
            hosts += host
            entered.put(number)
            return behaviour(number, host, cancellation)
        }
    }

    private fun loader(
        gateway: HostJournalGateway,
        spacingMillis: Long = 0,
        deliver: (HostJournalState) -> Unit,
    ) = HostJournalLoader(gateway, System::nanoTime, spacingMillis, deliver).also { loaders += it }

    private fun collecting(): Pair<LinkedBlockingQueue<HostJournalState>, (HostJournalState) -> Unit> {
        val queue = LinkedBlockingQueue<HostJournalState>()
        return queue to { state -> queue.put(state) }
    }

    private fun LinkedBlockingQueue<HostJournalState>.next(): HostJournalState =
        checkNotNull(poll(5, TimeUnit.SECONDS)) { "no state was delivered" }

    // --- delivery --------------------------------------------------------------------------------------------

    @Test fun aReadIsDeliveredAsReadyForThePairingItWasAskedFor() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { _, _, _ -> journal("a") }
        loader(gateway, deliver = deliver).request(host)
        val state = queue.next()
        assertEquals(HostJournalState.Ready(StatusObservationSource.from(host), journal("a")), state)
        assertEquals(1, gateway.calls.get())
        assertEquals("the host handed to the gateway is the requested one", host, gateway.hosts.single())
    }

    @Test fun noJournalAndEvenAThrowingGatewayAreJustUnavailableAndTheLoaderKeepsWorking() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { number, _, _ ->
            when (number) {
                1 -> null
                2 -> throw IllegalStateException("simulated gateway bug")
                3 -> throw java.io.IOException("simulated transport failure")
                else -> journal("d")
            }
        }
        val loader = loader(gateway, deliver = deliver)
        repeat(3) {
            loader.request(host)
            assertEquals(HostJournalState.Unavailable(StatusObservationSource.from(host)), queue.next())
        }
        loader.request(host)
        assertEquals(HostJournalState.Ready(StatusObservationSource.from(host), journal("d")), queue.next())
    }

    @Test fun aFailingListenerNeverBreaksTheLoader() {
        val delivered = LinkedBlockingQueue<HostJournalState>()
        val gateway = Recorder { _, _, _ -> journal("a") }
        val loader = loader(gateway) { state ->
            delivered.put(state)
            if (delivered.size == 1) throw IllegalStateException("listener failed")
        }
        loader.request(host)
        assertTrue(delivered.poll(5, TimeUnit.SECONDS) is HostJournalState.Ready)
        loader.request(host)
        assertTrue(delivered.poll(5, TimeUnit.SECONDS) is HostJournalState.Ready)
    }

    @Test fun theLoaderNeverDeliversALoadingState() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { number, _, _ -> if (number % 2 == 0) null else journal("a") }
        val loader = loader(gateway, deliver = deliver)
        repeat(4) {
            loader.request(host)
            assertFalse(queue.next() is HostJournalState.Loading)
        }
    }

    // --- one read per pairing, latest pairing wins -----------------------------------------------------------

    @Test fun theSamePairingWhileItsReadIsPendingOrRunningIsIgnored() {
        val (queue, deliver) = collecting()
        val release = CountDownLatch(1)
        val gateway = Recorder { _, _, _ -> release.await(5, TimeUnit.SECONDS); journal("a") }
        val loader = loader(gateway, deliver = deliver)
        loader.request(host)
        assertEquals(1, gateway.entered.poll(5, TimeUnit.SECONDS))
        repeat(5) { loader.request(host) }
        release.countDown()
        queue.next()
        assertNull("no second delivery", queue.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(1, gateway.calls.get())
    }

    @Test fun repeatedRequestsDuringTheSpacingWaitAreOneRead() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { _, _, _ -> journal("a") }
        val loader = loader(gateway, spacingMillis = 600, deliver = deliver)
        loader.request(host)
        queue.next()
        // A burst of Refresh presses right after a read waits out the spacing and costs one request.
        repeat(20) { loader.request(host) }
        queue.next()
        assertNull(queue.poll(800, TimeUnit.MILLISECONDS))
        assertEquals(2, gateway.calls.get())
    }

    @Test fun aRequestForAnotherPairingCancelsTheOlderReadAndOnlyTheNewerIsDelivered() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { number, _, signal ->
            if (number == 1) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!signal.isCancelled && System.nanoTime() < deadline) Thread.sleep(2)
                journal("OLD")
            } else journal("NEW")
        }
        val loader = loader(gateway, deliver = deliver)
        loader.request(host)
        assertEquals(1, gateway.entered.poll(5, TimeUnit.SECONDS))
        loader.request(other)
        val state = queue.next()
        assertEquals(HostJournalState.Ready(StatusObservationSource.from(other), journal("NEW")), state)
        assertTrue("the superseded read was told to stop", gateway.signals[0].isCancelled)
        assertNull("the cancelled read is never delivered", queue.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(2, gateway.calls.get())
    }

    // --- spacing: never an extra request per status poll ------------------------------------------------------

    @Test fun readsAreSpacedAtLeastTheMinimumApart() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { _, _, _ -> journal("a") }
        val loader = loader(gateway, spacingMillis = 400, deliver = deliver)
        loader.request(host)
        queue.next()
        loader.request(host)
        queue.next()
        val gapMillis = TimeUnit.NANOSECONDS.toMillis(gateway.startedAtNanos[1] - gateway.startedAtNanos[0])
        assertTrue("reads were only $gapMillis ms apart", gapMillis >= 300)
    }

    @Test fun theFirstReadIsNotDelayedAndTheDefaultSpacingIsFiveSeconds() {
        assertEquals(5_000L, HostJournalLoader.MIN_SPACING_MILLIS)
        val (queue, deliver) = collecting()
        val gateway = Recorder { _, _, _ -> journal("a") }
        val started = System.nanoTime()
        HostJournalLoader(gateway, System::nanoTime, deliver = deliver).also { loaders += it }.request(host)
        queue.next()
        val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("the first read waited $millis ms", millis < 3_000)
    }

    @Test fun spacingFollowsTheInjectedClockNotTheWallClock() {
        // A fake monotonic clock that never advances: the second read must wait the full spacing.
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000_000L)
        val (queue, deliver) = collecting()
        val gateway = Recorder { _, _, _ -> journal("a") }
        val loader = HostJournalLoader(gateway, now::get, 700, deliver).also { loaders += it }
        loader.request(host)
        queue.next()
        val before = System.nanoTime()
        loader.request(host)
        queue.next()
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
        assertTrue("second read only waited $waited ms", waited >= 600)
        // Once the clock has moved past the spacing, the next read is immediate.
        now.addAndGet(TimeUnit.SECONDS.toNanos(10))
        val again = System.nanoTime()
        loader.request(host)
        queue.next()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - again) < 500)
    }

    // --- shutdown --------------------------------------------------------------------------------------------

    @Test fun cancelDropsTheReadInFlightWithoutDeliveringIt() {
        val (queue, deliver) = collecting()
        val release = CountDownLatch(1)
        val gateway = Recorder { _, _, _ -> release.await(5, TimeUnit.SECONDS); journal("a") }
        val loader = loader(gateway, deliver = deliver)
        loader.request(host)
        assertEquals(1, gateway.entered.poll(5, TimeUnit.SECONDS))
        loader.cancel()
        release.countDown()
        assertNull(queue.poll(400, TimeUnit.MILLISECONDS))
        assertTrue(gateway.signals.single().isCancelled)
        // The loader still serves the next request.
        loader.request(host)
        assertTrue(queue.next() is HostJournalState.Ready)
    }

    @Test fun cancelDropsARequestStillWaitingForItsSpacing() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { _, _, _ -> journal("a") }
        val loader = loader(gateway, spacingMillis = 500, deliver = deliver)
        loader.request(host)
        queue.next()
        loader.request(host)
        loader.cancel()
        assertNull(queue.poll(900, TimeUnit.MILLISECONDS))
        assertEquals("the waiting request never became a read", 1, gateway.calls.get())
    }

    @Test fun closeStopsEverythingAndLaterRequestsAreIgnored() {
        val (queue, deliver) = collecting()
        val release = CountDownLatch(1)
        val gateway = Recorder { _, _, _ -> release.await(5, TimeUnit.SECONDS); journal("a") }
        val loader = loader(gateway, deliver = deliver)
        loader.request(host)
        assertEquals(1, gateway.entered.poll(5, TimeUnit.SECONDS))
        loader.close()
        release.countDown()
        loader.request(other)
        assertNull(queue.poll(400, TimeUnit.MILLISECONDS))
        assertEquals(1, gateway.calls.get())
        assertTrue(gateway.signals.single().isCancelled)
    }

    // --- provenance ------------------------------------------------------------------------------------------

    @Test fun aStateIsOnlyServedForItsOwnPairing() {
        val state = HostJournalState.Ready(StatusObservationSource.from(host), journal("a"))
        assertEquals(state, state.forHost(host))
        assertNull("another credential", state.forHost(other))
        assertNull("another host", state.forHost(testActiveHost(hostId = "host-fixture0002xxxxxxxxxxxxx")))
        assertNull("another endpoint", state.forHost(testActiveHost(endpoint = "other.local:18443")))
        // Learning a new boot ID is not a new pairing.
        assertEquals(state, state.forHost(host.copy(lastObservedBootId = "boot-2")))
        assertNull(HostJournalState.Unavailable(StatusObservationSource.from(host)).forHost(other))
        assertNull(HostJournalState.Loading(StatusObservationSource.from(host)).forHost(other))
    }

}
