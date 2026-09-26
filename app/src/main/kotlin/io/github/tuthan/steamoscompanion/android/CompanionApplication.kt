package io.github.tuthan.steamoscompanion.android

import android.app.Application
import android.content.Context
import io.github.tuthan.steamoscompanion.android.data.AndroidAtomicStateStore
import io.github.tuthan.steamoscompanion.android.data.AndroidClock
import io.github.tuthan.steamoscompanion.android.data.AndroidNetworkAccess
import io.github.tuthan.steamoscompanion.android.data.AndroidSocketFactory
import io.github.tuthan.steamoscompanion.android.data.LocalNetworkAccess
import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.PairingStateTransactions
import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanner
import io.github.tuthan.steamoscompanion.core.discovery.PinnedDiscoveryProbe
import io.github.tuthan.steamoscompanion.core.pairing.PairingCoordinator
import io.github.tuthan.steamoscompanion.core.pairing.PinnedPairingGateway
import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport

/** Process-level production graph shared by the onboarding and paired-device UI. */
class CompanionRuntime private constructor(
    val clock: Clock,
    val networkAccess: AndroidNetworkAccess,
    val localNetworkAccess: LocalNetworkAccess,
    val stateRepository: EncryptedStateRepository,
    val pairingTransactions: PairingStateTransactions,
    val transport: PinnedHttpTransport,
    val pairingCoordinator: PairingCoordinator,
    val discoveryScanner: DiscoveryScanner,
) : AutoCloseable {
    override fun close() = discoveryScanner.close()

    companion object {
        fun create(context: Context): CompanionRuntime {
            val appContext = context.applicationContext
            val clock = AndroidClock()
            val networkAccess = AndroidNetworkAccess(appContext)
            val localNetworkAccess = LocalNetworkAccess(appContext)
            val stateRepository = AndroidAtomicStateStore.createRepository(appContext)
            val pairingTransactions = PairingStateTransactions(stateRepository)
            val transport = PinnedHttpTransport(clock, networkAccess, AndroidSocketFactory())
            val pairingCoordinator = PairingCoordinator(
                repository = stateRepository,
                transactions = pairingTransactions,
                clock = clock,
                gateway = PinnedPairingGateway(transport, networkAccess),
            )
            val discoveryScanner = DiscoveryScanner(networkAccess, PinnedDiscoveryProbe(transport))
            return CompanionRuntime(
                clock = clock,
                networkAccess = networkAccess,
                localNetworkAccess = localNetworkAccess,
                stateRepository = stateRepository,
                pairingTransactions = pairingTransactions,
                transport = transport,
                pairingCoordinator = pairingCoordinator,
                discoveryScanner = discoveryScanner,
            )
        }
    }
}

/** Keeps the production transport and local-network gate rooted in every minified app variant. */
class CompanionApplication : Application() {
    lateinit var runtime: CompanionRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = CompanionRuntime.create(this)
    }
}
