package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.JsonFormatException
import io.github.tuthan.steamoscompanion.core.protocol.JsonLimits
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.asArray
import io.github.tuthan.steamoscompanion.core.protocol.asLong
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.asString

/** Strict versioned serializer for the decrypted state payload. */
object PersistentStateCodec {
    private val limits = JsonLimits(
        maxBytes = PersistentStateLimits.MAX_PLAINTEXT_BYTES,
        maxDepth = 20,
        maxStringCodePoints = 8_192,
        maxContainerItems = 1_024,
        maxNodes = 20_000,
        maxNumberChars = 32,
    )

    fun encode(state: PersistentState): ByteArray {
        val bytes = BoundedJson.encode(toJson(state.validated()))
        if (bytes.size > PersistentStateLimits.MAX_PLAINTEXT_BYTES) {
            throw StateInvariantException(StateInvariantCode.INVALID_FIELD, "Saved state exceeds 64 KiB")
        }
        return bytes
    }

    fun parseDocument(bytes: ByteArray): JsonValue.ObjectValue {
        if (bytes.size > PersistentStateLimits.MAX_PLAINTEXT_BYTES) {
            throw JsonFormatException("Saved state exceeds 64 KiB")
        }
        return BoundedJson.parseObject(bytes, limits)
    }

    fun schemaVersion(document: JsonValue.ObjectValue): Long = document["schema_version"].asLong("schema_version")

    fun decodeCurrent(document: JsonValue.ObjectValue): PersistentState {
        if (schemaVersion(document) != PersistentStateLimits.CURRENT_SCHEMA_VERSION.toLong()) {
            throw JsonFormatException("State schema is not version 1")
        }
        document.exactKeys("schema_version", "revision", "pairing_generation", "active_host", "candidate", "journal")
        val state = PersistentState(
            schemaVersion = 1,
            revision = document["revision"].asLong("revision"),
            pairingGeneration = document["pairing_generation"].asLong("pairing_generation"),
            activeHost = document["active_host"].nullableObject("active_host")?.decodeActiveHost(),
            candidate = document["candidate"].nullableObject("candidate")?.decodeCandidate(),
            journal = document["journal"].asArray("journal").mapIndexed { index, value -> value.asObject("journal[$index]").decodeJournalEntry() },
        )
        return try {
            state.validated()
        } catch (error: StateInvariantException) {
            throw JsonFormatException("Saved state failed validation: ${error.code}", error)
        }
    }

    /** The first shipped schema has no persisted predecessor; this empty v0 fixture tests the migration path. */
    fun migrateKnownDocument(document: JsonValue.ObjectValue): JsonValue.ObjectValue? {
        if (schemaVersion(document) != 0L) return null
        document.exactKeys("schema_version", "revision", "active_host", "candidate", "journal")
        if (document["active_host"] != JsonValue.NullValue || document["candidate"] != JsonValue.NullValue ||
            document["journal"].asArray("journal").isNotEmpty()
        ) {
            throw JsonFormatException("Unknown data in legacy state schema")
        }
        return JsonValue.ObjectValue(
            linkedMapOf(
                "schema_version" to number(1),
                "revision" to document["revision"].asLong("revision").asJson(),
                "pairing_generation" to number(0),
                "active_host" to JsonValue.NullValue,
                "candidate" to JsonValue.NullValue,
                "journal" to JsonValue.ArrayValue(emptyList()),
            ),
        )
    }

    private fun toJson(state: PersistentState): JsonValue.ObjectValue = obj(
        "schema_version" to number(state.schemaVersion),
        "revision" to state.revision.asJson(),
        "pairing_generation" to state.pairingGeneration.asJson(),
        "active_host" to (state.activeHost?.let(::encodeActiveHost) ?: JsonValue.NullValue),
        "candidate" to (state.candidate?.let(::encodeCandidate) ?: JsonValue.NullValue),
        "journal" to array(state.journal.map(::encodeJournalEntry)),
    )

    private fun encodeActiveHost(host: ActiveHost) = obj(
        "host_id" to string(host.hostId),
        "client_id" to string(host.clientId),
        "endpoint" to string(host.endpoint),
        "certificate_pin" to string(host.certificatePin),
        "bearer_token" to string(host.bearerToken),
        "granted_scopes" to array(host.grantedScopes.map(::string)),
        "wake_target" to encodeWakeTarget(host.wakeTarget),
        "paired_at_ms" to host.pairedAtMillis.asJson(),
    )

    private fun encodeWakeTarget(target: StoredWakeTarget) = obj(
        "available" to bool(target.available),
        "mac" to target.mac.asJsonOrNull(),
        "interface" to target.interfaceName.asJsonOrNull(),
        "source_address" to target.sourceAddress.asJsonOrNull(),
        "reason" to target.reason.asJsonOrNull(),
    )

