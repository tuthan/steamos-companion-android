package io.github.tuthan.steamoscompanion.core.protocol

import java.security.MessageDigest

data class EncodedRequest(
    val route: MatchedRoute,
    val body: ByteArray,
)

/** Route-aware serialization and decoding for the pinned v1 host contract. */
object ProtocolCodec {
    fun encodeRequest(request: ApiRequest): EncodedRequest {
        val route = request.validate()
        val bytes = request.body?.let(BoundedJson::encode) ?: ByteArray(0)
        if (bytes.size > MAX_REQUEST_BODY_BYTES) throw RequestValidationException("Request body exceeds the protocol limit")
        return EncodedRequest(route, bytes)
    }

    fun decodeResponse(
        route: ApiRoute,
        response: HttpResponse,
        expectedOperationId: String? = null,
    ): ApiReply {
        if (response.status !in 200..599) throw JsonFormatException("Informational or invalid HTTP status is not supported")
        if (response.status in 300..399) throw JsonFormatException("Redirect responses are not supported")
        if (response.body.size > route.maxResponseBytes) throw JsonFormatException("Response body exceeds the route limit")
        val body = BoundedJson.parseObject(response.body, JsonLimits(maxBytes = route.maxResponseBytes))
        return if (response.status in 200..299) {
            val version = body["protocol_version"].asLong("protocol_version")
            if (version < 0) throw JsonFormatException("protocol_version must be nonnegative")
            if (version != SUPPORTED_PROTOCOL_VERSION.toLong()) {
                ApiReply.UnsupportedVersion(version)
            } else {
                if (body["error"] != null) throw JsonFormatException("Success response contains an error envelope")
                val payload = parsePayload(route, body)
                if (route == ApiRoute.OPERATION && expectedOperationId != null) {
                    val operation = (payload as VersionedPayload.Operation).operation
                    if (operation.id != expectedOperationId) {
                        throw JsonFormatException("Operation response ID does not match the requested operation")
                    }
                }
                ApiReply.Versioned(SUPPORTED_PROTOCOL_VERSION, payload)
            }
        } else {
            val error = body["error"].asString("error")
            val message = body["message"].asString("message")
            if (!ERROR_CODE.matches(error) || message.isEmpty() || message.length > MAX_ERROR_MESSAGE_CHARS) {
                throw JsonFormatException("Invalid error envelope")
            }
            ApiReply.Rejected(response.status, error, message)
        }
    }

    fun parseOperation(value: JsonValue.ObjectValue): OperationSummary {
        val id = value["id"].asString("operation.id")
        val requestId = value["request_id"].asString("operation.request_id")
        val stateText = value["state"].asString("operation.state")
        val state = when (stateText) {
            "accepted" -> WireValue.Known(OperationState.ACCEPTED)
            "dispatched" -> WireValue.Known(OperationState.DISPATCHED)
            "observed_return" -> WireValue.Known(OperationState.OBSERVED_RETURN)
            "succeeded" -> WireValue.Known(OperationState.SUCCEEDED)
            "failed" -> WireValue.Known(OperationState.FAILED)
            "unknown" -> WireValue.Known(OperationState.UNKNOWN)
            else -> WireValue.Unknown(stateText)
        }
        return OperationSummary(
            id = bounded(id, "operation.id", 128),
            requestId = bounded(requestId, "operation.request_id", 128),
            state = state,
            kind = bounded(value["kind"].asString("operation.kind"), "operation.kind", 128),
            createdAt = bounded(value["created_at"].asString("operation.created_at"), "operation.created_at", 64),
            updatedAt = bounded(value["updated_at"].asString("operation.updated_at"), "operation.updated_at", 64),
            outcome = value["outcome"].nullableString("operation.outcome", 128),
            reason = value["reason"].nullableString("operation.reason", 256),
        )
    }

