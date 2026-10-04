package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.LinkConnector
import io.github.tuthan.steamoscompanion.core.protocol.LinkFacts
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cadence and failure policy of the advisory read: one read per request key, spaced, latest wins,
 * cancellation-safe, and incapable of failing anything. Waits are on latches and queues with generous
 * timeouts; the only timing assertions are lower bounds.
 */
class LinkFactsLoaderTest {
    private val host = testActiveHost()
    private val loaders = mutableListOf<LinkFactsLoader>()

    @After fun closeLoaders() = loaders.forEach(LinkFactsLoader::close)

    private fun facts(name: String) = LinkFacts(true, null, null, listOf(
        LinkConnector(name, "drm:card0", name, null, null, false, true, listOf("1920x1080"), "1")))

    private class Recorder(private val behaviour: (Int, ActiveHost, CancellationSignal) -> LinkFacts?) : LinkFactsGateway {
        val calls = AtomicInteger()
        val startedAtNanos = CopyOnWriteArrayList<Long>()
        val signals = CopyOnWriteArrayList<CancellationSignal>()
        val entered = LinkedBlockingQueue<Int>()

        override fun read(host: ActiveHost, cancellation: CancellationSignal): LinkFacts? {
            val number = calls.incrementAndGet()
            startedAtNanos += System.nanoTime()
            signals += cancellation
            entered.put(number)
            return behaviour(number, host, cancellation)
        }
    }

    private fun loader(
        gateway: LinkFactsGateway,
        spacingMillis: Long = 0,
        deliver: (LinkFactsReading) -> Unit,
    ) = LinkFactsLoader(gateway, System::nanoTime, spacingMillis, deliver).also { loaders += it }

    private fun collecting(): Pair<LinkedBlockingQueue<LinkFactsReading>, (LinkFactsReading) -> Unit> {
        val queue = LinkedBlockingQueue<LinkFactsReading>()
        return queue to { reading -> queue.put(reading) }
    }

    private fun LinkedBlockingQueue<LinkFactsReading>.next(): LinkFactsReading =
        checkNotNull(poll(5, TimeUnit.SECONDS)) { "no reading was delivered" }

    // --- delivery --------------------------------------------------------------------------------------------

