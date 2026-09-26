package io.github.tuthan.steamoscompanion.core.pairing

import io.github.tuthan.steamoscompanion.core.client.CandidatePhase
import io.github.tuthan.steamoscompanion.core.client.PairingCandidate
import io.github.tuthan.steamoscompanion.core.client.PairingVariant
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.JsonFormatException
import io.github.tuthan.steamoscompanion.core.protocol.JsonLimits
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.asLong
import io.github.tuthan.steamoscompanion.core.protocol.asString
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import org.bouncycastle.crypto.generators.SCrypt

data class PairingPayload(
    val endpoint: String,
    val hostId: String,
    val certificateFingerprint: String,
    val pairingId: String,
    val secret: String,
    val expiresAtMillis: Long,
)

/** Strict decoder for the exact `steamos-companion:v1:` host payload format. */
object PairingPayloadParser {
    const val PREFIX = "steamos-companion:v1:"
    const val MAX_PAYLOAD_CHARS = 4_096
    private const val MAX_DECODED_BYTES = 3_072
    private val encodedPayload = Regex("[A-Za-z0-9_-]+")
    private val safeId = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    private val fingerprint = Regex("sha256:[0-9a-f]{64}")
    private val limits = JsonLimits(
        maxBytes = MAX_DECODED_BYTES,
        maxDepth = 8,
        maxStringCodePoints = 1_024,
        maxContainerItems = 32,
        maxNodes = 64,
        maxNumberChars = 64,
    )

    fun parse(value: String, nowMillis: Long): PairingPayload {
        if (value.length !in (PREFIX.length + 1)..MAX_PAYLOAD_CHARS || !value.startsWith(PREFIX)) {
            throw PairingInputException("Pairing payload format is unsupported")
        }
        val encoded = value.removePrefix(PREFIX)
        if (!encodedPayload.matches(encoded)) throw PairingInputException("Pairing payload is malformed")
        val bytes = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw PairingInputException("Pairing payload is malformed")
        }
        if (bytes.isEmpty() || bytes.size > MAX_DECODED_BYTES ||
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != encoded
        ) {
            throw PairingInputException("Pairing payload is malformed")
        }
        val document = try {
            BoundedJson.parseObject(bytes, limits)
        } catch (_: JsonFormatException) {
            throw PairingInputException("Pairing payload is malformed")
        }
        val version = try {
            document["protocol_version"].asLong("protocol_version")
        } catch (_: JsonFormatException) {
            throw PairingInputException("Pairing payload protocol is unsupported")
        }
        if (version != 1L) throw PairingInputException("Pairing payload protocol is unsupported")

        val endpoint = try {
            HostEndpoint.parse(document["endpoint"].asString("endpoint")).authority
        } catch (_: Exception) {
            throw PairingInputException("Pairing payload endpoint is invalid")
        }
        val hostId = requiredString(document, "host_id")
        val pin = requiredString(document, "certificate_fingerprint")
        val pairingId = requiredString(document, "pairing_id")
        val secret = requiredString(document, "secret")
        if (!safeId.matches(hostId) || !safeId.matches(pairingId)) {
            throw PairingInputException("Pairing payload identity is invalid")
        }
        if (!fingerprint.matches(pin)) throw PairingInputException("Pairing payload fingerprint is invalid")
        if (secret.length !in 16..256 || secret.any { it.code < 0x20 || it.code == 0x7f }) {
            throw PairingInputException("Pairing payload secret is invalid")
        }
        val expiry = (document["expires_at"] as? JsonValue.NumberValue)?.lexical?.toDoubleOrNull()
            ?: throw PairingInputException("Pairing payload expiry is invalid")
        if (!expiry.isFinite() || expiry <= 0.0 || expiry > Long.MAX_VALUE.toDouble() / 1_000.0) {
            throw PairingInputException("Pairing payload expiry is invalid")
        }
        val expiresAtMillis = (expiry * 1_000.0).toLong()
        if (expiresAtMillis <= nowMillis) throw PairingInputException("Pairing payload has expired")
        return PairingPayload(endpoint, hostId, pin, pairingId, secret, expiresAtMillis)
    }

    private fun requiredString(document: JsonValue.ObjectValue, name: String): String = try {
        document[name].asString(name)
    } catch (_: JsonFormatException) {
        throw PairingInputException("Pairing payload is incomplete")
    }
}

class PairingInputException(message: String) : IllegalArgumentException(message)

/** Calls BouncyCastle's selected implementation directly; callers must execute it on a worker. */
object SasCodeDeriver {
    private val pinPattern = Regex("sha256:[0-9a-f]{64}")
    private val noncePattern = Regex("[A-Za-z0-9_-]{22}")
    private val saltPrefix = "steamos-companion:v1:pairing-sas:".toByteArray(Charsets.US_ASCII)

    fun derive(verificationNonce: String, certificateFingerprint: String): String {
        require(noncePattern.matches(verificationNonce)) { "Verification nonce is invalid" }
        require(pinPattern.matches(certificateFingerprint)) { "Certificate fingerprint is invalid" }
        val nonce = try {
            Base64.getUrlDecoder().decode(verificationNonce)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Verification nonce is invalid")
        }
        require(nonce.size == SAS_NONCE_BYTES && Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) == verificationNonce) {
            "Verification nonce is invalid"
        }
        val pinBytes = certificateFingerprint.toByteArray(Charsets.US_ASCII)
        val salt = saltPrefix + pinBytes
        val derived = SCrypt.generate(nonce, salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, DERIVED_BYTES)
        val decimal = BigInteger(1, derived).mod(BigInteger.valueOf(CODE_MODULUS)).toLong()
        derived.fill(0)
        nonce.fill(0)
        salt.fill(0)
        return String.format(Locale.ROOT, "%08d", decimal)
    }

    const val SAS_NONCE_BYTES = 16
    const val SCRYPT_N = 16_384
    const val SCRYPT_R = 8
    const val SCRYPT_P = 1
    const val DERIVED_BYTES = 8
    const val CODE_MODULUS = 100_000_000L
}

