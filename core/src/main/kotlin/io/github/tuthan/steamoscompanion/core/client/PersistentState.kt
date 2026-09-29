package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.DisplayRecoveryProfile
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonFormatException
import io.github.tuthan.steamoscompanion.core.protocol.asString
import java.security.MessageDigest
import java.util.Base64

object PersistentStateLimits {
    const val MAX_PLAINTEXT_BYTES = 64 * 1024
    const val MAX_ENVELOPE_BYTES = 65 * 1024
    const val MAX_JOURNAL_ENTRIES = 32
    const val MAX_CACHED_RECOVERY_PROFILES = 8
    const val MAX_OPERATION_BODY_BYTES = 4 * 1024
    const val CURRENT_SCHEMA_VERSION = 6
    const val ENVELOPE_FORMAT_VERSION = 1
    const val STATE_KEY_ALIAS = "steamos-companion-android-state-v1"
    const val STATE_AAD = "steamos-companion:android:state:v1"
}

class StateInvariantException(val code: StateInvariantCode, message: String) : IllegalArgumentException(message)

enum class StateInvariantCode {
    INVALID_FIELD,
    INVALID_IDENTITY,
    INVALID_CANDIDATE,
    INVALID_JOURNAL,
    STATE_TOO_LARGE,
    HISTORY_FULL,
    DUPLICATE_REQUEST_ID,
    OPERATION_UNRESOLVED,
    STALE_GENERATION,
    NEWER_SCHEMA,
}

data class PersistentState(
    val schemaVersion: Int = PersistentStateLimits.CURRENT_SCHEMA_VERSION,
    val revision: Long = 0,
    val pairingGeneration: Long = 0,
    val activeHost: ActiveHost? = null,
    val candidate: PairingCandidate? = null,
    val journal: List<JournalEntry> = emptyList(),
) {
    fun validated(): PersistentState {
        if (schemaVersion != PersistentStateLimits.CURRENT_SCHEMA_VERSION) invalid(StateInvariantCode.NEWER_SCHEMA, "Unsupported state schema")
        if (revision < 0 || pairingGeneration < 0) invalid(StateInvariantCode.INVALID_FIELD, "Invalid state revision")
        if (journal.size > PersistentStateLimits.MAX_JOURNAL_ENTRIES) invalid(StateInvariantCode.HISTORY_FULL, "Operation history is full")
        activeHost?.validated()
        candidate?.validated(pairingGeneration)
        journal.forEach(JournalEntry::validated)

        if (journal.map { it.localGeneration }.toSet().size != journal.size) {
            invalid(StateInvariantCode.INVALID_JOURNAL, "Duplicate local journal generation")
        }
        val duplicateRequest = journal.groupBy { Triple(it.hostId, it.clientId, it.requestId) }.values.any { it.size > 1 }
        if (duplicateRequest) invalid(StateInvariantCode.INVALID_JOURNAL, "Duplicate journal request ID")

        // Journal identities are historical and must survive re-pairing the same remote host
        // with a different client ID. Each entry binds its own host/client/endpoint/pin tuple.
        return this
    }
}

data class ActiveHost(
    val hostId: String,
    val clientId: String,
    val endpoint: String,
    val certificatePin: String,
    val bearerToken: String,
    val grantedScopes: List<String>,
    val wakeTarget: StoredWakeTarget,
    val pairedAtMillis: Long,
    val lastObservedBootId: String? = null,
    /** Null means no successful recovery inventory has been observed for this pairing. */
    val cachedRecoveryProfiles: List<DisplayRecoveryProfile>? = null,
) {
    fun validated(): ActiveHost {
        requireText(hostId, "host ID", 128)
        requireId(clientId, "client ID")
        validateEndpoint(endpoint)
        validatePin(certificatePin)
        requireSecret(bearerToken, "bearer token", 512)
        validateScopes(grantedScopes)
        wakeTarget.validated()
        lastObservedBootId?.let { requireText(it, "host boot ID", 128) }
        cachedRecoveryProfiles?.let { profiles ->
            if (profiles.size > PersistentStateLimits.MAX_CACHED_RECOVERY_PROFILES ||
                profiles.map { it.id }.distinct().size != profiles.size) {
                invalid(StateInvariantCode.INVALID_FIELD, "Invalid cached recovery profiles")
            }
            profiles.forEach { profile ->
                requireText(profile.id, "recovery profile ID", 128)
                requireText(profile.outputId, "recovery output ID", 128)
                requireText(profile.mode.id, "recovery mode ID", 128)
                if (profile.mode.width !in 1..16384 || profile.mode.height !in 1..16384) {
                    invalid(StateInvariantCode.INVALID_FIELD, "Invalid cached recovery mode size")
                }
                profile.mode.refreshHz?.let { requireText(it, "recovery refresh rate", 32) }
            }
        }
        if (pairedAtMillis < 0) invalid(StateInvariantCode.INVALID_FIELD, "Invalid paired timestamp")
        return this
    }
}