    private fun parsePayload(route: ApiRoute, body: JsonValue.ObjectValue): VersionedPayload = when (route) {
        ApiRoute.DISCOVERY -> parseDiscovery(body)
        ApiRoute.PAIR_REQUEST -> parsePairing(body)
        ApiRoute.PAIR_CANCEL -> parsePairing(body)
        ApiRoute.STATUS -> parseStatus(body)
        ApiRoute.DISPLAY_OUTPUTS -> parseDisplayOutputs(body)
        ApiRoute.DISPLAY_ORDER -> VersionedPayload.DisplayOrder(parseDisplayOrder(body), body)
        ApiRoute.OPERATION -> VersionedPayload.Operation(parseOperation(body["operation"].asObject("operation")))
        else -> VersionedPayload.Mutation(
            operation = (body["operation"] as? JsonValue.ObjectValue)?.let(::parseOperation),
            body = body,
            profile = if (route == ApiRoute.DISPLAY_CONFIRM || route == ApiRoute.DISPLAY_SAVE_CURRENT) {
                body["profile"].takeOptionalObject("profile")?.let(::parseRecoveryProfile)
            } else null,
            preview = if (route == ApiRoute.DISPLAY_PREVIEW) {
                body["preview"].takeOptionalObject("preview")?.let(::parseDisplayPreview)
            } else null,
        ).also {
            if (it.operation == null) throw JsonFormatException("Accepted mutation response has no operation")
            val operationBody = body["operation"] as JsonValue.ObjectValue
            if (it.preview != null && it.preview.id != it.operation.id) {
                throw JsonFormatException("Preview ID does not match the returned operation")
            }
            if (it.preview != null) {
                val declaredPreviewId = operationBody["preview_id"].nullableString("operation.preview_id", 128)
                if (declaredPreviewId != null && declaredPreviewId != it.preview.id) {
                    throw JsonFormatException("Preview ID conflicts with the operation preview ID")
                }
            }
            if (it.profile != null) {
                val target = operationBody["target"].takeOptionalObject("operation.target")
                val targetProfileId = target?.get("profile_id").nullableString("operation.target.profile_id", 128)
                if (targetProfileId != null && targetProfileId != it.profile.id) {
                    throw JsonFormatException("Profile ID does not match the returned operation target")
                }
            }
        }
    }

    private fun parseDiscovery(body: JsonValue.ObjectValue): VersionedPayload.Discovery {
        val service = bounded(body["service"].asString("service"), "service", 64)
        val version = body["protocol_version"].asLong("protocol_version")
        val hostId = bounded(body["host_id"].asString("host_id"), "host_id", 128)
        val fingerprint = bounded(body["certificate_fingerprint"].asString("certificate_fingerprint"), "certificate_fingerprint", 71)
        if (service != DISCOVERY_SERVICE || version != SUPPORTED_PROTOCOL_VERSION.toLong() || hostId.isEmpty()) {
            throw JsonFormatException("Discovery service identity is invalid")
        }
        if (!FINGERPRINT.matches(fingerprint)) throw JsonFormatException("Discovery fingerprint is invalid")
        return VersionedPayload.Discovery(
            DiscoveryAnnouncement(service, version.toInt(), hostId, fingerprint),
            body["name"].nullableString("name", 128),
        )
    }

