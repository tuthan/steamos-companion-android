package io.github.tuthan.steamoscompanion.core.pairing

import io.github.tuthan.steamoscompanion.core.client.CandidatePhase
import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.MemoryStateStore
import io.github.tuthan.steamoscompanion.core.client.JvmAesGcmStateCipher
import io.github.tuthan.steamoscompanion.core.client.PairingStateTransactions
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.client.testActiveHost
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.ports.SocketFactory
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.PairingState
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.DiscoveryProbeResult
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.ResponseTiming
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCoordinatorTest {
    @Test
    fun pendingPairingGetsHostDeadlineAnchorOnlyFromFreshTimedReply() {
        val base = TEST_NOW * 1_000_000L
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(TEST_NOW).atZone(ZoneOffset.UTC))
        val rig = rig({ _, _, _ ->
            pendingReply(expiresAtMillis = TEST_NOW + 90_000)
                .copy(timing = ResponseTiming(base, base + 250_000_000L, date))
        })
        val pending = rig.coordinator.startNonce(discovery()).join() as PairingAction.Pending
        assertTrue(pending.deadlineAnchor != null)
        assertTrue(pending.deadlineAnchor!!.remainingSeconds(base + 250_000_000L) in 88L..89L)
        val restored = PairingCoordinator(
            rig.repository, PairingStateTransactions(rig.repository), rig.clock, rig.gateway,
            executor = java.util.concurrent.Executor { it.run() },
        ).resume().join() as PairingAction.Pending
        assertNull(restored.deadlineAnchor)
    }

    @Test
    fun backwardPhoneClockCannotExtendPendingPairingInCurrentProcess() {
        val clock = object : Clock {
            var wall = TEST_NOW
            var elapsed = 0L
            override fun wallTimeMillis() = wall
            override fun monotonicNanos() = elapsed
        }
        val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
        val gateway = ScriptedPairingGateway { _, _, _ -> pendingReply(expiresAtMillis = TEST_NOW + 90_000) }
        val coordinator = PairingCoordinator(
            repository, PairingStateTransactions(repository), clock, gateway,
            executor = java.util.concurrent.Executor { it.run() },
        )
        val pending = coordinator.startNonce(discovery()).join() as PairingAction.Pending
        clock.wall -= 3_600_000
        clock.elapsed += 91_000_000_000L
        assertTrue(coordinator.poll(pending.candidate.generation).join() is PairingAction.Expired)
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun persistsRequestBeforeNetworkAndPendingIdentityBeforePollThenRestartsAndCancels() {
        lateinit var repository: EncryptedStateRepository
        val rig = rig({ _, request, _ ->
            if (request.path == ApiRoute.PAIR_REQUEST.path) {
                val saved = (repository.load() as StateLoadResult.Ready).state.candidate!!
                assertEquals(CandidatePhase.REQUESTING, saved.phase)
                assertTrue(saved.requestStartedAtMillis != null)
                assertTrue(saved.localExpiresAtMillis != null)
                assertTrue("verification_code" !in request.body!!.fields)
                pendingReply(expiresAtMillis = TEST_NOW + 90_000)
            } else {
                val state = (repository.load() as StateLoadResult.Ready).state
                assertNull("local state is durably cleared before remote cancel", state.candidate)
                assertEquals(setOf("pairing_id", "verification_nonce", "client_id", "pairing_session"), request.body!!.fields.keys)
                cancelledReply()
            }
        })
        repository = rig.repository
        val gateway = rig.gateway

        val first = rig.coordinator.startNonce(discovery(), cancellation = CancellationSignal()).join()
        assertTrue(first is PairingAction.Pending)
        val pending = (first as PairingAction.Pending).candidate
        assertEquals(TEST_PAIRING_ID, pending.pairingId)
        assertEquals(TEST_SESSION, pending.pairingSession)
        assertEquals(TEST_NOW + 90_000, pending.expiresAtMillis)
        assertEquals(TEST_NOW + 120_000, pending.localExpiresAtMillis)
        assertEquals(TEST_NOW + 90_000, pending.hostExpiresAtMillis)
        assertEquals(1, gateway.requests.size)

        // Reopen the same encrypted bytes as if the process had died while pending.
        val reopenedRepository = EncryptedStateRepository(rig.store, rig.cipher)
        val resumedGateway = rig.gateway
        val resumed = PairingCoordinator(
            reopenedRepository,
            PairingStateTransactions(reopenedRepository),
            rig.clock,
            resumedGateway,
            executor = java.util.concurrent.Executor { it.run() },
        )
        assertTrue(resumed.resume().join() is PairingAction.Pending)
        val cancelled = resumed.cancel(pending.generation).join() as PairingAction.Cancelled
        assertFalse(cancelled.localOnly)
        assertTrue(cancelled.remoteConfirmed)
        assertTrue(cancelled.explanation.contains("removed from the host"))
        assertNull((reopenedRepository.load() as StateLoadResult.Ready).state.candidate)
        assertEquals(2, resumedGateway.requests.size)
    }

    @Test
    fun payloadRequestPersistsSeparateVariantAndCancelNeverCallsRemoteRoute() {
        lateinit var repository: EncryptedStateRepository
        val gateway = ScriptedPairingGateway { _, request, _ ->
            val saved = (repository.load() as StateLoadResult.Ready).state.candidate!!
            assertEquals(CandidatePhase.REQUESTING, saved.phase)
            assertEquals(setOf("pairing_id", "secret", "client_id", "client_name", "scopes"), request.body!!.fields.keys)
            assertFalse("verification_nonce" in requireNotNull(request.body).fields)
            pendingReply(pairingId = TEST_PAIRING_ID, expiresAtMillis = TEST_NOW + 90_000)
        }
        val rig = rig({ c, r, s -> gateway.execute(c, r, s) })
        repository = rig.repository
        val action = rig.coordinator.startPayload(
            payloadText(TEST_NOW + 60_000),
            networkId = "wifi-1",
        ).join() as PairingAction.Pending
        assertEquals(TEST_NOW + 60_000, action.candidate.expiresAtMillis)
        assertEquals(TEST_NOW + 90_000, action.candidate.hostExpiresAtMillis)
        assertEquals("wifi-1", action.candidate.networkId)

        val cancelled = rig.coordinator.cancel(action.candidate.generation).join() as PairingAction.Cancelled
        assertTrue(cancelled.localOnly)
        assertFalse(cancelled.remoteConfirmed)
        assertTrue(cancelled.explanation.contains("until it expires"))
        assertEquals(1, gateway.requests.size)
        assertNull((rig.repository.load() as StateLoadResult.Ready).state.candidate)
    }

    @Test
    fun latePayloadApprovalAfterLocalCancelCannotRestoreCredentials() {
        val enteredPoll = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val rig = rig(
                handler = { candidate, request, _ ->
                    if ("pairing_session" !in request.body!!.fields) {
                        pendingReply(pairingId = requireNotNull(candidate.pairingId))
                    } else {
                        enteredPoll.countDown()
                        assertTrue(releasePoll.await(5, TimeUnit.SECONDS))
                        approvedReply(
                            pairingId = requireNotNull(candidate.pairingId),
                            clientId = candidate.clientId,
                            scopes = candidate.requestedScopes,
                        )
                    }
                },
                executor = java.util.concurrent.Executor { command -> pool.execute(command) },
            )
            val pending = rig.coordinator.startPayload(payloadText(TEST_NOW + 60_000), "wifi-1").join() as PairingAction.Pending
            rig.clock.now += 2_000
            val poll = rig.coordinator.poll(pending.candidate.generation)
            assertTrue(enteredPoll.await(5, TimeUnit.SECONDS))

            val cancelled = rig.coordinator.cancel(pending.candidate.generation).get(5, TimeUnit.SECONDS) as PairingAction.Cancelled
            assertTrue(cancelled.localOnly)
            assertFalse(cancelled.remoteConfirmed)
            releasePoll.countDown()

            assertTrue(poll.get(5, TimeUnit.SECONDS) is PairingAction.StaleGeneration)
            val state = (rig.repository.load() as StateLoadResult.Ready).state
            assertNull(state.candidate)
            assertNull(state.activeHost)
            assertEquals(2, rig.gateway.requests.size)
            assertTrue(rig.gateway.requests.none { it.path == ApiRoute.PAIR_CANCEL.path })
        } finally {
            releasePoll.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun lostInitialReplySurvivesAsUnconfirmedAndNeverRetriesAutomatically() {
        val rig = rig(handler = { _, _, _ -> unconfirmedReply() })
        val result = rig.coordinator.startNonce(discovery()).join() as PairingAction.RequestUnconfirmed
        assertEquals(CandidatePhase.REQUEST_UNCONFIRMED, result.candidate.phase)
        assertNull(result.candidate.pairingId)
        assertNull(result.candidate.pairingSession)
        assertEquals(1, rig.gateway.requests.size)

        assertTrue(rig.coordinator.requestInitial(result.candidate.generation).join() is PairingAction.RequestUnconfirmed)
        assertEquals(1, rig.gateway.requests.size)
        val cancel = rig.coordinator.cancel(result.candidate.generation).join() as PairingAction.Cancelled
        assertTrue(cancel.localOnly)
        assertTrue(cancel.explanation.contains("first reply did not provide a pairing ID"))
        assertEquals(1, rig.gateway.requests.size)
    }

    @Test
    fun replacementApprovalWaitsForExactOldHostConfirmationBeforeSwapping() {
        val oldHost = testActiveHost(hostId = "old-host", endpoint = "old.local:18443", token = "old-token")
        val rig = rig(handler = { candidate, request, _ ->
            if (request.body!!.fields.containsKey("pairing_session")) {
                approvedReply(clientId = candidate.clientId, scopes = candidate.requestedScopes)
            } else {
                pendingReply()
            }
        })
        assertTrue(rig.repository.transact { it.copy(activeHost = oldHost) } is StateCommitResult.Saved)
        val pending = rig.coordinator.startNonce(discovery()).join() as PairingAction.Pending
        assertTrue(rig.coordinator.poll(pending.candidate.generation).join() is PairingAction.RateLimited)
        assertEquals(1, rig.gateway.requests.size)

        rig.clock.now += 2_000
        val staged = rig.coordinator.poll(pending.candidate.generation).join() as PairingAction.ReplacementReady
        assertEquals(oldHost.hostId, staged.currentHostId)
        assertEquals(TEST_HOST, staged.proposedHostId)
        assertEquals(oldHost, (rig.repository.load() as StateLoadResult.Ready).state.activeHost)
        assertTrue(rig.coordinator.resume().join() is PairingAction.ReplacementReady)
        val approved = PairingStateTransactions(rig.repository)
            .acceptApprovedCandidateFor(staged.candidateGeneration, oldHost) as StateCommitResult.Saved
        val active = requireNotNull(approved.state.activeHost)
        assertEquals(TEST_HOST, active.hostId)
        assertEquals(pending.candidate.clientId, active.clientId)
        assertEquals(TEST_PIN, active.certificatePin)
        assertEquals("deck.local:18443", active.endpoint)
        assertEquals(TEST_TOKEN, active.bearerToken)
        assertEquals(pending.candidate.requestedScopes, active.grantedScopes)
        assertEquals("001122334455", active.wakeTarget.mac)
        assertTrue(active.wakeTarget.available)
        assertNull((rig.repository.load() as StateLoadResult.Ready).state.candidate)
        assertEquals(2, rig.gateway.requests.size)
        val pollBody = rig.gateway.requests.last().body!!
        assertEquals(setOf("verification_nonce", "client_id", "client_name", "scopes", "pairing_session"), pollBody.fields.keys)
        assertFalse(BoundedJson.encode(pollBody).toString(Charsets.UTF_8).contains(pending.candidate.verificationCode!!))
    }

    @Test
    fun failedFinalApprovalCommitRetainsStagedCredentialWithoutBootstrapSecretsForRecovery() {
        val rig = rig(handler = { candidate, request, _ ->
            if ("pairing_session" in request.body!!.fields) {
                approvedReply(clientId = candidate.clientId, scopes = candidate.requestedScopes)
            } else pendingReply()
        })
        val pending = rig.coordinator.startNonce(discovery()).join() as PairingAction.Pending
        rig.store.failOnWriteNumber = rig.store.writeCount + 3 // poll claim, staged credential, then final active-host commit.
        rig.clock.now += 2_000

        val unsaved = rig.coordinator.poll(pending.candidate.generation).join() as PairingAction.CredentialCouldNotBeSaved
        val staged = repositoryState(rig).candidate!!
        assertEquals(CandidatePhase.APPROVED_STAGED, staged.phase)
        assertTrue(staged.stagedCredential != null)
        assertNull(staged.verificationNonce)
        assertNull(staged.verificationCode)
        assertNull(staged.pairingSession)
        assertNull(repositoryState(rig).activeHost)
        assertEquals(staged, unsaved.candidate)

        val recovered = rig.coordinator.resume().join() as PairingAction.Approved
        assertEquals(TEST_TOKEN, recovered.activeHost.bearerToken)
        assertNull(repositoryState(rig).candidate)
    }

    @Test
    fun consumedCredentialIsTerminalUncertaintyAndDoesNotCreateANewRequest() {
        val rig = rig(handler = { _, request, _ ->
            if ("pairing_session" in request.body!!.fields) {
                RequestOutcome.Rejected(ApiReply.Rejected(404, "pairing_not_found", "pairing is not available"), null)
            } else pendingReply()
        })
        val pending = rig.coordinator.startNonce(discovery()).join() as PairingAction.Pending
        rig.clock.now += 2_000
        val attention = rig.coordinator.poll(pending.candidate.generation).join() as PairingAction.NeedsAttention
        assertTrue(attention.reason.startsWith("Pairing could not be saved"))
        assertEquals(CandidatePhase.CREDENTIAL_UNCONFIRMED, attention.candidate.phase)
        assertNull(attention.candidate.verificationNonce)
        assertNull(attention.candidate.pairingSession)
        assertTrue(rig.coordinator.resume().join() is PairingAction.NeedsAttention)
        assertEquals(2, rig.gateway.requests.size)
    }

    @Test
    fun rejectionAndExpiryBecomeTerminalAndAnExpiredPendingRequestIsNotPolled() {
        val rejectedRig = rig(handler = { _, _, _ -> pairingStateReply(PairingState.REJECTED) })
        val rejected = rejectedRig.coordinator.startNonce(discovery()).join() as PairingAction.Rejected
        assertEquals(CandidatePhase.REJECTED, rejected.candidate.phase)

        val expiredRig = rig(handler = { _, _, _ -> pairingStateReply(PairingState.EXPIRED) })
        val expired = expiredRig.coordinator.startNonce(discovery()).join() as PairingAction.Expired
        assertEquals(CandidatePhase.EXPIRED, expired.candidate.phase)

        val pendingRig = rig(handler = { _, _, _ -> pendingReply(expiresAtMillis = TEST_NOW + 1_000) })
        val pending = pendingRig.coordinator.startNonce(discovery()).join() as PairingAction.Pending
        pendingRig.clock.now += 2_000
        val afterExpiry = pendingRig.coordinator.poll(pending.candidate.generation).join() as PairingAction.Expired
        assertEquals(CandidatePhase.EXPIRED, afterExpiry.candidate.phase)
        assertEquals(1, pendingRig.gateway.requests.size)
    }

    @Test
    fun rateLimitIntervalsArePersistedAndNoRequestIsSentBeforeRetryAfter() {
        var requestNumber = 0
        val rig = rig(handler = { candidate, request, _ ->
            requestNumber++
            when {
                request.path == ApiRoute.PAIR_REQUEST.path && requestNumber == 1 ->
                    RequestOutcome.Rejected(ApiReply.Rejected(429, "rate_limited", "request rate limit exceeded"), 5_000)
                request.path == ApiRoute.PAIR_REQUEST.path && requestNumber == 3 ->
                    RequestOutcome.Rejected(ApiReply.Rejected(429, "rate_limited", "request rate limit exceeded"), 8_000)
                request.path == ApiRoute.PAIR_REQUEST.path && "pairing_session" !in request.body!!.fields ->
                    pendingReply(expiresAtMillis = rigClockNow(candidate) + 90_000)
                request.path == ApiRoute.PAIR_REQUEST.path -> pendingReply()
                else -> error("Unexpected route")
            }
        })
        val action = rig.coordinator.startNonce(discovery()).join() as PairingAction.RateLimited
        assertEquals(TEST_NOW + 5_000, action.retryAtMillis)
        assertEquals(CandidatePhase.READY, action.candidate.phase)
        assertEquals(action.retryAtMillis, action.candidate.pollNotBeforeMillis)
        assertTrue(rig.coordinator.requestInitial(action.candidate.generation).join() is PairingAction.RateLimited)
        assertEquals(1, rig.gateway.requests.size)

        rig.clock.now += 5_000
        val pending = rig.coordinator.requestInitial(action.candidate.generation).join() as PairingAction.Pending
        assertEquals(2, rig.gateway.requests.size)
        rig.clock.now += 2_000
        val rateLimited = rig.coordinator.poll(pending.candidate.generation).join() as PairingAction.RateLimited
        assertEquals(rig.clock.now + 8_000, rateLimited.retryAtMillis)
        assertEquals(CandidatePhase.RATE_LIMITED, rateLimited.candidate.phase)
        assertTrue(rig.coordinator.poll(pending.candidate.generation).join() is PairingAction.RateLimited)
        assertEquals(3, rig.gateway.requests.size)
        rig.clock.now += 8_000
        assertTrue(rig.coordinator.poll(pending.candidate.generation).join() is PairingAction.Pending)
        assertEquals(4, rig.gateway.requests.size)
    }

    @Test
    fun retryAfterPastPairingExpiryResumesAsExpiredWithoutAnotherPoll() {
        val rig = rig(handler = { candidate, request, _ ->
            if ("pairing_session" !in request.body!!.fields) {
                pendingReply(expiresAtMillis = TEST_NOW + 3_000)
            } else {
                RequestOutcome.Rejected(ApiReply.Rejected(429, "rate_limited", "poll rate limit exceeded"), 10_000)
            }
        })
        val pending = rig.coordinator.startNonce(discovery()).join() as PairingAction.Pending
        rig.clock.now += 2_000
        val limited = rig.coordinator.poll(pending.candidate.generation).join() as PairingAction.RateLimited
        assertEquals(TEST_NOW + 3_000, limited.retryAtMillis)
        assertEquals(2, rig.gateway.requests.size)

        rig.clock.now = TEST_NOW + 3_000
        val resumed = rig.coordinator.resume().join()
        assertTrue(resumed is PairingAction.Expired)
        assertEquals(2, rig.gateway.requests.size)
    }

    @Test
    fun initialPayloadRetryAfterPastExpiryBecomesExpiredWithoutResending() {
        val expiry = TEST_NOW + 3_000
        val rig = rig(handler = { _, _, _ ->
            RequestOutcome.Rejected(ApiReply.Rejected(429, "rate_limited", "request rate limit exceeded"), 10_000)
        })
        val limited = rig.coordinator.startPayload(payloadText(expiry), "wifi-1").join() as PairingAction.RateLimited
        assertEquals(expiry, limited.retryAtMillis)
        assertEquals(1, rig.gateway.requests.size)

        rig.clock.now = expiry
        val resumed = rig.coordinator.resume().join()
        assertTrue(resumed is PairingAction.Expired)
        assertTrue(rig.coordinator.requestInitial(limited.candidate.generation).join() is PairingAction.Expired)
        assertEquals(1, rig.gateway.requests.size)
    }

    @Test
    fun processResumeAfterLostApprovalPollDoesNotReplayThePoll() {
        val rig = rig(handler = { _, request, _ ->
            if ("pairing_session" !in request.body!!.fields) pendingReply()
            else RequestOutcome.Unknown(TransportFailure.TIMEOUT)
        })
        val pending = rig.coordinator.startNonce(discovery()).join() as PairingAction.Pending
        rig.clock.now += 2_000
        assertTrue(rig.coordinator.poll(pending.candidate.generation).join() is PairingAction.Pending)
        val storedBeforeRestart = repositoryState(rig).candidate!!
        assertEquals(2, rig.gateway.requests.size)

        val reopened = EncryptedStateRepository(rig.store, rig.cipher)
        val noNetworkGateway = ScriptedPairingGateway { _, _, _ -> error("resume must not replay a lost poll") }
        val resumedCoordinator = PairingCoordinator(
            reopened,
            PairingStateTransactions(reopened),
            rig.clock,
            noNetworkGateway,
            executor = java.util.concurrent.Executor { it.run() },
        )
        val resumed = resumedCoordinator.resume().join() as PairingAction.Pending
        assertEquals(storedBeforeRestart, resumed.candidate)
        assertTrue(noNetworkGateway.requests.isEmpty())
    }

    @Test
    fun processDeathDuringUnresolvedInitialDispatchNeverReplaysThatRequest() {
        val clock = FakeClock()
        val store = io.github.tuthan.steamoscompanion.core.client.MemoryStateStore()
        val cipher = io.github.tuthan.steamoscompanion.core.client.JvmAesGcmStateCipher()
        val repository = EncryptedStateRepository(store, cipher)
        val transactions = PairingStateTransactions(repository)
        val candidate = PairingCandidateFactory().fromDiscovery(discovery(), "Android Companion", PairingCandidateFactory.DEFAULT_SCOPES, TEST_NOW)
        val begun = transactions.beginCandidate(candidate) as StateCommitResult.Saved
        val generation = begun.state.candidate!!.generation
        transactions.updateCandidate(generation) {
            it.copy(
                phase = CandidatePhase.REQUESTING,
                requestStartedAtMillis = TEST_NOW,
                localExpiresAtMillis = TEST_NOW + 120_000,
                expiresAtMillis = TEST_NOW + 120_000,
            )
        }
        val gateway = ScriptedPairingGateway { _, _, _ -> error("resume must not send anything") }
        val reopenedRepository = EncryptedStateRepository(store, cipher)
        val coordinator = PairingCoordinator(
            reopenedRepository, PairingStateTransactions(reopenedRepository), clock, gateway,
            executor = java.util.concurrent.Executor { it.run() },
        )
        val resumed = coordinator.resume().join() as PairingAction.RequestUnconfirmed
        assertEquals(generation, resumed.candidate.generation)
        assertEquals(CandidatePhase.REQUEST_UNCONFIRMED, resumed.candidate.phase)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun schemaOneNonceCandidateRecomputesAndPersistsItsComparisonCodeBeforeUse() {
        val rig = rig(handler = { _, _, _ -> pendingReply() })
        val candidate = PairingCandidateFactory().fromDiscovery(
            discovery(),
            PairingCandidateFactory.DEFAULT_CLIENT_NAME,
            PairingCandidateFactory.DEFAULT_SCOPES,
            TEST_NOW,
        ).copy(verificationCode = null)
        val begun = PairingStateTransactions(rig.repository).beginCandidate(candidate) as StateCommitResult.Saved
        val generation = begun.state.candidate!!.generation

        val resumed = rig.coordinator.resume().join() as PairingAction.Ready
        assertEquals(SasCodeDeriver.derive(candidate.verificationNonce!!, candidate.certificatePin), resumed.candidate.verificationCode)
        assertEquals(resumed.candidate.verificationCode, repositoryState(rig).candidate!!.verificationCode)

        PairingStateTransactions(rig.repository).updateCandidate(generation) { it.copy(verificationCode = null) }
        val pending = rig.coordinator.requestInitial(generation).join() as PairingAction.Pending
        assertEquals(resumed.candidate.verificationCode, pending.candidate.verificationCode)
        assertEquals(1, rig.gateway.requests.size)
    }

    @Test
    fun staleApprovalAfterCancelIsDiscardedAndConcurrentPollsAreCoalesced() {
        val enteredPoll = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val rig = rig(
                handler = { candidate, request, _ ->
                    when {
                        request.path == ApiRoute.PAIR_CANCEL.path -> cancelledReply()
                        "pairing_session" !in request.body!!.fields -> pendingReply()
                        else -> {
                            enteredPoll.countDown()
                            assertTrue(releasePoll.await(5, TimeUnit.SECONDS))
                            approvedReply(clientId = candidate.clientId, scopes = candidate.requestedScopes)
                        }
                    }
                },
                executor = java.util.concurrent.Executor { command -> pool.execute(command) },
            )
            val pending = rig.coordinator.startNonce(discovery()).join() as PairingAction.Pending
            rig.clock.now += 2_000
            val firstPoll = rig.coordinator.poll(pending.candidate.generation)
            assertTrue(enteredPoll.await(5, TimeUnit.SECONDS))
            val duplicatePoll = rig.coordinator.poll(pending.candidate.generation)
            assertSame(firstPoll, duplicatePoll)

            val cancelled = rig.coordinator.cancel(pending.candidate.generation).get(5, TimeUnit.SECONDS) as PairingAction.Cancelled
            assertTrue(cancelled.remoteConfirmed)
            releasePoll.countDown()
            assertTrue(firstPoll.get(5, TimeUnit.SECONDS) is PairingAction.StaleGeneration)
            val state = (rig.repository.load() as StateLoadResult.Ready).state
            assertNull(state.candidate)
            assertNull(state.activeHost)
            assertEquals(2, rig.gateway.requests.count { it.path == ApiRoute.PAIR_REQUEST.path })
            assertEquals(1, rig.gateway.requests.count { it.path == ApiRoute.PAIR_CANCEL.path })
        } finally {
            releasePoll.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun separateCoordinatorInstancesCannotDispatchTheSameInitialRequestTwice() {
        val enteredRequest = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        var calls = 0
        try {
            val rig = rig(
                handler = { candidate, _, _ ->
                    calls++
                    if (calls == 1) {
                        RequestOutcome.Rejected(ApiReply.Rejected(429, "rate_limited", "slow down"), 2_000)
                    } else {
                        enteredRequest.countDown()
                        assertTrue(releaseRequest.await(5, TimeUnit.SECONDS))
                        pendingReply(expiresAtMillis = rigClockNow(candidate) + 90_000)
                    }
                },
                executor = java.util.concurrent.Executor { command -> pool.execute(command) },
            )
            val limited = rig.coordinator.startNonce(discovery()).join() as PairingAction.RateLimited
            rig.clock.now += 2_000
            val secondRepository = EncryptedStateRepository(rig.store, rig.cipher)
            val second = PairingCoordinator(
                secondRepository,
                PairingStateTransactions(secondRepository),
                rig.clock,
                rig.gateway,
                executor = java.util.concurrent.Executor { command -> pool.execute(command) },
            )
            val firstRequest = rig.coordinator.requestInitial(limited.candidate.generation)
            assertTrue(enteredRequest.await(5, TimeUnit.SECONDS))
            val competing = second.requestInitial(limited.candidate.generation).get(5, TimeUnit.SECONDS)
            assertTrue(competing is PairingAction.InProgress)
            assertEquals(2, rig.gateway.requests.size)
            releaseRequest.countDown()
            assertTrue(firstRequest.get(5, TimeUnit.SECONDS) is PairingAction.Pending)
            assertEquals(2, rig.gateway.requests.size)
        } finally {
            releaseRequest.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun separateCoordinatorInstancesRespectPersistedPollInterval() {
        val enteredPoll = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val rig = rig(
                handler = { candidate, request, _ ->
                    if ("pairing_session" !in request.body!!.fields) {
                        pendingReply()
                    } else {
                        enteredPoll.countDown()
                        assertTrue(releasePoll.await(5, TimeUnit.SECONDS))
                        pendingReply()
                    }
                },
                executor = java.util.concurrent.Executor { command -> pool.execute(command) },
            )
            val pending = rig.coordinator.startNonce(discovery()).join() as PairingAction.Pending
            rig.clock.now += 2_000
            val secondRepository = EncryptedStateRepository(rig.store, rig.cipher)
            val second = PairingCoordinator(
                secondRepository,
                PairingStateTransactions(secondRepository),
                rig.clock,
                rig.gateway,
                executor = java.util.concurrent.Executor { command -> pool.execute(command) },
            )
            val firstPoll = rig.coordinator.poll(pending.candidate.generation)
            assertTrue(enteredPoll.await(5, TimeUnit.SECONDS))
            val competing = second.poll(pending.candidate.generation).get(5, TimeUnit.SECONDS)
            assertTrue(competing is PairingAction.RateLimited)
            assertEquals(rig.clock.now + 2_000, (competing as PairingAction.RateLimited).retryAtMillis)
            assertEquals(2, rig.gateway.requests.size)
            releasePoll.countDown()
            assertTrue(firstPoll.get(5, TimeUnit.SECONDS) is PairingAction.Pending)
            assertEquals(2, rig.gateway.requests.size)
        } finally {
            releasePoll.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun responseFromAChangedHostIdentityNeverStoresCredential() {
        val rig = rig(handler = { candidate, request, _ ->
            if ("pairing_session" in request.body!!.fields) {
                val response = approvedReply(clientId = candidate.clientId, hostId = "host-other")
                response
            } else pendingReply()
        })
        val pending = rig.coordinator.startNonce(discovery()).join() as PairingAction.Pending
        rig.clock.now += 2_000
        val changed = rig.coordinator.poll(pending.candidate.generation).join() as PairingAction.IdentityChanged
        assertEquals(TEST_HOST, changed.expectedHostId)
        assertEquals("host-other", changed.actualHostId)
        assertNull((rig.repository.load() as StateLoadResult.Ready).state.activeHost)
        assertEquals(CandidatePhase.IDENTITY_CHANGED, (rig.repository.load() as StateLoadResult.Ready).state.candidate!!.phase)
    }

    @Test
    fun sameEndpointWithChangedPinnedIdentityIsRejectedBeforeDispatch() {
        val rig = rig(handler = { _, _, _ -> error("identity mismatch must stop before the wire") })
        val old = testActiveHost(hostId = TEST_HOST, endpoint = "deck.local:18443")
        rig.repository.transact { it.copy(activeHost = old) }
        val changed = UnverifiedDiscoveryCandidate(
            TransportTarget(
                io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint.parse("deck.local:18443"),
                LanPath("wifi-1", "wlan0", emptyList(), emptyList()),
            ),
            TEST_HOST,
            "SteamOS device",
            "sha256:" + "cd".repeat(32),
        )
        assertTrue(rig.coordinator.startNonce(changed).join() is PairingAction.IdentityChanged)
        assertTrue(rig.gateway.requests.isEmpty())
    }

    @Test
    fun pairingDoesNotFallBackToAnotherLanAfterTheSelectedNetworkDisappears() {
        val alternate = LanPath("wifi-2", "wlan1", emptyList(), emptyList())
        var socketsCreated = 0
        val network = object : NetworkAccess {
            override fun activeLanPaths(): List<LanPath> = listOf(alternate)
            override fun resolve(host: String, path: LanPath): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
            override fun bind(socket: Socket, path: LanPath) = Unit
            override fun bind(socket: DatagramSocket, path: LanPath) = Unit
        }
        val socketFactory = object : SocketFactory {
            override fun create(): Socket {
                socketsCreated++
                return Socket()
            }
            override fun createDatagram(): DatagramSocket = error("not used")
        }
        val transport = PinnedHttpTransport(FakeClock(), network, socketFactory)
        val candidate = PairingCandidateFactory().fromDiscovery(discovery(), "Android Companion", PairingCandidateFactory.DEFAULT_SCOPES, TEST_NOW)
        val outcome = PinnedPairingGateway(transport, network).execute(
            candidate,
            io.github.tuthan.steamoscompanion.core.protocol.ApiRequest(
                HttpMethod.POST,
                ApiRoute.PAIR_REQUEST.path,
                PairingRequestBuilder.request(candidate),
            ),
            CancellationSignal(),
        )
        assertEquals(RequestOutcome.NotSent(TransportFailure.CONNECT), outcome)
        assertEquals(0, socketsCreated)
    }

    @Test
    fun initialKnownRateLimitCanRetryOnlyWithSameCandidateAfterDeadline() {
        val rig = rig(handler = { _, _, _ -> RequestOutcome.Rejected(ApiReply.Rejected(429, "rate_limited", "slow down"), null) })
        val limited = rig.coordinator.startNonce(discovery()).join() as PairingAction.RateLimited
        assertEquals(TEST_NOW + 2_000, limited.retryAtMillis)
        rig.clock.now += 1_999
        assertTrue(rig.coordinator.requestInitial(limited.candidate.generation).join() is PairingAction.RateLimited)
        assertEquals(1, rig.gateway.requests.size)
        rig.clock.now += 1
        val next = rig.coordinator.requestInitial(limited.candidate.generation).join() as PairingAction.RateLimited
        assertEquals(2, rig.gateway.requests.size)
        assertEquals(limited.candidate.clientId, next.candidate.clientId)
        assertEquals(limited.candidate.verificationNonce, next.candidate.verificationNonce)
    }

    private fun discovery() = UnverifiedDiscoveryCandidate(
        TransportTarget(
            io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint.parse("deck.local:18443"),
            LanPath("wifi-1", "wlan0", emptyList(), emptyList()),
        ),
        TEST_HOST,
        "SteamOS device",
        TEST_PIN,
    )

    private fun payloadText(expiresAtMillis: Long): String {
        val document = jsonObject(
            "protocol_version" to JsonValue.NumberValue("1"),
            "endpoint" to JsonValue.StringValue("https://deck.local:18443"),
            "host_id" to JsonValue.StringValue(TEST_HOST),
            "certificate_fingerprint" to JsonValue.StringValue(TEST_PIN),
            "pairing_id" to JsonValue.StringValue(TEST_PAIRING_ID),
            "secret" to JsonValue.StringValue("fixture-payload-secret-001"),
            "expires_at" to JsonValue.NumberValue((expiresAtMillis / 1_000.0).toString()),
        )
        return PairingPayloadParser.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(BoundedJson.encode(document))
    }

    private fun pairingStateReply(state: PairingState) = RequestOutcome.Reply(
        ApiReply.Versioned(
            1,
            VersionedPayload.Pairing(
                state = WireValue.Known(state),
                pairingId = TEST_PAIRING_ID,
                hostId = null,
                expiresAt = null,
                pairingSession = null,
                credential = null,
                wakeTarget = null,
                body = jsonObject(),
            ),
        ),
        200,
        null,
    )

    private fun rigClockNow(candidate: io.github.tuthan.steamoscompanion.core.client.PairingCandidate): Long =
        candidate.requestStartedAtMillis ?: TEST_NOW
}