data class StoredWakeTarget(
    val available: Boolean,
    val mac: String?,
    val interfaceName: String?,
    val sourceAddress: String?,
    val reason: String?,
) {
    fun validated(): StoredWakeTarget {
        if (mac != null && (mac.length > 12 || !Regex("[0-9a-f]{0,12}").matches(mac))) {
            invalid(StateInvariantCode.INVALID_FIELD, "Invalid wake MAC address")
        }
        interfaceName?.let { requireText(it, "wake interface", 32, allowEmpty = true) }
        sourceAddress?.let { requireText(it, "wake source address", 64) }
        reason?.let { requireText(it, "wake reason", 256) }
        if (available && mac?.length != 12) invalid(StateInvariantCode.INVALID_FIELD, "Available wake target needs a MAC address")
        return this
    }
}

enum class PairingVariant(val wireValue: String) { VERIFICATION("verification"), ADVANCED_PAYLOAD("advanced_payload") }
enum class CandidatePhase(val wireValue: String) {
    READY("ready"),
    REQUESTING("requesting"),
    REQUEST_UNCONFIRMED("request_unconfirmed"),
    PENDING("pending"),
    RATE_LIMITED("rate_limited"),
    REJECTED("rejected"),
    EXPIRED("expired"),
    IDENTITY_CHANGED("identity_changed"),
    CREDENTIAL_UNCONFIRMED("credential_unconfirmed"),
    CANCELLED("cancelled"),
    APPROVED_STAGED("approved_staged"),
}