    private fun parsePairing(body: JsonValue.ObjectValue): VersionedPayload.Pairing {
        val stateText = bounded(body["state"].asString("state"), "state", 32)
        val state = when (stateText) {
            "pending" -> WireValue.Known(PairingState.PENDING)
            "approved" -> WireValue.Known(PairingState.APPROVED)
            "rejected" -> WireValue.Known(PairingState.REJECTED)
            "cancelled" -> WireValue.Known(PairingState.CANCELLED)
            "expired" -> WireValue.Known(PairingState.EXPIRED)
            else -> WireValue.Unknown(stateText)
        }
        val pairingId = body["pairing_id"].nullableString("pairing_id", 128)
        val hostId = body["host_id"].nullableString("host_id", 128)
        val expiresAt = body["expires_at"] as? JsonValue.NumberValue
        if (body["expires_at"] != null && expiresAt == null) throw JsonFormatException("'expires_at' must be numeric")
        val session = body["pairing_session"].nullableString("pairing_session", 256)
        if (session != null && !PAIRING_SESSION.matches(session)) {
            throw JsonFormatException("Pairing session is invalid")
        }
        val credential = (body["credential"] as? JsonValue.ObjectValue)?.let { item ->
            val scopes = item["scopes"].asArray("credential.scopes").map { bounded(it.asString("scope"), "scope", 64) }
            if (scopes.size > 3 || scopes.distinct().size != scopes.size || scopes.any { it !in setOf("status.read", "power.control", "display.control") }) {
                throw JsonFormatException("Credential scopes are invalid")
            }
            PairedCredential(
                clientId = bounded(item["client_id"].asString("credential.client_id"), "credential.client_id", 128),
                token = bounded(item["token"].asString("credential.token"), "credential.token", 512),
                scopes = scopes,
                hostId = bounded(item["host_id"].asString("credential.host_id"), "credential.host_id", 128),
            )
        }
        val wakeTarget = (body["wake_target"] as? JsonValue.ObjectValue)?.let { item ->
            val available = (item["available"] as? JsonValue.BooleanValue)?.value
                ?: throw JsonFormatException("'wake_target.available' must be boolean")
            val mac = item["mac"].nullableString("wake_target.mac", 32)
            if (available && mac?.let { Regex("[0-9a-f]{12}").matches(it) } != true) {
                throw JsonFormatException("Available pairing wake target needs a MAC address")
            }
            PairingWakeTarget(
                available = available,
                mac = mac,
                interfaceName = item["interface"].nullableString("wake_target.interface", 32),
                sourceAddress = item["source_address"].nullableString("wake_target.source_address", 64),
                reason = item["reason"].nullableString("wake_target.reason", 256),
            )
        }
        if (stateText == "pending" && (pairingId == null || expiresAt == null)) {
            throw JsonFormatException("Pending pairing response is incomplete")
        }
        if (stateText == "approved" &&
            (pairingId == null || hostId == null || credential == null || credential.hostId != hostId || wakeTarget == null)
        ) {
            throw JsonFormatException("Approved pairing response is incomplete or inconsistent")
        }
        return VersionedPayload.Pairing(state, pairingId, hostId, expiresAt, session, credential, wakeTarget, body)
    }

    private fun parseStatus(body: JsonValue.ObjectValue): VersionedPayload.Status {
        val hostId = bounded(body["host_id"].asString("host_id"), "host_id", 128)
        val bootId = bounded(body["boot_id"].asString("boot_id"), "boot_id", 128)
        val bridgeText = bounded(body["steam_bridge"].asString("steam_bridge"), "steam_bridge", 64)
        val bridge = when (bridgeText) {
            "ready" -> WireValue.Known(SteamBridgeState.READY)
            "unavailable" -> WireValue.Known(SteamBridgeState.UNAVAILABLE)
            else -> WireValue.Unknown(bridgeText)
        }
        val capabilityObject = body["capabilities"].asObject("capabilities")
        val capabilityNames = setOf("suspend", "restart", "shutdown", "display_rescue", "sunshine_restart")
        if (!capabilityObject.fields.keys.containsAll(capabilityNames)) throw JsonFormatException("Capabilities are incomplete")
        val capabilities = capabilityObject.fields.mapValues { (name, value) ->
            val text = bounded(value.asString("capabilities.$name"), "capabilities.$name", 64)
            val known = when (text) {
                "available" -> CapabilityState.AVAILABLE
                "unavailable" -> CapabilityState.UNAVAILABLE
                "unsupported" -> CapabilityState.UNSUPPORTED
                "unverified" -> CapabilityState.UNVERIFIED
                "disabled" -> CapabilityState.DISABLED
                else -> null
            }
            if (known == null) WireValue.Unknown(text) else WireValue.Known(known)
        }
        val sunshine = body["sunshine"].asObject("sunshine")
        val sunshineText = bounded(sunshine["state"].asString("sunshine.state"), "sunshine.state", 64)
        val sunshineState = when (sunshineText) {
            "disabled" -> WireValue.Known(SunshineState.DISABLED)
            "unavailable" -> WireValue.Known(SunshineState.UNAVAILABLE)
            "unknown" -> WireValue.Known(SunshineState.UNKNOWN)
            "running" -> WireValue.Known(SunshineState.RUNNING)
            "stopped" -> WireValue.Known(SunshineState.STOPPED)
            "restarting" -> WireValue.Known(SunshineState.RESTARTING)
            else -> WireValue.Unknown(sunshineText)
        }
        return VersionedPayload.Status(hostId, bootId, bridge, capabilities, sunshineState, body)
    }