/** Builds encrypted pending records and binds discovery identity before pairing can begin. */
class PairingCandidateFactory(private val random: SecureRandom = SecureRandom()) {
    fun fromDiscovery(
        discovered: UnverifiedDiscoveryCandidate,
        clientName: String,
        scopes: List<String>,
        nowMillis: Long,
    ): PairingCandidate {
        val target = discovered.target
        validateIdentity(discovered.hostId, discovered.certificateFingerprint)
        val nonceBytes = ByteArray(SasCodeDeriver.SAS_NONCE_BYTES).also(random::nextBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        val code = SasCodeDeriver.derive(nonce, discovered.certificateFingerprint)
        nonceBytes.fill(0)
        return PairingCandidate(
            generation = 0,
            variant = PairingVariant.VERIFICATION,
            clientId = newClientId(),
            clientName = validateClientName(clientName),
            requestedScopes = validateScopes(scopes),
            endpoint = target.endpoint.authority,
            certificatePin = discovered.certificateFingerprint,
            hostId = discovered.hostId,
            createdAtMillis = nowMillis,
            expiresAtMillis = null,
            phase = CandidatePhase.READY,
            verificationNonce = nonce,
            verificationCode = code,
            networkId = target.network.id,
        )
    }

    fun fromPayload(
        payload: PairingPayload,
        networkId: String?,
        clientName: String,
        scopes: List<String>,
        nowMillis: Long,
    ): PairingCandidate = PairingCandidate(
        generation = 0,
        variant = PairingVariant.ADVANCED_PAYLOAD,
        clientId = newClientId(),
        clientName = validateClientName(clientName),
        requestedScopes = validateScopes(scopes),
        endpoint = payload.endpoint,
        certificatePin = payload.certificateFingerprint,
        hostId = payload.hostId,
        createdAtMillis = nowMillis,
        expiresAtMillis = payload.expiresAtMillis,
        phase = CandidatePhase.READY,
        payloadId = payload.pairingId,
        payloadSecret = payload.secret,
        pairingId = payload.pairingId,
        networkId = networkId,
        payloadExpiresAtMillis = payload.expiresAtMillis,
    )

    private fun newClientId(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return "client-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { bytes.fill(0) }
    }

    companion object {
        const val DEFAULT_CLIENT_NAME = "Android Companion"
        val DEFAULT_SCOPES = listOf("status.read", "power.control", "display.control")
        private val allowedScopes = setOf("status.read", "power.control", "display.control")
        private val id = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")

        fun validateClientName(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed.length > 96 || trimmed.any { it.code < 0x20 || it.code == 0x7f }) {
                throw PairingInputException("Client name must be 1–96 safe characters")
            }
            return trimmed
        }

        fun validateScopes(values: List<String>): List<String> {
            if (values.isEmpty() || values.size > allowedScopes.size || values.distinct().size != values.size ||
                values.any { it !in allowedScopes } || "status.read" !in values
            ) {
                throw PairingInputException("Pairing needs status.read and may request only status, power, or display scopes")
            }
            return listOf("status.read", "power.control", "display.control").filter { it in values }
        }

        private fun validateIdentity(hostId: String, pin: String) {
            require(id.matches(hostId)) { "Discovered host identity is invalid" }
            require(Regex("sha256:[0-9a-f]{64}").matches(pin)) { "Discovered certificate fingerprint is invalid" }
        }
    }
}

/** Only the protocol's allowed key set is serialized. The comparison code is never a wire field. */
object PairingRequestBuilder {
    fun verification(candidate: PairingCandidate): JsonValue.ObjectValue {
        require(candidate.variant == PairingVariant.VERIFICATION)
        val fields = linkedMapOf<String, JsonValue>(
            "verification_nonce" to JsonValue.StringValue(requireNotNull(candidate.verificationNonce)),
            "client_id" to JsonValue.StringValue(candidate.clientId),
            "client_name" to JsonValue.StringValue(candidate.clientName),
            "scopes" to JsonValue.ArrayValue(candidate.requestedScopes.map(JsonValue::StringValue)),
        )
        candidate.pairingSession?.let { fields["pairing_session"] = JsonValue.StringValue(it) }
        return JsonValue.ObjectValue(fields)
    }

    fun payload(candidate: PairingCandidate): JsonValue.ObjectValue {
        require(candidate.variant == PairingVariant.ADVANCED_PAYLOAD)
        val fields = linkedMapOf<String, JsonValue>(
            "pairing_id" to JsonValue.StringValue(requireNotNull(candidate.payloadId)),
            "secret" to JsonValue.StringValue(requireNotNull(candidate.payloadSecret)),
            "client_id" to JsonValue.StringValue(candidate.clientId),
            "client_name" to JsonValue.StringValue(candidate.clientName),
            "scopes" to JsonValue.ArrayValue(candidate.requestedScopes.map(JsonValue::StringValue)),
        )
        candidate.pairingSession?.let { fields["pairing_session"] = JsonValue.StringValue(it) }
        return JsonValue.ObjectValue(fields)
    }

    fun request(candidate: PairingCandidate) = when (candidate.variant) {
        PairingVariant.VERIFICATION -> PairingRequestBuilder.verification(candidate)
        PairingVariant.ADVANCED_PAYLOAD -> PairingRequestBuilder.payload(candidate)
    }
}
