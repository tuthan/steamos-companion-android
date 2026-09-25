package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.JsonFormatException
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentStateCodecTest {
    @Test
    fun roundTripsActiveCandidateSecretsAndExactJournalBody() {
        val host = testActiveHost()
        val request = testPowerRequest()
        val body = BoundedJson.encode(request.body!!)
        val entry = JournalEntry.create(1, host, request, body, 1_790_000_000_000)
        val candidate = testCandidate().copy(generation = 3, variant = PairingVariant.ADVANCED_PAYLOAD, verificationNonce = null, payloadId = "payload-1", payloadSecret = "payload-secret-001")
        val state = PersistentState(revision = 8, pairingGeneration = 3, activeHost = host, candidate = candidate, journal = listOf(entry))

        val decoded = PersistentStateCodec.decodeCurrent(PersistentStateCodec.parseDocument(PersistentStateCodec.encode(state)))

        assertEquals(state, decoded)
        assertArrayEquals(body, decoded.journal.single().bodyBytes())
        assertEquals(entry.bodySha256, decoded.journal.single().bodySha256)
    }

    @Test
    fun preservesHistoricalClientIdentityAndRejectsUnknownFieldsBadDigestAndOversizedPayload() {
        val host = testActiveHost()
        val document = PersistentStateCodec.parseDocument(PersistentStateCodec.encode(PersistentState(activeHost = host)))
        val replacementHost = host.copy(clientId = "different-client")
        val historicalEntry = JournalEntry.create(1, host, testPowerRequest(), BoundedJson.encode(testPowerRequest().body!!), 10)
        val repairedState = PersistentState(activeHost = replacementHost, journal = listOf(historicalEntry)).validated()
        assertEquals("different-client", repairedState.activeHost!!.clientId)
        assertEquals("client-android", repairedState.journal.single().clientId)

        val badJournal = JournalEntry.create(1, host, testPowerRequest(), BoundedJson.encode(testPowerRequest().body!!), 10)
            .copy(bodySha256 = "00".repeat(32))
        assertThrows(StateInvariantException::class.java) { badJournal.validated() }

        val duplicateLocalGeneration = PersistentState(
            journal = listOf(
                historicalEntry,
                JournalEntry.create(1, host, testPowerRequest("different-request"), BoundedJson.encode(testPowerRequest("different-request").body!!), 11),
            ),
        )
        assertThrows(StateInvariantException::class.java) { duplicateLocalGeneration.validated() }

        val unknown = JsonValue.ObjectValue(document.fields + ("unexpected" to JsonValue.BooleanValue(true)))
        assertThrows(JsonFormatException::class.java) { PersistentStateCodec.decodeCurrent(unknown) }
        assertThrows(JsonFormatException::class.java) { PersistentStateCodec.parseDocument(ByteArray(PersistentStateLimits.MAX_PLAINTEXT_BYTES + 1)) }

        val oversizeBody = ByteArray(4_097) { 1 }
        val oversizedEntry = JournalEntry.create(1, host, testPowerRequest(), BoundedJson.encode(testPowerRequest().body!!), 10)
            .copy(
                bodyBase64 = Base64.getEncoder().encodeToString(oversizeBody),
                bodySha256 = MessageDigest.getInstance("SHA-256").digest(oversizeBody).joinToString("") { "%02x".format(it) },
            )
        assertThrows(StateInvariantException::class.java) { oversizedEntry.validated() }
    }

    @Test
    fun migratesOnlyTheKnownEmptyVersionZeroFixture() {
        val legacy = jsonObject(
            "schema_version" to JsonValue.NumberValue("0"),
            "revision" to JsonValue.NumberValue("7"),
            "active_host" to JsonValue.NullValue,
            "candidate" to JsonValue.NullValue,
            "journal" to JsonValue.ArrayValue(emptyList()),
        )
        val migrated = PersistentStateCodec.migrateKnownDocument(legacy)!!
        val state = PersistentStateCodec.decodeCurrent(migrated)
        assertEquals(7, state.revision)
        assertEquals(0, state.pairingGeneration)
        assertThrows(JsonFormatException::class.java) {
            PersistentStateCodec.migrateKnownDocument(
                JsonValue.ObjectValue(legacy.fields + ("candidate" to JsonValue.ObjectValue(emptyMap()))),
            )
        }
    }

    @Test
    fun migratesVersionOnePairingCandidateWithoutLosingExistingSecretsOrGeneration() {
        val current = PersistentStateCodec.parseDocument(
            PersistentStateCodec.encode(
                PersistentState(pairingGeneration = 3, candidate = testCandidate().copy(generation = 3)),
            ),
        )
        val oldCandidate = current["candidate"]!!.asObject("candidate")
        val v1OnlyFields = setOf(
            "verification_code", "network_id", "request_started_at_ms", "local_expires_at_ms",
            "host_expires_at_ms", "payload_expires_at_ms", "poll_not_before_ms",
        )
        val v1 = JsonValue.ObjectValue(
            linkedMapOf(
                "schema_version" to JsonValue.NumberValue("1"),
                "revision" to current["revision"]!!,
                "pairing_generation" to current["pairing_generation"]!!,
                "active_host" to current["active_host"]!!,
                "candidate" to JsonValue.ObjectValue(oldCandidate.fields.filterKeys { it !in v1OnlyFields }),
                "journal" to current["journal"]!!,
            ),
        )
        val migrated = PersistentStateCodec.migrateKnownDocument(v1)!!
        val decoded = PersistentStateCodec.decodeCurrent(migrated)
        assertEquals(2, decoded.schemaVersion)
        assertEquals(3, decoded.pairingGeneration)
        assertEquals("client-new", decoded.candidate!!.clientId)
        assertEquals(testCandidate().verificationNonce, decoded.candidate!!.verificationNonce)
        assertEquals(null, decoded.candidate!!.verificationCode)
    }

    @Test
    fun migrationCarriesLegacyPayloadExpiryForwardAsAnUnextendableDeadline() {
        val expiry = 1_790_000_060_000L
        val payloadCandidate = PairingCandidate(
            generation = 3,
            variant = PairingVariant.ADVANCED_PAYLOAD,
            clientId = "client-new",
            clientName = "Android Companion",
            requestedScopes = listOf("status.read", "power.control"),
            endpoint = "deck.local:18443",
            certificatePin = "sha256:" + "cd".repeat(32),
            hostId = "host-fixture0001xxxxxxxxxxxxx",
            createdAtMillis = 1_790_000_000_000L,
            expiresAtMillis = expiry,
            phase = CandidatePhase.READY,
            payloadId = "pair-fixture0001xxxxxxxxxxxxx",
            payloadSecret = "fixture-payload-secret-001",
            pairingId = "pair-fixture0001xxxxxxxxxxxxx",
            payloadExpiresAtMillis = expiry,
        )
        val current = PersistentStateCodec.parseDocument(
            PersistentStateCodec.encode(PersistentState(pairingGeneration = 3, candidate = payloadCandidate)),
        )
        val oldCandidate = current["candidate"]!!.asObject("candidate")
        val v1OnlyFields = setOf(
            "verification_code", "network_id", "request_started_at_ms", "local_expires_at_ms",
            "host_expires_at_ms", "payload_expires_at_ms", "poll_not_before_ms",
        )
        val v1 = JsonValue.ObjectValue(
            linkedMapOf(
                "schema_version" to JsonValue.NumberValue("1"),
                "revision" to current["revision"]!!,
                "pairing_generation" to current["pairing_generation"]!!,
                "active_host" to current["active_host"]!!,
                "candidate" to JsonValue.ObjectValue(oldCandidate.fields.filterKeys { it !in v1OnlyFields }),
                "journal" to current["journal"]!!,
            ),
        )
        val migrated = PersistentStateCodec.decodeCurrent(PersistentStateCodec.migrateKnownDocument(v1)!!)
        assertEquals(expiry, migrated.candidate!!.expiresAtMillis)
        assertEquals(expiry, migrated.candidate!!.payloadExpiresAtMillis)
    }

    @Test
    fun candidateVariantAndStagedCredentialMustAgree() {
        val candidate = testCandidate().copy(
            generation = 4,
            phase = CandidatePhase.APPROVED_STAGED,
            hostId = "host-new",
            pairingId = "pairing-1",
            stagedCredential = StagedCredential(
                clientId = "wrong-client",
                hostId = "host-new",
                bearerToken = "staged-token",
                grantedScopes = listOf("status.read"),
                wakeTarget = StoredWakeTarget(false, "", null, null, null),
                pairedAtMillis = 20,
            ),
        )
        assertThrows(StateInvariantException::class.java) { candidate.validated(4) }
        assertTrue(candidate.copy(stagedCredential = candidate.stagedCredential!!.copy(clientId = "client-new")).validated(4).stagedCredential != null)
    }
}