    private fun parseDisplayOutputs(body: JsonValue.ObjectValue): VersionedPayload.DisplayOutputs {
        val legacyShape = body["available"] == null
        val outputsValue = body["outputs"].asArray("outputs")
        val available = if (legacyShape) outputsValue.isNotEmpty() else {
            (body["available"] as? JsonValue.BooleanValue)?.value
                ?: throw JsonFormatException("'available' must be boolean")
        }
        val generation = body["generation"]?.let { if (it == JsonValue.NullValue) null else nonnegativeLong(it, "generation") }
        if (outputsValue.size > 8) throw JsonFormatException("Too many display outputs")
        val outputs = outputsValue.mapIndexed { index, value ->
            val output = value.asObject("outputs[$index]")
            val id = bounded(output["id"].asString("output.id"), "output.id", 128)
            val name = output["name"].nullableString("output.name", 256)
            output["description"].nullableString("output.description", 256)
            val isInternal = output["is_internal"].nullableBoolean("output.is_internal")
            val currentMode = output["current_mode_id"].nullableString("output.current_mode_id", 128)
            val outputGeneration = nonnegativeLong(output["generation"] ?: throw JsonFormatException("Output generation is missing"), "output.generation")
            val modes = output["modes"].asArray("output.modes")
            if (modes.size > 256) throw JsonFormatException("Too many display modes")
            val parsedModes = modes.mapIndexed { modeIndex, modeValue ->
                parseDisplayMode(modeValue.asObject("modes[$modeIndex]"))
            }
            if (parsedModes.map { it.id }.distinct().size != parsedModes.size) throw JsonFormatException("Duplicate display mode ID")
            if (output["rgb_range"] != null && nonnegativeLong(output["rgb_range"]!!, "output.rgb_range") !in 0..2) {
                throw JsonFormatException("Invalid output rgb_range")
            }
            DisplayOutputSummary(id, name, currentMode, outputGeneration, modes.size, isInternal, parsedModes)
        }
        if (available && generation == null && !legacyShape) throw JsonFormatException("Available display outputs need a generation")
        val profiles = body["profiles"]?.let { value ->
            val items = value.asArray("profiles")
            if (items.size > 8) throw JsonFormatException("Too many recovery profiles")
            items.mapIndexed { index, item -> parseRecoveryProfile(item.asObject("profiles[$index]")) }
        }.orEmpty()
        val preview = body["preview"].takeOptionalObject("preview")?.let(::parseDisplayPreview)
        return VersionedPayload.DisplayOutputs(available, generation, outputs, legacyShape, body, profiles, preview)
    }

    private fun parseRecoveryProfile(profile: JsonValue.ObjectValue): DisplayRecoveryProfile = DisplayRecoveryProfile(
        bounded(profile["id"].asString("profile.id"), "profile.id", 128),
        bounded(profile["output_id"].asString("profile.output_id"), "profile.output_id", 128),
        parseDisplayMode(profile["mode"].asObject("profile.mode")),
    )

    private fun parseDisplayPreview(item: JsonValue.ObjectValue): DisplayPreviewSummary = DisplayPreviewSummary(
        bounded(item["preview_id"].asString("preview.preview_id"), "preview.preview_id", 128),
        bounded(item["output_id"].asString("preview.output_id"), "preview.output_id", 128),
        nonnegativeLong(item["generation"] ?: throw JsonFormatException("Preview generation is missing"), "preview.generation"),
        parseDisplayMode(item["baseline_mode"].asObject("preview.baseline_mode")),
        parseDisplayMode(item["target_mode"].asObject("preview.target_mode")),
        item["deadline"].nullableNumber("preview.deadline")
            ?: throw JsonFormatException("Preview deadline is missing"),
    )

    private fun JsonValue?.takeOptionalObject(name: String): JsonValue.ObjectValue? = when (this) {
        null, JsonValue.NullValue -> null
        else -> asObject(name)
    }

    private fun parseDisplayMode(mode: JsonValue.ObjectValue): DisplayModeSummary = DisplayModeSummary(
        bounded(mode["id"].asString("mode.id"), "mode.id", 128),
        positiveLong(mode["width"] ?: throw JsonFormatException("Mode width is missing"), "mode.width", 16384),
        positiveLong(mode["height"] ?: throw JsonFormatException("Mode height is missing"), "mode.height", 16384),
        mode["refresh_hz"].nullableNumber("mode.refresh_hz"),
    )