    @Test fun aReadIsDeliveredTiedToThePairingAndTheTopologyItWasAskedFor() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { _, _, _ -> facts("HDMI-A-1") }
        loader(gateway, deliver = deliver).request(host, "k1")
        val reading = queue.next()
        assertEquals(StatusObservationSource.from(host), reading.source)
        assertEquals("k1", reading.topology)
        assertEquals(facts("HDMI-A-1"), reading.facts)
        assertEquals(1, gateway.calls.get())
    }

    @Test fun noFactsAndEvenAThrowingGatewayAreJustNoFactsAndTheLoaderKeepsWorking() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { number, _, _ ->
            when (number) {
                1 -> null
                2 -> throw IllegalStateException("simulated gateway bug")
                3 -> throw java.io.IOException("simulated transport failure")
                else -> facts("DP-1")
            }
        }
        val loader = loader(gateway, deliver = deliver)
        listOf("a", "b", "c").forEach { key ->
            loader.request(host, key)
            val reading = queue.next()
            assertEquals(key, reading.topology)
            assertNull(key, reading.facts)
        }
        loader.request(host, "d")
        assertEquals(facts("DP-1"), queue.next().facts)
    }

    @Test fun aFailingListenerNeverBreaksTheLoader() {
        val delivered = LinkedBlockingQueue<String>()
        val gateway = Recorder { _, _, _ -> facts("HDMI-A-1") }
        val loader = loader(gateway) { reading ->
            delivered.put(reading.topology)
            if (reading.topology == "a") throw IllegalStateException("listener failed")
        }
        loader.request(host, "a")
        assertEquals("a", delivered.poll(5, TimeUnit.SECONDS))
        loader.request(host, "b")
        assertEquals("b", delivered.poll(5, TimeUnit.SECONDS))
    }

    // --- one read per key, latest wins ----------------------------------------------------------------------

    @Test fun theSameKeyWhileItsReadIsPendingIsIgnored() {
        val (queue, deliver) = collecting()
        val release = CountDownLatch(1)
        val gateway = Recorder { _, _, _ -> release.await(5, TimeUnit.SECONDS); facts("HDMI-A-1") }
        val loader = loader(gateway, deliver = deliver)
        loader.request(host, "k")
        assertEquals(1, gateway.entered.poll(5, TimeUnit.SECONDS))
        repeat(5) { loader.request(host, "k") }
        release.countDown()
        queue.next()
        assertNull("no second delivery", queue.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(1, gateway.calls.get())
    }

    @Test fun aNewerRequestCancelsTheOlderReadAndOnlyTheNewerIsDelivered() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { number, _, signal ->
            if (number == 1) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!signal.isCancelled && System.nanoTime() < deadline) Thread.sleep(2)
                facts("OLD")
            } else facts("NEW")
        }
        val loader = loader(gateway, deliver = deliver)
        loader.request(host, "old")
        assertEquals(1, gateway.entered.poll(5, TimeUnit.SECONDS))
        loader.request(host, "new")
        val reading = queue.next()
        assertEquals("new", reading.topology)
        assertEquals(facts("NEW"), reading.facts)
        assertTrue("the superseded read was told to stop", gateway.signals[0].isCancelled)
        assertNull("the cancelled read is never delivered", queue.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(2, gateway.calls.get())
    }

    @Test fun anotherPairingIsAnotherKeyEvenWithTheSameTopology() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { _, _, _ -> facts("HDMI-A-1") }
        val loader = loader(gateway, deliver = deliver)
        val other = testActiveHost(token = "fixture-token-0003-xxxxxxxxxxxxxxxxxxxxxxxx")
        loader.request(host, "k")
        val first = queue.next()
        loader.request(other, "k")
        val second = queue.next()
        assertEquals(StatusObservationSource.from(host), first.source)
        assertEquals(StatusObservationSource.from(other), second.source)
        assertEquals(2, gateway.calls.get())
    }

    // --- spacing: never an extra request per status poll ------------------------------------------------------

    @Test fun readsAreSpacedAtLeastTheMinimumApart() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { _, _, _ -> facts("HDMI-A-1") }
        val loader = loader(gateway, spacingMillis = 400, deliver = deliver)
        loader.request(host, "k1")
        assertEquals("k1", queue.next().topology)
        loader.request(host, "k2")
        assertEquals("k2", queue.next().topology)
        val gapMillis = TimeUnit.NANOSECONDS.toMillis(gateway.startedAtNanos[1] - gateway.startedAtNanos[0])
        assertTrue("reads were only $gapMillis ms apart", gapMillis >= 300)
    }

    @Test fun aFlappingTopologyIsCoalescedToTheLatestKeyInsteadOfOneReadPerChange() {
        val (queue, deliver) = collecting()
        val gateway = Recorder { _, _, _ -> facts("HDMI-A-1") }
        val loader = loader(gateway, spacingMillis = 500, deliver = deliver)
        loader.request(host, "k1")
        assertEquals("k1", queue.next().topology)
        // The topology changes on every status poll for a while; none of those becomes its own request.
        (2..50).forEach { loader.request(host, "k$it") }
        val reading = queue.next()
        assertEquals("k50", reading.topology)
        assertNull(queue.poll(700, TimeUnit.MILLISECONDS))
        assertEquals("one read for the first key and one for the last", 2, gateway.calls.get())
    }

    // --- shutdown --------------------------------------------------------------------------------------------

    @Test fun cancelDropsTheReadInFlightWithoutDeliveringIt() {
        val (queue, deliver) = collecting()
        val release = CountDownLatch(1)
        val gateway = Recorder { _, _, _ -> release.await(5, TimeUnit.SECONDS); facts("HDMI-A-1") }
        val loader = loader(gateway, deliver = deliver)
        loader.request(host, "k")
        assertEquals(1, gateway.entered.poll(5, TimeUnit.SECONDS))
        loader.cancel()
        release.countDown()
        assertNull(queue.poll(400, TimeUnit.MILLISECONDS))
        assertTrue(gateway.signals.single().isCancelled)
        // The loader still serves the next request.
        loader.request(host, "k2")
        assertEquals("k2", queue.next().topology)
    }

    @Test fun closeStopsEverythingAndLaterRequestsAreIgnored() {
        val (queue, deliver) = collecting()
        val release = CountDownLatch(1)
        val gateway = Recorder { _, _, _ -> release.await(5, TimeUnit.SECONDS); facts("HDMI-A-1") }
        val loader = loader(gateway, deliver = deliver)
        loader.request(host, "k")
        assertEquals(1, gateway.entered.poll(5, TimeUnit.SECONDS))
        loader.close()
        release.countDown()
        loader.request(host, "after-close")
        assertNull(queue.poll(400, TimeUnit.MILLISECONDS))
        assertEquals(1, gateway.calls.get())
        assertTrue(gateway.signals.single().isCancelled)
    }

    // --- provenance ------------------------------------------------------------------------------------------

    @Test fun aReadingIsOnlyServedForItsOwnPairingAndTopology() {
        val reading = LinkFactsReading(StatusObservationSource.from(host), "k1", facts("HDMI-A-1"))
        assertNotNull(reading.factsFor(host, "k1"))
        assertNull("another topology", reading.factsFor(host, "k2"))
        assertNull("no topology", reading.factsFor(host, null))
        assertNull("another credential", reading.factsFor(testActiveHost(token = "fixture-token-0003-xxxxxxxxxxxxxxxxxxxxxxxx"), "k1"))
        assertNull("another host", reading.factsFor(testActiveHost(hostId = "host-fixture0002xxxxxxxxxxxxx"), "k1"))
        assertNull("another endpoint", reading.factsFor(testActiveHost(endpoint = "other.local:18443"), "k1"))
        // Learning a new boot ID is not a new pairing.
        assertNotNull(reading.factsFor(host.copy(lastObservedBootId = "boot-2"), "k1"))
        assertNull("a reading with no facts serves none", LinkFactsReading(StatusObservationSource.from(host), "k1", null).factsFor(host, "k1"))
    }
}