data class PairingCandidate(
    val generation: Long,
    val variant: PairingVariant,
    val clientId: String,
    val clientName: String,
    val requestedScopes: List<String>,
    val endpoint: String,
    val certificatePin: String,
    val hostId: String?,
    val createdAtMillis: Long,
    val expiresAtMillis: Long?,
    val phase: CandidatePhase,
    val verificationNonce: String? = null,
    val verificationCode: String? = null,
    val payloadId: String? = null,
    val payloadSecret: String? = null,
    val pairingId: String? = null,
    val pairingSession: String? = null,
    val stagedCredential: StagedCredential? = null,
    val networkId: String? = null,
    val requestStartedAtMillis: Long? = null,
    val localExpiresAtMillis: Long? = null,
    val hostExpiresAtMillis: Long? = null,
    val payloadExpiresAtMillis: Long? = null,
    val pollNotBeforeMillis: Long? = null,
) {
    fun validated(currentGeneration: Long): PairingCandidate {
        if (generation <= 0 || generation != currentGeneration) invalid(StateInvariantCode.INVALID_CANDIDATE, "Candidate generation is stale")
        requireId(clientId, "client ID")
        requireText(clientName, "client name", 96)
        validateScopes(requestedScopes)
        validateEndpoint(endpoint)
        validatePin(certificatePin)
        hostId?.let { requireText(it, "host ID", 128) }
        if (createdAtMillis < 0 || expiresAtMillis?.let { it < createdAtMillis } == true) {
            invalid(StateInvariantCode.INVALID_CANDIDATE, "Invalid candidate timestamps")
        }
        networkId?.let { requireText(it, "network ID", 128) }
        val timestamps = listOfNotNull(
            requestStartedAtMillis,
            localExpiresAtMillis,
            hostExpiresAtMillis,
            payloadExpiresAtMillis,
            pollNotBeforeMillis,
        )
        if (timestamps.any { it < 0 } || requestStartedAtMillis?.let { it < createdAtMillis } == true) {
            invalid(StateInvariantCode.INVALID_CANDIDATE, "Invalid pairing deadline")
        }
        val deadlines = listOfNotNull(localExpiresAtMillis, hostExpiresAtMillis, payloadExpiresAtMillis)
        if (expiresAtMillis != null && deadlines.isNotEmpty() && expiresAtMillis > deadlines.min()) {
            invalid(StateInvariantCode.INVALID_CANDIDATE, "Effective expiry exceeds a pairing deadline")
        }
        when (variant) {
            PairingVariant.VERIFICATION -> {
                val requestMayNeedNonce = phase in setOf(
                    CandidatePhase.READY,
                    CandidatePhase.REQUESTING,
                    CandidatePhase.REQUEST_UNCONFIRMED,
                    CandidatePhase.PENDING,
                    CandidatePhase.RATE_LIMITED,
                )
                if ((requestMayNeedNonce && verificationNonce?.let { Regex("[A-Za-z0-9_-]{22}").matches(it) } != true) ||
                    payloadId != null || payloadSecret != null
                ) invalid(StateInvariantCode.INVALID_CANDIDATE, "Invalid verification candidate")
                if (verificationCode != null && !Regex("[0-9]{8}").matches(verificationCode)) {
                    invalid(StateInvariantCode.INVALID_CANDIDATE, "Invalid verification code")
                }
                if (requestMayNeedNonce && requestStartedAtMillis != null && verificationCode == null) {
                    invalid(StateInvariantCode.INVALID_CANDIDATE, "Pairing code is missing")
                }
            }
            PairingVariant.ADVANCED_PAYLOAD -> {
                val requestMayNeedPayload = phase in setOf(
                    CandidatePhase.READY,
                    CandidatePhase.REQUESTING,
                    CandidatePhase.REQUEST_UNCONFIRMED,
                    CandidatePhase.PENDING,
                    CandidatePhase.RATE_LIMITED,
                )
                if (payloadId?.let(::isSafeId) != true ||
                    (requestMayNeedPayload && payloadSecret?.let { it.length in 16..256 && isSafeSecret(it) } != true) ||
                    verificationNonce != null || verificationCode != null ||
                    (pairingId != null && pairingId != payloadId)
                ) invalid(StateInvariantCode.INVALID_CANDIDATE, "Invalid advanced candidate")
            }
        }
        pairingId?.let { requireId(it, "pairing ID") }
        pairingSession?.let {
            if (!Regex("[A-Za-z0-9_-]{16,256}").matches(it)) {
                invalid(StateInvariantCode.INVALID_CANDIDATE, "Invalid pairing session")
            }
        }
        if (phase in setOf(CandidatePhase.PENDING, CandidatePhase.RATE_LIMITED, CandidatePhase.APPROVED_STAGED) && pairingId == null) {
            invalid(StateInvariantCode.INVALID_CANDIDATE, "Pending candidate is missing its host pairing ID")
        }
        if (phase in setOf(CandidatePhase.PENDING, CandidatePhase.RATE_LIMITED) &&
            requestStartedAtMillis != null && pairingSession == null
        ) {
            invalid(StateInvariantCode.INVALID_CANDIDATE, "Pending candidate is missing its pairing session")
        }
        if (phase == CandidatePhase.RATE_LIMITED && pollNotBeforeMillis == null) {
            invalid(StateInvariantCode.INVALID_CANDIDATE, "Rate-limited pairing is missing its retry deadline")
        }
        stagedCredential?.validated(clientId, hostId)
        if ((phase == CandidatePhase.APPROVED_STAGED) != (stagedCredential != null)) {
            invalid(StateInvariantCode.INVALID_CANDIDATE, "Staged credential and candidate phase disagree")
        }
        return this
    }
}

