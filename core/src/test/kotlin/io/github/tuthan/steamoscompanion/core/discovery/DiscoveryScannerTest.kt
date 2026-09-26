package io.github.tuthan.steamoscompanion.core.discovery

import io.github.tuthan.steamoscompanion.core.ports.Ipv4Subnet
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.DiscoveryProbeResult
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryScannerTest {
    @Test
    fun targetBuilderUsesSelectedIpv4SubnetsSkipsSelfAndBoundsSubnetAndUniqueAddressWork() {
        val wifi = path("wifi", "192.168.1.5", 24)
        val targets = DiscoveryScanner.buildTargets(listOf(wifi))
        assertEquals(253, targets.targets.size)
        assertEquals("192.168.1.1", targets.targets.first().endpoint.host)
        assertEquals("192.168.1.254", targets.targets.last().endpoint.host)
        assertTrue(targets.targets.none { it.endpoint.host == "192.168.1.5" })
        assertFalse(targets.truncated)

        val duplicateSubnet = path("ethernet", "192.168.1.10", 24)
        val deduplicated = DiscoveryScanner.buildTargets(listOf(wifi, duplicateSubnet))
        assertEquals(252, deduplicated.targets.size)
        assertEquals("wifi", deduplicated.targets.first().network.id)

        val tooLarge = path("large", "10.8.0.4", 21)
        val skipped = DiscoveryScanner.buildTargets(listOf(tooLarge))
        assertTrue(skipped.targets.isEmpty())
        assertEquals(listOf("10.8.0.0/21"), skipped.skippedSubnets)
    }

    @Test
    fun targetBuilderHonorsGlobal1024AddressLimitAndPointToPointSubnets() {
        val many = (1..6).map { index -> path("lan-$index", "10.$index.0.5", 24) }
        val limited = DiscoveryScanner.buildTargets(many)
        assertEquals(DiscoveryScanner.MAX_SCAN_ADDRESSES, limited.targets.size)
        assertTrue(limited.truncated)
        assertEquals(limited.targets.size, limited.targets.map { it.endpoint.host }.distinct().size)

        val pointToPoint = path("p2p", "192.0.2.10", 31)
        val p2pTargets = DiscoveryScanner.buildTargets(listOf(pointToPoint))
        assertEquals(1, p2pTargets.targets.size)
        assertEquals("192.0.2.11", p2pTargets.targets.single().endpoint.host)

        val single = path("single", "192.0.2.20", 32)
        assertTrue(DiscoveryScanner.buildTargets(listOf(single)).targets.isEmpty())
    }

    @Test
    fun scanNeverRunsMoreThanSixteenProbesAndCollectsPartialCandidateResults() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val firstBatch = CountDownLatch(DiscoveryScanner.MAX_CONCURRENT_PROBES)
        val probe = DiscoveryProbe { target, _ ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, now) }
            firstBatch.countDown()
            assertTrue(firstBatch.await(2, TimeUnit.SECONDS))
            active.decrementAndGet()
            val candidate = UnverifiedDiscoveryCandidate(
                target,
                "host-${target.endpoint.host.substringAfterLast('.')}",
                "SteamOS device",
                "sha256:" + "aa".repeat(32),
            )
            if (target.endpoint.host.endsWith(".1")) DiscoveryProbeResult.Candidate(candidate)
            else DiscoveryProbeResult.Failed(TransportFailure.CONNECT)
        }
        val scanner = DiscoveryScanner(networkAccess(path("wifi", "192.168.1.5", 24)), probe)
        try {
            val result = scanner.scan(budgetMillis = 5_000)
            assertEquals(16, maximum.get())
            assertEquals(253, result.addressesProbed)
            assertEquals(252, result.failedProbes)
            assertEquals(1, result.candidates.size)
            assertEquals("192.168.1.1", result.candidates.single().target.endpoint.host)
            assertFalse(result.partial)
        } finally {
            scanner.close()
        }
    }

    @Test
    fun scanDeadlineReturnsPartialResultsAndNetworkChangeDropsOldGeneration() {
        val secondRun = AtomicBoolean(false)
        val active = AtomicInteger()
        val secondStarted = CountDownLatch(1)
        val probe = DiscoveryProbe { _, cancellation ->
            active.incrementAndGet()
            try {
                if (secondRun.get()) secondStarted.countDown()
                while (!cancellation.isCancelled) Thread.sleep(5)
            } finally {
                active.decrementAndGet()
            }
            DiscoveryProbeResult.Failed(TransportFailure.CANCELLED)
        }
        val scanner = DiscoveryScanner(networkAccess(path("wifi", "192.168.1.5", 24)), probe)
        try {
            val timed = scanner.scan(budgetMillis = 200)
            assertTrue(timed.timedOut)
            assertTrue(timed.partial)
            assertTrue(timed.addressesProbed in 1..DiscoveryScanner.MAX_CONCURRENT_PROBES)
            val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (active.get() != 0 && System.nanoTime() < drainDeadline) Thread.sleep(5)
            assertEquals(0, active.get())

            val pool = Executors.newSingleThreadExecutor()
            try {
                secondRun.set(true)
                val future = pool.submit<DiscoveryScanResult> { scanner.scan(budgetMillis = 3_000) }
                assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
                scanner.networkChanged()
                val cancelled = future.get(2, TimeUnit.SECONDS)
                assertTrue(cancelled.cancelled)
                assertTrue(cancelled.candidates.isEmpty())
            } finally {
                pool.shutdownNow()
            }
        } finally {
            scanner.close()
        }
    }

    @Test
    fun manualProbeValidatesOriginAndSendsOnlyTheBoundedDiscoveryProbe() {
        var calls = 0
        lateinit var seen: TransportTarget
        val probe = DiscoveryProbe { target, _ ->
            calls++
            seen = target
            DiscoveryProbeResult.Candidate(
                UnverifiedDiscoveryCandidate(target, "host-manual", "Manual host", "sha256:" + "ab".repeat(32)),
            )
        }
        val path = path("wifi", "192.168.1.5", 24)
        val scanner = DiscoveryScanner(networkAccess(path), probe)
        try {
            assertTrue(scanner.probeManual("https://user@deck.local:18443/path", path) is ManualProbeResult.Failed)
            assertEquals(0, calls)
            val accepted = scanner.probeManual("deck.local:18443", path) as ManualProbeResult.Candidate
            assertEquals(1, calls)
            assertEquals("deck.local", seen.endpoint.host)
            assertEquals("wifi", seen.network.id)
            assertEquals(accepted.value.target.endpoint.authority, seen.endpoint.authority)
        } finally {
            scanner.close()
        }
    }

    @Test
    fun manualAddressProbeGetsFiveSecondsIndependentlyOfDiscoveryProbeBudget() {
        val path = path("wifi", "192.168.1.5", 24)
        var receivedBudget = 0L
        val probe = object : DiscoveryProbe {
            override fun probe(target: TransportTarget, cancellation: CancellationSignal) =
                DiscoveryProbeResult.Failed(TransportFailure.CONNECT)

            override fun probe(target: TransportTarget, cancellation: CancellationSignal, budgetMillis: Long): DiscoveryProbeResult {
                receivedBudget = budgetMillis
                return DiscoveryProbeResult.Failed(TransportFailure.CONNECT)
            }
        }
        val scanner = DiscoveryScanner(networkAccess(path), probe)
        try {
            assertTrue(scanner.probeManual("deck.local:18443", path) is ManualProbeResult.Failed)
            assertEquals(5_000L, receivedBudget)
        } finally {
            scanner.close()
        }
    }

    @Test
    fun api36CompatibilityScanUsesTheThreeSecondGlobalBudget() {
        val entered = CountDownLatch(1)
        val probe = DiscoveryProbe { _, cancellation ->
            entered.countDown()
            while (!cancellation.isCancelled) Thread.sleep(5)
            DiscoveryProbeResult.Failed(TransportFailure.CANCELLED)
        }
        val scanner = DiscoveryScanner(networkAccess(path("wifi", "192.168.1.5", 24)), probe)
        try {
            val startedAt = System.nanoTime()
            val result = scanner.scan(budgetMillis = DiscoveryScanner.API36_LEGACY_SCAN_BUDGET_MS)
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(result.timedOut)
            assertTrue(elapsedMillis in 2_500L..4_500L)
            assertTrue(result.addressesProbed in 1..DiscoveryScanner.MAX_CONCURRENT_PROBES)
        } finally {
            scanner.close()
        }
    }

    private fun path(id: String, local: String, prefix: Int): LanPath {
        val address = InetAddress.getByName(local) as Inet4Address
        return LanPath(
            id = id,
            interfaceName = "wlan0",
            addresses = listOf(address),
            broadcastAddresses = emptyList(),
            ipv4Subnets = listOf(Ipv4Subnet(address, prefix)),
        )
    }

    private fun networkAccess(path: LanPath) = object : NetworkAccess {
        override fun activeLanPaths(): List<LanPath> = listOf(path)
        override fun bind(socket: Socket, path: LanPath) = Unit
        override fun bind(socket: DatagramSocket, path: LanPath) = Unit
    }
}
