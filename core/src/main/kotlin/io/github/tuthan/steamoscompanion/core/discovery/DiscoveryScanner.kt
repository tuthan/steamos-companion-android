package io.github.tuthan.steamoscompanion.core.discovery

import io.github.tuthan.steamoscompanion.core.ports.Ipv4Subnet
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionState
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.DiscoveryProbeResult
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

fun interface DiscoveryProbe {
    fun probe(target: TransportTarget, cancellation: CancellationSignal): DiscoveryProbeResult

    fun probe(target: TransportTarget, cancellation: CancellationSignal, budgetMillis: Long): DiscoveryProbeResult =
        probe(target, cancellation)
}

class PinnedDiscoveryProbe(private val transport: PinnedHttpTransport) : DiscoveryProbe {
    override fun probe(target: TransportTarget, cancellation: CancellationSignal): DiscoveryProbeResult =
        transport.probeDiscovery(target, cancellation, DiscoveryScanner.DEFAULT_PROBE_BUDGET_MS)

    override fun probe(target: TransportTarget, cancellation: CancellationSignal, budgetMillis: Long): DiscoveryProbeResult =
        transport.probeDiscovery(target, cancellation, budgetMillis)
}

data class DiscoveryScanResult(
    val generation: Long,
    val candidates: List<UnverifiedDiscoveryCandidate>,
    val addressesProbed: Int,
    val skippedSubnets: List<String>,
    val truncated: Boolean,
    val cancelled: Boolean,
    val timedOut: Boolean,
    val failedProbes: Int,
    val localNetworkPermissionState: LocalNetworkPermissionState = LocalNetworkPermissionState.Allowed,
    val permissionStateAtStart: LocalNetworkPermissionState = localNetworkPermissionState,
) {
    val partial: Boolean get() = skippedSubnets.isNotEmpty() || truncated || cancelled || timedOut
    val qualifiedPermissionFailure: Boolean
        get() = candidates.isEmpty() &&
            (localNetworkPermissionState == LocalNetworkPermissionState.LegacyRestrictionUnknown ||
                permissionStateAtStart == LocalNetworkPermissionState.LegacyRestrictionUnknown)
}

sealed interface ManualProbeResult {
    data class Candidate(val value: UnverifiedDiscoveryCandidate) : ManualProbeResult
    data class Rejected(val status: Int, val error: String) : ManualProbeResult
    data class Failed(val reason: String) : ManualProbeResult
}

/**
 * Bounded IPv4 discovery over the selected Wi-Fi/Ethernet paths. Calls return partial results at
 * the scan deadline; callers should run them on a worker thread. Starting a new scan or reporting
 * a network change invalidates and closes the previous scan's in-flight probes.
 */