data class StagedCredential(
    val clientId: String,
    val hostId: String,
    val bearerToken: String,
    val grantedScopes: List<String>,
    val wakeTarget: StoredWakeTarget,
    val pairedAtMillis: Long,
) {
    fun validated(expectedClientId: String, expectedHostId: String?): StagedCredential {
        if (clientId != expectedClientId || expectedHostId != null && hostId != expectedHostId) {
            invalid(StateInvariantCode.INVALID_IDENTITY, "Staged credential identity mismatch")
        }
        requireText(hostId, "host ID", 128)
        requireSecret(bearerToken, "bearer token", 512)
        validateScopes(grantedScopes)
        wakeTarget.validated()
        if (pairedAtMillis < 0) invalid(StateInvariantCode.INVALID_CANDIDATE, "Invalid staged timestamp")
        return this
    }
}

enum class JournalPhase(val wireValue: String, val unresolved: Boolean) {
    PREPARED("prepared", true),
    ACCEPTED("accepted", true),
    DISPATCHED("dispatched", true),
    OBSERVED_RETURN("observed_return", true),
    UNKNOWN("unknown", true),
    SUCCEEDED("succeeded", false),
    FAILED("failed", false),
}

/** Fixed host failure facts; UI guidance must not depend on a free-form host message. */
enum class JournalRejectionCode(val wireValue: String) {
    SAVE_CURRENT_UNSUPPORTED("save_current_unsupported"),
    PROFILE_NOT_FOUND("profile_not_found"),
    STALE_TARGET("stale_target"),
    MUTATION_CONFLICT("mutation_conflict"),
    BRIDGE_UNAVAILABLE("bridge_unavailable"),
    READBACK_NOT_CONFIRMED("readback_not_confirmed"),
    PREVIEW_NOT_FOUND("preview_not_found"),
    PREVIEW_EXPIRED("preview_expired"),
    FORBIDDEN("forbidden"),
    OPERATION_FAILED("operation_failed"),
    /** Display order: a connector on the host is ambiguous, so the host refused to target it. */
    AMBIGUOUS_DISPLAY("ambiguous_display_identity"),
    /** Display order: this host's gamescope-session format cannot be changed safely. */
    ORDER_UNSUPPORTED("gamescope_output_unsupported"),
    ORDER_UPDATE_FAILED("gamescope_output_update_failed"),
    /** Display order: the order is saved on the host, but the requested Gaming Mode restart failed. */
    ORDER_SAVED_RESTART_FAILED("order_saved_restart_failed"),
    OTHER_REJECTION("other_rejection"),
}