    private fun parseDisplayOrder(body: JsonValue.ObjectValue): DisplayOrderSummary {
        val value = body["display_order"].asObject("display_order")
        fun bool(name: String) = (value[name] as? JsonValue.BooleanValue)?.value
            ?: throw JsonFormatException("'display_order.$name' must be boolean")
        val available = bool("available")
        val generation = value["generation"]?.let { if (it == JsonValue.NullValue) null else nonnegativeLong(it, "display_order.generation") }
        val outputKeys = value["output_keys"].asArray("display_order.output_keys").map { bounded(it.asString("output_key"), "output_key", 128) }
        val savedKeys = value["saved_output_keys"].asArray("display_order.saved_output_keys").map { bounded(it.asString("output_key"), "output_key", 128) }
        if (outputKeys.size > 16 || savedKeys.size > 16 || outputKeys.distinct().size != outputKeys.size || savedKeys.distinct().size != savedKeys.size) {
            throw JsonFormatException("Display-order keys are invalid")
        }
        val outputs = value["outputs"].asArray("display_order.outputs")
        if (outputs.size > 16) throw JsonFormatException("Too many display-order outputs")
        outputs.forEachIndexed { index, outputValue ->
            val output = outputValue.asObject("display_order.outputs[$index]")
            bounded(output["output_key"].asString("output_key"), "output_key", 128)
            output["display_name"].nullableString("display_name", 256)
            output["connector"].nullableString("connector", 128)
            if (output["connected"] !is JsonValue.BooleanValue) throw JsonFormatException("'connected' must be boolean")
            output["active"].nullableBoolean("active")
        }
        if (available && generation == null) throw JsonFormatException("Available display order needs a generation")
        return DisplayOrderSummary(
            available, generation, outputKeys, savedKeys,
            bool("restart_required"), bool("restart_available"), bool("unsupported"), bool("stale"), bool("ambiguous"),
        )
    }

    private fun bounded(value: String, field: String, maxLength: Int): String {
        if (value.isEmpty() || value.length > maxLength || value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            throw JsonFormatException("Invalid $field")
        }
        return value
    }

    private fun JsonValue?.nullableString(field: String, maxLength: Int): String? = when (this) {
        null, JsonValue.NullValue -> null
        else -> bounded(asString(field), field, maxLength)
    }

    private fun JsonValue?.nullableBoolean(field: String): Boolean? = when (this) {
        null, JsonValue.NullValue -> null
        is JsonValue.BooleanValue -> value
        else -> throw JsonFormatException("'$field' must be boolean or null")
    }

    private fun JsonValue?.nullableNumber(field: String): String? = when (this) {
        null, JsonValue.NullValue -> null
        is JsonValue.NumberValue -> lexical
        else -> throw JsonFormatException("'$field' must be numeric or null")
    }

    private fun nonnegativeLong(value: JsonValue, field: String): Long = value.asLong(field).also {
        if (it < 0) throw JsonFormatException("'$field' must be nonnegative")
    }

    private fun positiveLong(value: JsonValue, field: String, max: Long): Long = nonnegativeLong(value, field).also {
        if (it !in 1..max) throw JsonFormatException("'$field' is out of range")
    }

    private val ERROR_CODE = Regex("[a-z][a-z0-9_]{0,63}")
    private val FINGERPRINT = Regex("sha256:[0-9a-f]{64}")
    private val PAIRING_SESSION = Regex("[A-Za-z0-9_-]{16,256}")

    const val SUPPORTED_PROTOCOL_VERSION = 1
    const val DISCOVERY_SERVICE = "steamos-companion"
    const val MAX_REQUEST_BODY_BYTES = 256 * 1024
    const val MAX_ERROR_MESSAGE_CHARS = 1024
}

/** Constant-time equality for the strict lowercase fingerprint format. */
fun fingerprintMatches(actualSha256: ByteArray, expectedFingerprint: String): Boolean {
    if (!Regex("sha256:[0-9a-f]{64}").matches(expectedFingerprint) || actualSha256.size != 32) return false
    val expected = expectedFingerprint.removePrefix("sha256:").toByteArray(Charsets.US_ASCII)
    val actual = actualSha256.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
    return MessageDigest.isEqual(actual, expected)
}