class DiscoveryScanner(
    private val networkAccess: NetworkAccess,
    private val probe: DiscoveryProbe,
    private val port: Int = DEFAULT_DISCOVERY_PORT,
) : Closeable {
    private val generation = AtomicLong()
    private val activeSignal = AtomicReference<CancellationSignal?>(null)
    private val runLock = Any()

    init {
        require(port in 1..65535)
    }

    fun scan(
        paths: List<LanPath> = networkAccess.activeLanPaths(),
        budgetMillis: Long = DEFAULT_SCAN_BUDGET_MS,
        externalCancellation: CancellationSignal? = null,
    ): DiscoveryScanResult {
        require(budgetMillis in 1..MAX_SCAN_BUDGET_MS)
        val permissionStateAtStart = networkAccess.localNetworkPermissionState()
        val cancellation = externalCancellation ?: CancellationSignal()
        val scanGeneration = beginRun(cancellation)
        if (permissionStateAtStart == LocalNetworkPermissionState.RequiredPermissionMissing) {
            return DiscoveryScanResult(
                generation = scanGeneration,
                candidates = emptyList(),
                addressesProbed = 0,
                skippedSubnets = emptyList(),
                truncated = false,
                cancelled = false,
                timedOut = false,
                failedProbes = 0,
                localNetworkPermissionState = networkAccess.localNetworkPermissionState(),
                permissionStateAtStart = permissionStateAtStart,
            )
        }
        val effectiveBudgetMillis = if (permissionStateAtStart == LocalNetworkPermissionState.LegacyRestrictionUnknown) {
            minOf(budgetMillis, API36_LEGACY_SCAN_BUDGET_MS)
        } else {
            budgetMillis
        }
        val (targets, skipped, truncated) = buildTargets(paths, port)
        val completion: CompletionService<ProbeResult> = ExecutorCompletionService(probeExecutor)
        val inFlight = mutableSetOf<Future<ProbeResult>>()
        val candidates = mutableListOf<UnverifiedDiscoveryCandidate>()
        var nextTarget = 0
        var completed = 0
        var failed = 0
        val start = System.nanoTime()
        val deadline = start + TimeUnit.MILLISECONDS.toNanos(effectiveBudgetMillis)
        var timedOut = false

        try {
            while (completed < targets.size || inFlight.isNotEmpty()) {
                while (!cancellation.isCancelled && scanGeneration == generation.get() &&
                    inFlight.size < MAX_CONCURRENT_PROBES && nextTarget < targets.size
                ) {
                    val target = targets[nextTarget++]
                    inFlight += completion.submit {
                        val result = if (cancellation.isCancelled || scanGeneration != generation.get()) null
                        else runCatching { probe.probe(target, cancellation) }.getOrNull()
                        ProbeResult(target, result)
                    }
                }
                if (inFlight.isEmpty()) break
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    timedOut = true
                    cancellation.cancel()
                    break
                }
                val done = completion.poll(remaining, TimeUnit.NANOSECONDS)
                if (done == null) {
                    timedOut = true
                    cancellation.cancel()
                    break
                }
                inFlight.remove(done)
                completed++
                when (val result = runCatching { done.get() }.getOrNull()?.result) {
                    is DiscoveryProbeResult.Candidate -> candidates += result.value
                    null, is DiscoveryProbeResult.Failed, is DiscoveryProbeResult.Rejected, is DiscoveryProbeResult.UnsupportedVersion -> failed++
                }
                if (scanGeneration != generation.get()) {
                    cancellation.cancel()
                    break
                }
            }
        } finally {
            if (timedOut || cancellation.isCancelled || scanGeneration != generation.get()) {
                cancellation.cancel()
            }
            inFlight.forEach { it.cancel(true) }
            if (activeSignal.get() === cancellation) activeSignal.compareAndSet(cancellation, null)
        }

        val stale = scanGeneration != generation.get()
        return DiscoveryScanResult(
            generation = scanGeneration,
            candidates = if (stale) emptyList() else candidates.distinctBy { Triple(it.hostId, it.certificateFingerprint, it.target.endpoint.authority) },
            addressesProbed = nextTarget,
            skippedSubnets = skipped,
            truncated = truncated,
            cancelled = stale || (cancellation.isCancelled && !timedOut),
            timedOut = timedOut,
            failedProbes = failed,
            localNetworkPermissionState = networkAccess.localNetworkPermissionState(),
            permissionStateAtStart = permissionStateAtStart,
        )
    }

    /** Manual entry is still an unauthenticated discovery probe and gets no credential or nonce. */
    fun probeManual(endpointText: String, path: LanPath, cancellation: CancellationSignal? = null): ManualProbeResult {
        val endpoint = try {
            HostEndpoint.parse(endpointText)
        } catch (error: IllegalArgumentException) {
            return ManualProbeResult.Failed(error.message ?: "Enter a valid host address")
        }
        val signal = cancellation ?: CancellationSignal()
        val runGeneration = beginRun(signal)
        if (networkAccess.localNetworkPermissionState() == LocalNetworkPermissionState.RequiredPermissionMissing) {
            if (activeSignal.get() === signal) activeSignal.compareAndSet(signal, null)
            return ManualProbeResult.Failed("Local network permission is required")
        }
        return try {
            val result = probe.probe(TransportTarget(endpoint, path), signal, MANUAL_PROBE_BUDGET_MS)
            if (runGeneration != generation.get()) return ManualProbeResult.Failed("cancelled")
            when (result) {
                is DiscoveryProbeResult.Candidate -> ManualProbeResult.Candidate(result.value)
                is DiscoveryProbeResult.Rejected -> ManualProbeResult.Rejected(result.status, result.error)
                is DiscoveryProbeResult.UnsupportedVersion -> ManualProbeResult.Failed("Host protocol ${result.version} is not supported")
                is DiscoveryProbeResult.Failed -> ManualProbeResult.Failed(result.failure.name.lowercase().replace('_', ' '))
            }
        } catch (_: Exception) {
            ManualProbeResult.Failed("connection failed")
        } finally {
            if (activeSignal.get() === signal) activeSignal.compareAndSet(signal, null)
        }
    }

    /** Called when Android reports a network change; late results from that generation are dropped. */
    fun networkChanged() {
        synchronized(runLock) {
            generation.incrementAndGet()
            activeSignal.getAndSet(null)?.cancel()
        }
    }

    fun cancelActiveScan() = networkChanged()

    override fun close() = cancelActiveScan()

    private fun beginRun(signal: CancellationSignal): Long = synchronized(runLock) {
        val nextGeneration = generation.incrementAndGet()
        activeSignal.getAndSet(signal)?.cancel()
        nextGeneration
    }

    companion object {
        const val DEFAULT_DISCOVERY_PORT = HostEndpoint.DEFAULT_PORT
        const val MAX_SCAN_ADDRESSES = 1_024
        const val MAX_SUBNET_ADDRESSES = 1_024
        const val MAX_CONCURRENT_PROBES = 16
        const val DEFAULT_SCAN_BUDGET_MS = 15_000L
        const val API36_LEGACY_SCAN_BUDGET_MS = 3_000L
        const val DEFAULT_PROBE_BUDGET_MS = 750L
        const val MANUAL_PROBE_BUDGET_MS = 5_000L
        const val MAX_SCAN_BUDGET_MS = DEFAULT_SCAN_BUDGET_MS

        /** Exposed for deterministic limit tests; output contains at most 1,024 unique non-self IPs. */
        fun buildTargets(paths: List<LanPath>, port: Int = DEFAULT_DISCOVERY_PORT): TargetSet {
            require(port in 1..65535)
            val selfAddresses = paths.flatMap { it.addresses }.mapTo(mutableSetOf()) { it.hostAddress }
            val seen = linkedSetOf<String>()
            val targets = mutableListOf<TransportTarget>()
            val skipped = mutableListOf<String>()
            var truncated = false
            for (path in paths) {
                for (subnet in path.ipv4Subnets) {
                    val shape = subnet.shapeOrNull() ?: continue
                    val label = "${shape.networkAddress}/${subnet.prefixLength}"
                    if (shape.totalAddresses > MAX_SUBNET_ADDRESSES) {
                        skipped += label
                        continue
                    }
                    for (address in shape.hostAddresses()) {
                        val rendered = address.hostAddress
                        if (rendered in selfAddresses) continue
                        if (!seen.add(rendered)) continue
                        if (targets.size >= MAX_SCAN_ADDRESSES) {
                            truncated = true
                            return TargetSet(targets, skipped, truncated)
                        }
                        targets += TransportTarget(HostEndpoint.parse("${rendered}:$port"), path)
                    }
                }
            }
            return TargetSet(targets, skipped, truncated)
        }

        private val probeExecutor = ThreadPoolExecutor(
            MAX_CONCURRENT_PROBES,
            MAX_CONCURRENT_PROBES,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(MAX_CONCURRENT_PROBES),
            DiscoveryThreadFactory(),
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }
}