data class JournalEntry(
    val localGeneration: Long,
    val hostId: String,
    val clientId: String,
    val originalEndpoint: String,
    val originalCertificatePin: String,
    /** Null for entries migrated from before credential provenance was recorded. */
    val originalPairedAtMillis: Long?,
    val originalBearerSha256: String?,
    val method: String,
    val path: String,
    val requestId: String,
    val bodyBase64: String,
    val bodySha256: String,
    val phase: JournalPhase,
    val operationId: String?,
    val hostBootId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val resultSummary: String?,
    val acknowledged: Boolean,
    val rejectionCode: JournalRejectionCode? = null,
) {
    fun bodyBytes(): ByteArray = try {
        Base64.getDecoder().decode(bodyBase64)
    } catch (error: IllegalArgumentException) {
        throw StateInvariantException(StateInvariantCode.INVALID_JOURNAL, "Stored request body is not valid base64")
    }

    fun validated(): JournalEntry {
        if (localGeneration <= 0) invalid(StateInvariantCode.INVALID_JOURNAL, "Invalid local journal generation")
        requireText(hostId, "host ID", 128)
        requireId(clientId, "client ID")
        validateEndpoint(originalEndpoint)
        validatePin(originalCertificatePin)
        if ((originalPairedAtMillis == null) != (originalBearerSha256 == null) ||
            (originalPairedAtMillis != null && originalPairedAtMillis < 0) ||
            (originalBearerSha256 != null && !Regex("[0-9a-f]{64}").matches(originalBearerSha256))
        ) invalid(StateInvariantCode.INVALID_JOURNAL, "Invalid original pairing identity")
        if (method != HttpMethod.POST.wireValue) invalid(StateInvariantCode.INVALID_JOURNAL, "Only POST mutations are journaled")
        requireId(requestId, "request ID")
        val route = try {
            ApiRoute.match(HttpMethod.POST, path).route
        } catch (_: Exception) {
            invalid(StateInvariantCode.INVALID_JOURNAL, "Journal route is invalid")
        }
        if (route !in JOURNALED_ROUTES) invalid(StateInvariantCode.INVALID_JOURNAL, "Route is not journaled")
        val bytes = bodyBytes()
        if (bytes.isEmpty() || bytes.size > PersistentStateLimits.MAX_OPERATION_BODY_BYTES) {
            invalid(StateInvariantCode.INVALID_JOURNAL, "Journal request body exceeds its limit")
        }
        if (Base64.getEncoder().encodeToString(bytes) != bodyBase64 || !digestMatches(bytes, bodySha256)) {
            invalid(StateInvariantCode.INVALID_JOURNAL, "Journal request digest does not match its bytes")
        }
        val requestBody = try {
            BoundedJson.parseObject(bytes)
        } catch (_: JsonFormatException) {
            invalid(StateInvariantCode.INVALID_JOURNAL, "Journal request body is not valid JSON")
        }
        if (requestBody["request_id"].asString("request_id") != requestId) {
            invalid(StateInvariantCode.INVALID_JOURNAL, "Journal request ID does not match its body")
        }
        try {
            ApiRequest(HttpMethod.POST, path, requestBody, bearerToken = "journal-validation-token").validate()
        } catch (_: Exception) {
            invalid(StateInvariantCode.INVALID_JOURNAL, "Journal request does not match the pinned route schema")
        }
        operationId?.let { if (!isSafeId(it)) invalid(StateInvariantCode.INVALID_JOURNAL, "Operation ID is invalid") }
        hostBootId?.let { requireText(it, "host boot ID", 128) }
        if (createdAtMillis < 0 || updatedAtMillis < createdAtMillis) invalid(StateInvariantCode.INVALID_JOURNAL, "Journal timestamps are invalid")
        resultSummary?.let { requireText(it, "result summary", 1024, allowEmpty = true) }
        if (rejectionCode != null && phase != JournalPhase.FAILED) {
            invalid(StateInvariantCode.INVALID_JOURNAL, "Rejection code requires failed journal phase")
        }
        if (phase in setOf(JournalPhase.ACCEPTED, JournalPhase.DISPATCHED, JournalPhase.OBSERVED_RETURN, JournalPhase.SUCCEEDED) && operationId == null) {
            invalid(StateInvariantCode.INVALID_JOURNAL, "Journal phase needs an operation ID")
        }
        if (phase == JournalPhase.PREPARED && operationId != null) invalid(StateInvariantCode.INVALID_JOURNAL, "Prepared entry cannot have an operation ID")
        return this
    }

    companion object {
        private val JOURNALED_ROUTES = setOf(
            ApiRoute.PAIR_REVOKE_SELF,
            ApiRoute.POWER,
            ApiRoute.DISPLAY_ORDER_SET,
            ApiRoute.DISPLAY_ORDER_AUTOMATIC,
            ApiRoute.DISPLAY_PREVIEW,
            ApiRoute.DISPLAY_CONFIRM,
            ApiRoute.DISPLAY_RESTORE,
            ApiRoute.DISPLAY_SAVE_CURRENT,
            ApiRoute.SUNSHINE_RESTART,
        )

        fun create(
            localGeneration: Long,
            host: ActiveHost,
            request: ApiRequest,
            exactBodyBytes: ByteArray,
            nowMillis: Long,
        ): JournalEntry {
            val matched = request.validate()
            if (request.method != HttpMethod.POST || matched.route !in JOURNALED_ROUTES) {
                invalid(StateInvariantCode.INVALID_JOURNAL, "Request is not a journaled mutation")
            }
            if (request.bearerToken != host.bearerToken) invalid(StateInvariantCode.INVALID_IDENTITY, "Mutation credential does not match active host")
            if (exactBodyBytes.isEmpty() || exactBodyBytes.size > PersistentStateLimits.MAX_OPERATION_BODY_BYTES) {
                invalid(StateInvariantCode.INVALID_JOURNAL, "Mutation body exceeds the journal limit")
            }
            val encodedBody = request.body?.let(BoundedJson::encode)
            if (encodedBody == null || !encodedBody.contentEquals(exactBodyBytes)) {
                invalid(StateInvariantCode.INVALID_JOURNAL, "Serialized request bytes do not match the validated request")
            }
            val requestId = try {
                request.body.fields["request_id"].asString("request_id")
            } catch (_: Exception) {
                invalid(StateInvariantCode.INVALID_JOURNAL, "Mutation request ID is missing")
            }
            return JournalEntry(
                localGeneration = localGeneration,
                hostId = host.hostId,
                clientId = host.clientId,
                originalEndpoint = host.endpoint,
                originalCertificatePin = host.certificatePin,
                originalPairedAtMillis = host.pairedAtMillis,
                originalBearerSha256 = sha256Hex(host.bearerToken.toByteArray(Charsets.UTF_8)),
                method = HttpMethod.POST.wireValue,
                path = request.path,
                requestId = requestId,
                bodyBase64 = Base64.getEncoder().encodeToString(exactBodyBytes),
                bodySha256 = sha256Hex(exactBodyBytes),
                phase = JournalPhase.PREPARED,
                operationId = null,
                hostBootId = host.lastObservedBootId,
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
                resultSummary = null,
                acknowledged = false,
            ).validated()
        }
    }
}

