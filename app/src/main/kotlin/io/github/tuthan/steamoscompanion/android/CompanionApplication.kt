package io.github.tuthan.steamoscompanion.android

import android.app.Application
import android.content.Context
import io.github.tuthan.steamoscompanion.android.data.AndroidAtomicStateStore
import io.github.tuthan.steamoscompanion.android.data.AndroidClock
import io.github.tuthan.steamoscompanion.android.data.AndroidNetworkAccess
import io.github.tuthan.steamoscompanion.android.data.AndroidSocketFactory
import io.github.tuthan.steamoscompanion.android.data.DiagnosticDocumentWriter
import io.github.tuthan.steamoscompanion.android.data.LocalNetworkAccess
import io.github.tuthan.steamoscompanion.android.data.LocalAliasStore
import io.github.tuthan.steamoscompanion.android.data.PinnedStatusGateway
import io.github.tuthan.steamoscompanion.android.data.WakeRelayStore
import io.github.tuthan.steamoscompanion.android.data.PinnedOperationLookup
import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.ConnectionRepair
import io.github.tuthan.steamoscompanion.core.client.ConnectionRemoval
import io.github.tuthan.steamoscompanion.core.client.ConnectionReplacement
import io.github.tuthan.steamoscompanion.core.client.ControlDispatcher
import io.github.tuthan.steamoscompanion.core.client.DiagnosticExporter
import io.github.tuthan.steamoscompanion.core.client.DisplayDispatcher
import io.github.tuthan.steamoscompanion.core.client.OperationJournal
import io.github.tuthan.steamoscompanion.core.client.JournalUpdateResult
import io.github.tuthan.steamoscompanion.core.client.OperationReconciler
import io.github.tuthan.steamoscompanion.core.client.JournalMutationSender
import io.github.tuthan.steamoscompanion.core.client.PairingStateTransactions
import io.github.tuthan.steamoscompanion.core.client.PowerSettlement
import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanner
import io.github.tuthan.steamoscompanion.core.discovery.PinnedDiscoveryProbe
import io.github.tuthan.steamoscompanion.core.pairing.PairingCoordinator
import io.github.tuthan.steamoscompanion.core.pairing.PinnedPairingGateway
import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.ports.HostPaths
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.wake.WakeSender

/** Failures no other network can cure: bad input, a cancelled request, or a missing permission. */
private val FINAL_NOT_SENT = setOf(TransportFailure.VALIDATION, TransportFailure.CANCELLED, TransportFailure.PERMISSION)

/** Process-level production graph shared by the onboarding and paired-device UI. */
class CompanionRuntime private constructor(
    val clock: Clock,
    val networkAccess: AndroidNetworkAccess,
    val localNetworkAccess: LocalNetworkAccess,
    val aliasStore: LocalAliasStore,
    val wakeRelayStore: WakeRelayStore,
    val stateRepository: EncryptedStateRepository,
    val pairingTransactions: PairingStateTransactions,
    val operationJournal: OperationJournal,
    val startupJournalRecovery: JournalUpdateResult,
    val operationReconciler: OperationReconciler,
    val diagnosticExporter: DiagnosticExporter,
    val diagnosticDocumentWriter: DiagnosticDocumentWriter,
    val connectionRemoval: ConnectionRemoval,
    val connectionReplacement: ConnectionReplacement,
    val controlDispatcher: ControlDispatcher,
    val powerSettlement: PowerSettlement,
    val displayDispatcher: DisplayDispatcher,
    val connectionRepair: ConnectionRepair,
    val statusGateway: PinnedStatusGateway,
    val mutationSender: JournalMutationSender,
    val wakeSender: WakeSender,
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
            val aliasStore = LocalAliasStore(appContext)
            val wakeRelayStore = WakeRelayStore(appContext)
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
            val operationJournal = OperationJournal(stateRepository, clock)
            val startupJournalRecovery = operationJournal.recoverPreparedEntries()
            val operationReconciler = OperationReconciler(
                stateRepository, operationJournal, PinnedOperationLookup(transport, networkAccess),
            )
            val diagnosticExporter = DiagnosticExporter(stateRepository, clock)
            val diagnosticDocumentWriter = DiagnosticDocumentWriter(appContext.contentResolver, diagnosticExporter)
            val connectionRemoval = ConnectionRemoval(operationJournal, pairingTransactions)
            val connectionReplacement = ConnectionReplacement(pairingTransactions, operationJournal)
            val controlDispatcher = ControlDispatcher(stateRepository, operationJournal, clock)
            val powerSettlement = PowerSettlement(stateRepository, operationJournal)
            val displayDispatcher = DisplayDispatcher(stateRepository, operationJournal, clock)
            val connectionRepair = ConnectionRepair(stateRepository, discoveryScanner)
            val statusGateway = PinnedStatusGateway(transport, networkAccess, stateRepository)
            val mutationSender = JournalMutationSender { host, request, exactBody ->
                val paths = try { networkAccess.activeLanPaths() }
                catch (_: SecurityException) { return@JournalMutationSender RequestOutcome.NotSent(TransportFailure.PERMISSION) }
                // The network holding the host first, then any VPN tunnel. NotSent means no byte left the phone, so
                // trying the next network cannot repeat the request; anything else is final.
                var last: RequestOutcome = RequestOutcome.NotSent(TransportFailure.CONNECT)
                for (path in HostPaths.candidates(paths, host.endpoint, host.wakeTarget.interfaceName)) {
                    val outcome = transport.executePrepared(
                        TransportTarget(HostEndpoint.parse(host.endpoint), path),
                        host.certificatePin,
                        request,
                        exactBody,
                    )
                    if (outcome !is RequestOutcome.NotSent || outcome.failure in FINAL_NOT_SENT) return@JournalMutationSender outcome
                    if (last !is RequestOutcome.NotSent || (last as RequestOutcome.NotSent).failure != TransportFailure.PIN) last = outcome
                }
                last
            }
            val wakeSender = WakeSender(networkAccess, AndroidSocketFactory())
            return CompanionRuntime(
                clock = clock,
                networkAccess = networkAccess,
                localNetworkAccess = localNetworkAccess,
                aliasStore = aliasStore,
                wakeRelayStore = wakeRelayStore,
                stateRepository = stateRepository,
                pairingTransactions = pairingTransactions,
                operationJournal = operationJournal,
                startupJournalRecovery = startupJournalRecovery,
                operationReconciler = operationReconciler,
                diagnosticExporter = diagnosticExporter,
                diagnosticDocumentWriter = diagnosticDocumentWriter,
                connectionRemoval = connectionRemoval,
                connectionReplacement = connectionReplacement,
                controlDispatcher = controlDispatcher,
                powerSettlement = powerSettlement,
                displayDispatcher = displayDispatcher,
                connectionRepair = connectionRepair,
                statusGateway = statusGateway,
                mutationSender = mutationSender,
                wakeSender = wakeSender,
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