data class TargetSet(val targets: List<TransportTarget>, val skippedSubnets: List<String>, val truncated: Boolean)

private data class ProbeResult(val target: TransportTarget, val result: DiscoveryProbeResult?)

private data class SubnetShape(
    val networkAddress: String,
    val totalAddresses: Long,
    private val network: Long,
    private val broadcast: Long,
    private val local: Long,
    private val prefix: Int,
) {
    fun hostAddresses(): Sequence<Inet4Address> = sequence {
        val start = if (prefix <= 30) network + 1 else network
        val end = if (prefix <= 30) broadcast - 1 else broadcast
        var current = start
        while (current <= end) {
            if (current != local) yield(current.toIpv4())
            current++
        }
    }
}

private fun Ipv4Subnet.shapeOrNull(): SubnetShape? {
    val address = address as? Inet4Address ?: return null
    if (prefixLength !in 0..32 || address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return null
    val local = address.address.toUnsignedLong()
    val hostBits = 32 - prefixLength
    val total = 1L shl hostBits
    val hostMask = total - 1
    val mask = 0xffff_ffffL xor hostMask
    val network = local and mask
    val broadcast = network or hostMask
    return SubnetShape(network.toIpv4().hostAddress, total, network, broadcast, local, prefixLength)
}

private fun ByteArray.toUnsignedLong(): Long = fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xffL) }

private fun Long.toIpv4(): Inet4Address = InetAddress.getByAddress(
    byteArrayOf((this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), toByte()),
) as Inet4Address

private class DiscoveryThreadFactory : ThreadFactory {
    private val serial = AtomicLong()
    override fun newThread(runnable: Runnable): Thread = Thread(runnable, "companion-discovery-${serial.incrementAndGet()}").apply {
        isDaemon = true
    }
}