private fun validateEndpoint(value: String) {
    try {
        HostEndpoint.parse(value)
    } catch (_: Exception) {
        invalid(StateInvariantCode.INVALID_FIELD, "Invalid host endpoint")
    }
}

private fun validatePin(value: String) {
    if (!Regex("sha256:[0-9a-f]{64}").matches(value)) invalid(StateInvariantCode.INVALID_FIELD, "Invalid certificate pin")
}

private fun validateScopes(values: List<String>) {
    val allowed = setOf("status.read", "power.control", "display.control")
    if (values.isEmpty() || values.size > allowed.size || values.distinct().size != values.size || values.any { it !in allowed }) {
        invalid(StateInvariantCode.INVALID_FIELD, "Invalid granted scopes")
    }
}

private fun requireId(value: String, label: String, max: Int = 128) {
    if (value.length > max || !isSafeId(value)) invalid(StateInvariantCode.INVALID_FIELD, "Invalid $label")
}

private fun isSafeId(value: String): Boolean = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}").matches(value)

private fun requireSecret(value: String, label: String, max: Int) {
    if (value.isEmpty() || value.length > max || !isSafeSecret(value)) invalid(StateInvariantCode.INVALID_FIELD, "Invalid $label")
}

private fun isSafeSecret(value: String): Boolean = value.none { it == '\r' || it == '\n' || it == '\u0000' || it.code < 0x20 || it.code == 0x7f }

private fun requireText(value: String, label: String, max: Int, allowEmpty: Boolean = false) {
    if ((!allowEmpty && value.isEmpty()) || value.length > max || !isSafeSecret(value)) invalid(StateInvariantCode.INVALID_FIELD, "Invalid $label")
}

private fun invalid(code: StateInvariantCode, message: String): Nothing = throw StateInvariantException(code, message)

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun digestMatches(bytes: ByteArray, expected: String): Boolean {
    if (!Regex("[0-9a-f]{64}").matches(expected)) return false
    val actual = sha256Hex(bytes).toByteArray(Charsets.US_ASCII)
    return MessageDigest.isEqual(actual, expected.toByteArray(Charsets.US_ASCII))
}