    private fun encodeCandidate(candidate: PairingCandidate) = obj(
        "generation" to candidate.generation.asJson(),
        "variant" to string(candidate.variant.wireValue),
        "client_id" to string(candidate.clientId),
        "client_name" to string(candidate.clientName),
        "requested_scopes" to array(candidate.requestedScopes.map(::string)),
        "endpoint" to string(candidate.endpoint),
        "certificate_pin" to string(candidate.certificatePin),
        "host_id" to candidate.hostId.asJsonOrNull(),
        "created_at_ms" to candidate.createdAtMillis.asJson(),
        "expires_at_ms" to (candidate.expiresAtMillis?.asJson() ?: JsonValue.NullValue),
        "phase" to string(candidate.phase.wireValue),
        "verification_nonce" to candidate.verificationNonce.asJsonOrNull(),
        "legacy_pairing_code" to candidate.legacyPairingCode.asJsonOrNull(),
        "payload_id" to candidate.payloadId.asJsonOrNull(),
        "payload_secret" to candidate.payloadSecret.asJsonOrNull(),
        "pairing_id" to candidate.pairingId.asJsonOrNull(),
        "pairing_session" to candidate.pairingSession.asJsonOrNull(),
        "staged_credential" to (candidate.stagedCredential?.let(::encodeStagedCredential) ?: JsonValue.NullValue),
    )

    private fun encodeStagedCredential(credential: StagedCredential) = obj(
        "client_id" to string(credential.clientId),
        "host_id" to string(credential.hostId),
        "bearer_token" to string(credential.bearerToken),
        "granted_scopes" to array(credential.grantedScopes.map(::string)),
        "wake_target" to encodeWakeTarget(credential.wakeTarget),
        "paired_at_ms" to credential.pairedAtMillis.asJson(),
    )

    private fun encodeJournalEntry(entry: JournalEntry) = obj(
        "local_generation" to entry.localGeneration.asJson(),
        "host_id" to string(entry.hostId),
        "client_id" to string(entry.clientId),
        "original_endpoint" to string(entry.originalEndpoint),
        "original_certificate_pin" to string(entry.originalCertificatePin),
        "method" to string(entry.method),
        "path" to string(entry.path),
        "request_id" to string(entry.requestId),
        "body_base64" to string(entry.bodyBase64),
        "body_sha256" to string(entry.bodySha256),
        "phase" to string(entry.phase.wireValue),
        "operation_id" to entry.operationId.asJsonOrNull(),
        "host_boot_id" to entry.hostBootId.asJsonOrNull(),
        "created_at_ms" to entry.createdAtMillis.asJson(),
        "updated_at_ms" to entry.updatedAtMillis.asJson(),
        "result_summary" to entry.resultSummary.asJsonOrNull(),
        "acknowledged" to bool(entry.acknowledged),
    )

    private fun JsonValue.ObjectValue.decodeActiveHost(): ActiveHost {
        exactKeys("host_id", "client_id", "endpoint", "certificate_pin", "bearer_token", "granted_scopes", "wake_target", "paired_at_ms")
        return ActiveHost(
            hostId = this["host_id"].asString("host_id"),
            clientId = this["client_id"].asString("client_id"),
            endpoint = this["endpoint"].asString("endpoint"),
            certificatePin = this["certificate_pin"].asString("certificate_pin"),
            bearerToken = this["bearer_token"].asString("bearer_token"),
            grantedScopes = this["granted_scopes"].asArray("granted_scopes").map { it.asString("scope") },
            wakeTarget = this["wake_target"].asObject("wake_target").decodeWakeTarget(),
            pairedAtMillis = this["paired_at_ms"].asLong("paired_at_ms"),
        )
    }

    private fun JsonValue.ObjectValue.decodeWakeTarget(): StoredWakeTarget {
        exactKeys("available", "mac", "interface", "source_address", "reason")
        return StoredWakeTarget(
            available = this["available"].asBoolean("available"),
            mac = this["mac"].nullableString("mac"),
            interfaceName = this["interface"].nullableString("interface"),
            sourceAddress = this["source_address"].nullableString("source_address"),
            reason = this["reason"].nullableString("reason"),
        )
    }

    private fun JsonValue.ObjectValue.decodeCandidate(): PairingCandidate {
        exactKeys(
            "generation", "variant", "client_id", "client_name", "requested_scopes", "endpoint", "certificate_pin", "host_id",
            "created_at_ms", "expires_at_ms", "phase", "verification_nonce", "legacy_pairing_code", "payload_id", "payload_secret",
            "pairing_id", "pairing_session", "staged_credential",
        )
        val variant = PairingVariant.entries.firstOrNull { it.wireValue == this["variant"].asString("variant") }
            ?: throw JsonFormatException("Unknown candidate variant")
        val phase = CandidatePhase.entries.firstOrNull { it.wireValue == this["phase"].asString("phase") }
            ?: throw JsonFormatException("Unknown candidate phase")
        return PairingCandidate(
            generation = this["generation"].asLong("generation"),
            variant = variant,
            clientId = this["client_id"].asString("client_id"),
            clientName = this["client_name"].asString("client_name"),
            requestedScopes = this["requested_scopes"].asArray("requested_scopes").map { it.asString("scope") },
            endpoint = this["endpoint"].asString("endpoint"),
            certificatePin = this["certificate_pin"].asString("certificate_pin"),
            hostId = this["host_id"].nullableString("host_id"),
            createdAtMillis = this["created_at_ms"].asLong("created_at_ms"),
            expiresAtMillis = this["expires_at_ms"].nullableLong("expires_at_ms"),
            phase = phase,
            verificationNonce = this["verification_nonce"].nullableString("verification_nonce"),
            legacyPairingCode = this["legacy_pairing_code"].nullableString("legacy_pairing_code"),
            payloadId = this["payload_id"].nullableString("payload_id"),
            payloadSecret = this["payload_secret"].nullableString("payload_secret"),
            pairingId = this["pairing_id"].nullableString("pairing_id"),
            pairingSession = this["pairing_session"].nullableString("pairing_session"),
            stagedCredential = this["staged_credential"].nullableObject("staged_credential")?.decodeStagedCredential(),
        )
    }

    private fun JsonValue.ObjectValue.decodeStagedCredential(): StagedCredential {
        exactKeys("client_id", "host_id", "bearer_token", "granted_scopes", "wake_target", "paired_at_ms")
        return StagedCredential(
            clientId = this["client_id"].asString("client_id"),
            hostId = this["host_id"].asString("host_id"),
            bearerToken = this["bearer_token"].asString("bearer_token"),
            grantedScopes = this["granted_scopes"].asArray("granted_scopes").map { it.asString("scope") },
            wakeTarget = this["wake_target"].asObject("wake_target").decodeWakeTarget(),
            pairedAtMillis = this["paired_at_ms"].asLong("paired_at_ms"),
        )
    }

    private fun JsonValue.ObjectValue.decodeJournalEntry(): JournalEntry {
        exactKeys(
            "local_generation", "host_id", "client_id", "original_endpoint", "original_certificate_pin", "method", "path", "request_id",
            "body_base64", "body_sha256", "phase", "operation_id", "host_boot_id", "created_at_ms", "updated_at_ms", "result_summary", "acknowledged",
        )
        val phase = JournalPhase.entries.firstOrNull { it.wireValue == this["phase"].asString("phase") }
            ?: throw JsonFormatException("Unknown journal phase")
        return JournalEntry(
            localGeneration = this["local_generation"].asLong("local_generation"),
            hostId = this["host_id"].asString("host_id"),
            clientId = this["client_id"].asString("client_id"),
            originalEndpoint = this["original_endpoint"].asString("original_endpoint"),
            originalCertificatePin = this["original_certificate_pin"].asString("original_certificate_pin"),
            method = this["method"].asString("method"),
            path = this["path"].asString("path"),
            requestId = this["request_id"].asString("request_id"),
            bodyBase64 = this["body_base64"].asString("body_base64"),
            bodySha256 = this["body_sha256"].asString("body_sha256"),
            phase = phase,
            operationId = this["operation_id"].nullableString("operation_id"),
            hostBootId = this["host_boot_id"].nullableString("host_boot_id"),
            createdAtMillis = this["created_at_ms"].asLong("created_at_ms"),
            updatedAtMillis = this["updated_at_ms"].asLong("updated_at_ms"),
            resultSummary = this["result_summary"].nullableString("result_summary"),
            acknowledged = this["acknowledged"].asBoolean("acknowledged"),
        )
    }

    private fun JsonValue.ObjectValue.exactKeys(vararg keys: String) {
        if (fields.keys != keys.toSet()) throw JsonFormatException("Saved state object has missing or unknown fields")
    }

    private fun JsonValue?.nullableObject(field: String): JsonValue.ObjectValue? = when (this) {
        null, JsonValue.NullValue -> null
        else -> asObject(field)
    }

    private fun JsonValue?.nullableString(field: String): String? = when (this) {
        null, JsonValue.NullValue -> null
        else -> asString(field)
    }

    private fun JsonValue?.nullableLong(field: String): Long? = when (this) {
        null, JsonValue.NullValue -> null
        else -> asLong(field)
    }

    private fun JsonValue?.asBoolean(field: String): Boolean =
        (this as? JsonValue.BooleanValue)?.value ?: throw JsonFormatException("'$field' must be boolean")

    private fun String?.asJsonOrNull(): JsonValue = this?.let(::string) ?: JsonValue.NullValue
    private fun obj(vararg values: Pair<String, JsonValue>) = JsonValue.ObjectValue(linkedMapOf(*values))
    private fun array(values: List<JsonValue>) = JsonValue.ArrayValue(values)
    private fun string(value: String) = JsonValue.StringValue(value)
    private fun bool(value: Boolean) = JsonValue.BooleanValue(value)
    private fun number(value: Int) = JsonValue.NumberValue(value.toString())
    private fun Int.asJson() = JsonValue.NumberValue(toString())
    private fun Long.asJson() = JsonValue.NumberValue(toString())
}
