package io.github.tuthan.steamoscompanion.core.protocol

import java.net.URI
import java.util.Locale

enum class HttpMethod(val wireValue: String) { GET("GET"), POST("POST") }

enum class ApiRoute(
    val method: HttpMethod,
    val path: String,
    val requiresBearer: Boolean,
    val maxResponseBytes: Int = DEFAULT_RESPONSE_LIMIT,
) {
    DISCOVERY(HttpMethod.GET, "/v1/discovery", false, 16 * 1024),
    PAIR_REQUEST(HttpMethod.POST, "/v1/pair/request", false),
    PAIR_CANCEL(HttpMethod.POST, "/v1/pair/cancel", false),
    PAIR_REVOKE_SELF(HttpMethod.POST, "/v1/pair/revoke-self", true),
    STATUS(HttpMethod.GET, "/v1/status", true),
    DISPLAY_OUTPUTS(HttpMethod.GET, "/v1/display/outputs", true),
    DISPLAY_ORDER(HttpMethod.GET, "/v1/display/order", true),
    DRM(HttpMethod.GET, "/v1/drm", true),
    OPERATION(HttpMethod.GET, "/v1/operations/{id}", true),
    POWER(HttpMethod.POST, "/v1/power", true),
    DISPLAY_ORDER_SET(HttpMethod.POST, "/v1/display/order", true),
    DISPLAY_ORDER_AUTOMATIC(HttpMethod.POST, "/v1/display/order/automatic", true),
    DISPLAY_PREVIEW(HttpMethod.POST, "/v1/display/preview", true),
    DISPLAY_CONFIRM(HttpMethod.POST, "/v1/display/confirm", true),
    DISPLAY_RESTORE(HttpMethod.POST, "/v1/display/restore", true),
    DISPLAY_SAVE_CURRENT(HttpMethod.POST, "/v1/display/save-current", true),
    SUNSHINE_RESTART(HttpMethod.POST, "/v1/sunshine/restart", true),
    ;

    companion object {
        const val DEFAULT_RESPONSE_LIMIT = 256 * 1024
        private val OPERATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")

        fun match(method: HttpMethod, path: String): MatchedRoute {
            validatePath(path)
            val route = when {
                method == HttpMethod.GET && path == DISCOVERY.path -> DISCOVERY
                method == HttpMethod.POST && path == PAIR_REQUEST.path -> PAIR_REQUEST
                method == HttpMethod.POST && path == PAIR_CANCEL.path -> PAIR_CANCEL
                method == HttpMethod.POST && path == PAIR_REVOKE_SELF.path -> PAIR_REVOKE_SELF
                method == HttpMethod.GET && path == STATUS.path -> STATUS
                method == HttpMethod.GET && path == DISPLAY_OUTPUTS.path -> DISPLAY_OUTPUTS
                method == HttpMethod.GET && path == DISPLAY_ORDER.path -> DISPLAY_ORDER
                method == HttpMethod.GET && path == DRM.path -> DRM
                method == HttpMethod.GET && path.startsWith("/v1/operations/") -> OPERATION
                method == HttpMethod.POST && path == POWER.path -> POWER
                method == HttpMethod.POST && path == DISPLAY_ORDER_SET.path -> DISPLAY_ORDER_SET
                method == HttpMethod.POST && path == DISPLAY_ORDER_AUTOMATIC.path -> DISPLAY_ORDER_AUTOMATIC
                method == HttpMethod.POST && path == DISPLAY_PREVIEW.path -> DISPLAY_PREVIEW
                method == HttpMethod.POST && path == DISPLAY_CONFIRM.path -> DISPLAY_CONFIRM
                method == HttpMethod.POST && path == DISPLAY_RESTORE.path -> DISPLAY_RESTORE
                method == HttpMethod.POST && path == DISPLAY_SAVE_CURRENT.path -> DISPLAY_SAVE_CURRENT
                method == HttpMethod.POST && path == SUNSHINE_RESTART.path -> SUNSHINE_RESTART
                else -> throw RequestValidationException("Unsupported method and path")
            }
            val operationId = if (route == OPERATION) path.removePrefix("/v1/operations/") else null
            if (route == OPERATION && (operationId.isNullOrEmpty() || !OPERATION_ID.matches(operationId))) {
                throw RequestValidationException("Invalid operation ID")
            }
            return MatchedRoute(route, operationId)
        }

        fun validatePath(path: String) {
            if (path.isEmpty() || path.length > 512 || path.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                throw RequestValidationException("Invalid request path")
            }
            if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
                throw RequestValidationException("Request path must not be an absolute URL")
            }
            if ('?' in path || '#' in path || !path.startsWith('/')) {
                throw RequestValidationException("Queries, fragments, and relative paths are not supported")
            }
            if (path.any { it.code < 0x21 || it.code > 0x7e || it == '\\' }) {
                throw RequestValidationException("Request path contains an invalid character")
            }
        }
    }
}

data class MatchedRoute(val route: ApiRoute, val operationId: String? = null)

/** Host/port only. The caller cannot smuggle a URL path, query, fragment, or credentials. */
class HostEndpoint private constructor(val host: String, val port: Int) {
    val authority: String
        get() = "${if (host.contains(':')) "[$host]" else host}:$port"

    companion object {
        const val DEFAULT_PORT = 18443

        fun parse(input: String): HostEndpoint {
            if (input.isBlank() || input.length > 512 || input.any { it == '\r' || it == '\n' || it == '\u0000' || it.isWhitespace() }) {
                throw RequestValidationException("Enter a host name or IP address and optional port")
            }
            val uri = try {
                URI(if (input.contains("://")) input else "https://$input")
            } catch (_: Exception) {
                throw RequestValidationException("Invalid host endpoint")
            }
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
                uri.rawPath.orEmpty().isNotEmpty() || uri.rawQuery != null || uri.rawFragment != null
            ) {
                throw RequestValidationException("Endpoint must contain only an HTTPS host and port")
            }
            val uriHost = uri.host ?: throw RequestValidationException("Endpoint host is invalid")
            val host = uriHost.removePrefix("[").removeSuffix("]")
            if (host.isEmpty() || host.length > 253 || host.any { it == '\r' || it == '\n' || it.isWhitespace() }) {
                throw RequestValidationException("Endpoint host is invalid")
            }
            val port = if (uri.port == -1) DEFAULT_PORT else uri.port
            if (port !in 1..65535) throw RequestValidationException("Endpoint port must be from 1 to 65535")
            return HostEndpoint(host, port)
        }
    }
}

data class ApiRequest(
    val method: HttpMethod,
    val path: String,
    val body: JsonValue.ObjectValue? = null,
    /** Null for discovery and pairing. Pairing never inherits a saved host credential. */
    val bearerToken: String? = null,
) {
    fun validate(): MatchedRoute {
        val match = ApiRoute.match(method, path)
        val route = match.route
        if (route.requiresBearer && bearerToken.isNullOrBlank()) {
            throw RequestValidationException("This route requires a paired credential")
        }
        if (!route.requiresBearer && bearerToken != null) {
            throw RequestValidationException("This route must not carry a paired credential")
        }
        bearerToken?.let(::validateHeaderValue)
        if (method == HttpMethod.GET && body != null) throw RequestValidationException("GET requests must not have a body")
        if (method == HttpMethod.POST && body == null) throw RequestValidationException("POST requests require a JSON body")
        body?.let { validateBody(match, it) }
        return match
    }

    companion object {
        private val ALLOWED_SCOPES = setOf("status.read", "power.control", "display.control")
        private val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        private val PAIRING_SESSION = Regex("[A-Za-z0-9_-]{16,256}")
        private val NONCE = Regex("[A-Za-z0-9_-]{22}")

        private fun validateBody(match: MatchedRoute, body: JsonValue.ObjectValue) {
            val fields = body.fields
            fun exact(required: Set<String>, optional: Set<String> = emptySet()) {
                if (!fields.keys.containsAll(required) || (fields.keys - required - optional).isNotEmpty()) {
                    throw RequestValidationException("JSON body fields do not match ${match.route.path}")
                }
            }
            fun string(name: String, max: Int = 256): String {
                val value = fields[name].asString(name)
                if (value.isEmpty() || value.length > max || value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                    throw RequestValidationException("Invalid '$name'")
                }
                return value
            }
            fun requestId() {
                val id = string("request_id", 128)
                if (!REQUEST_ID.matches(id)) throw RequestValidationException("Invalid request_id")
            }
            fun scopes() {
                val values = fields["scopes"].asArray("scopes")
                if (values.isEmpty() || values.size > ALLOWED_SCOPES.size) throw RequestValidationException("Invalid scopes")
                val parsed = values.map { it.asString("scope") }
                if (parsed.distinct().size != parsed.size || parsed.any { it !in ALLOWED_SCOPES }) {
                    throw RequestValidationException("Unsupported or duplicate scope")
                }
            }
            fun positiveInt(name: String, max: Long = Int.MAX_VALUE.toLong()) {
                val number = fields[name].asLong(name)
                if (number !in 0..max) throw RequestValidationException("Invalid '$name'")
            }
            fun bool(name: String) {
                if (fields[name] !is JsonValue.BooleanValue) throw RequestValidationException("'$name' must be boolean")
            }
            fun pairingSession() {
                val session = string("pairing_session", 256)
                if (!PAIRING_SESSION.matches(session)) {
                    throw RequestValidationException("Invalid pairing session")
                }
            }

            when (match.route) {
                ApiRoute.PAIR_REQUEST -> {
                    val keys = fields.keys
                    val poll = "pairing_session" in keys
                    val verification = "verification_nonce" in keys
                    val payload = "pairing_id" in keys || "secret" in keys
                    if (listOf(verification, payload).count { it } != 1) {
                        throw RequestValidationException("Pairing request must use one supported form")
                    }
                    when {
                        verification -> {
                            exact(setOf("verification_nonce", "client_id", "client_name", "scopes"), setOf("pairing_session"))
                            if (!NONCE.matches(string("verification_nonce", 22))) throw RequestValidationException("Invalid verification nonce")
                        }
                        payload -> {
                            exact(setOf("pairing_id", "secret", "client_id", "client_name", "scopes"), setOf("pairing_session"))
                            if (!SAFE_ID.matches(string("pairing_id", 128))) throw RequestValidationException("Invalid pairing_id")
                            string("secret", 256)
                        }
                    }
                    if (!SAFE_ID.matches(string("client_id", 128))) throw RequestValidationException("Invalid client_id")
                    string("client_name", 96)
                    if (poll) pairingSession()
                    scopes()
                }
                ApiRoute.PAIR_CANCEL -> {
                    exact(setOf("pairing_id", "verification_nonce", "client_id"), setOf("pairing_session"))
                    if (!SAFE_ID.matches(string("pairing_id", 128)) || !SAFE_ID.matches(string("client_id", 128))) {
                        throw RequestValidationException("Invalid pairing identity")
                    }
                    if (!NONCE.matches(string("verification_nonce", 22))) throw RequestValidationException("Invalid verification nonce")
                    if ("pairing_session" in fields) pairingSession()
                }
                ApiRoute.PAIR_REVOKE_SELF, ApiRoute.DISPLAY_ORDER_AUTOMATIC, ApiRoute.SUNSHINE_RESTART -> {
                    exact(setOf("request_id")); requestId()
                }
                ApiRoute.POWER -> {
                    exact(setOf("request_id", "action")); requestId()
                    if (string("action", 32) !in setOf("suspend", "restart", "shutdown")) {
                        throw RequestValidationException("Unsupported power action")
                    }
                }
                ApiRoute.DISPLAY_ORDER_SET -> {
                    exact(setOf("request_id", "output_keys", "generation", "restart")); requestId()
                    positiveInt("generation")
                    bool("restart")
                    val keys = fields["output_keys"].asArray("output_keys")
                    if (keys.isEmpty() || keys.size > 16) throw RequestValidationException("Invalid output_keys")
                    val parsed = keys.map { it.asString("output_key") }
                    if (parsed.distinct().size != parsed.size || parsed.any { !Regex("[A-Za-z0-9][A-Za-z0-9_.:|/-]{0,127}").matches(it) }) {
                        throw RequestValidationException("Invalid or duplicate output key")
                    }
                }
                ApiRoute.DISPLAY_PREVIEW -> {
                    exact(setOf("request_id", "output_id", "mode_id", "generation")); requestId()
                    if (!SAFE_ID.matches(string("output_id", 128)) || !SAFE_ID.matches(string("mode_id", 128))) {
                        throw RequestValidationException("Invalid display target")
                    }
                    positiveInt("generation")
                }
                ApiRoute.DISPLAY_CONFIRM -> {
                    exact(setOf("request_id", "preview_id", "visible")); requestId()
                    if (!SAFE_ID.matches(string("preview_id", 128))) throw RequestValidationException("Invalid preview_id")
                    bool("visible")
                }
                ApiRoute.DISPLAY_RESTORE -> {
                    exact(setOf("request_id", "source"), setOf("profile_id")); requestId()
                    val source = string("source", 16)
                    if (source !in setOf("preview", "verified")) throw RequestValidationException("Invalid restore source")
                    if (source == "verified" && "profile_id" in fields && !SAFE_ID.matches(string("profile_id", 128))) {
                        throw RequestValidationException("Invalid profile_id")
                    }
                    if (source == "preview" && "profile_id" in fields) throw RequestValidationException("Preview restore must not include profile_id")
                }
                ApiRoute.DISPLAY_SAVE_CURRENT -> {
                    exact(setOf("request_id", "output_id", "generation", "visible")); requestId()
                    if (!SAFE_ID.matches(string("output_id", 128))) throw RequestValidationException("Invalid output_id")
                    positiveInt("generation")
                    if (fields["visible"] != JsonValue.BooleanValue(true)) {
                        throw RequestValidationException("Explicit owner confirmation is required")
                    }
                }
                else -> throw RequestValidationException("This route does not accept a request body")
            }
        }

        private fun validateHeaderValue(value: String) {
            if (value.length > 4096 || value.any { it.code < 0x20 || it.code == 0x7f || it == '\r' || it == '\n' }) {
                throw RequestValidationException("Invalid authorization header value")
            }
        }
    }
}

sealed interface ApiReply {
    data class Versioned(val protocolVersion: Int, val payload: VersionedPayload) : ApiReply
    data class UnsupportedVersion(val protocolVersion: Long) : ApiReply
    data class Rejected(val status: Int, val error: String, val message: String) : ApiReply
}

sealed interface VersionedPayload {
    data class Discovery(val announcement: DiscoveryAnnouncement, val name: String?) : VersionedPayload
    data class Pairing(
        val state: WireValue<PairingState>,
        val pairingId: String?,
        val hostId: String?,
        val expiresAt: JsonValue.NumberValue?,
        val pairingSession: String?,
        val credential: PairedCredential?,
        val wakeTarget: PairingWakeTarget?,
        val body: JsonValue.ObjectValue,
    ) : VersionedPayload
    data class Status(
        val hostId: String,
        val bootId: String,
        val steamBridge: WireValue<SteamBridgeState>,
        val capabilities: Map<String, WireValue<CapabilityState>>,
        val sunshineState: WireValue<SunshineState>,
        val body: JsonValue.ObjectValue,
        /** Optional host self-check; null when the host omits it or sends nothing usable. Never persisted. */
        val selfCheck: HostSelfCheck? = null,
    ) : VersionedPayload
    data class DisplayOutputs(
        val available: Boolean,
        val generation: Long?,
        val outputs: List<DisplayOutputSummary>,
        val legacyShape: Boolean,
        val body: JsonValue.ObjectValue,
        val profiles: List<DisplayRecoveryProfile> = emptyList(),
        val preview: DisplayPreviewSummary? = null,
    ) : VersionedPayload
    data class DisplayOrder(val snapshot: DisplayOrderSummary, val body: JsonValue.ObjectValue) : VersionedPayload
    /** Advisory DRM link facts; [facts] is null when the host sends nothing usable. Never persisted. */
    data class Drm(val facts: LinkFacts?) : VersionedPayload
    data class Operation(val operation: OperationSummary) : VersionedPayload
    data class Mutation(
        val operation: OperationSummary?,
        val body: JsonValue.ObjectValue,
        /** Host profile fact; callers still bind the operation to the dispatched journal entry. */
        val profile: DisplayRecoveryProfile? = null,
        /** Host preview fact with the operation ID as its preview ID. */
        val preview: DisplayPreviewSummary? = null,
    ) : VersionedPayload
}

data class PairedCredential(
    val clientId: String,
    val token: String,
    val scopes: List<String>,
    val hostId: String,
)

data class PairingWakeTarget(
    val available: Boolean,
    val mac: String?,
    val interfaceName: String?,
    val sourceAddress: String?,
    val reason: String?,
)

data class DisplayOutputSummary(
    val id: String,
    val name: String?,
    val currentModeId: String?,
    val generation: Long,
    val modeCount: Int,
    val isInternal: Boolean? = null,
    val modes: List<DisplayModeSummary> = emptyList(),
)

data class DisplayModeSummary(
    val id: String,
    val width: Long,
    val height: Long,
    /** Exact host value, kept for labels when rounded refresh values coincide. */
    val refreshHz: String?,
)

data class DisplayRecoveryProfile(
    val id: String,
    val outputId: String,
    val mode: DisplayModeSummary,
)

data class DisplayPreviewSummary(
    val id: String,
    val outputId: String,
    val generation: Long,
    val baselineMode: DisplayModeSummary,
    val targetMode: DisplayModeSummary,
    /** Host wall-clock deadline in seconds; UI must anchor it to response timing. */
    val deadlineSeconds: String,
)

data class DisplayOrderSummary(
    val available: Boolean,
    val generation: Long?,
    val outputKeys: List<String>,
    val savedOutputKeys: List<String>,
    val restartRequired: Boolean,
    val restartAvailable: Boolean,
    val unsupported: Boolean,
    val stale: Boolean,
    val ambiguous: Boolean,
    val outputs: List<DisplayOrderOutput> = emptyList(),
)

data class DisplayOrderOutput(
    val key: String,
    val displayName: String?,
    val connector: String?,
    val connected: Boolean,
    val active: Boolean?,
)

enum class PairingState { PENDING, APPROVED, REJECTED, CANCELLED, EXPIRED }
enum class SteamBridgeState { READY, UNAVAILABLE }
enum class CapabilityState { AVAILABLE, UNAVAILABLE, UNSUPPORTED, UNVERIFIED, DISABLED }
enum class SunshineState { DISABLED, UNAVAILABLE, UNKNOWN, RUNNING, STOPPED, RESTARTING }

data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

data class OperationSummary(
    val id: String,
    val requestId: String,
    val state: WireValue<OperationState>,
    val kind: String,
    val createdAt: String,
    val updatedAt: String,
    val outcome: String?,
    val reason: String?,
    /** Host fact on a display preview: restore progress, e.g. "succeeded" after a timeout revert. */
    val restoreState: String? = null,
    /** Host fact: the original mode was restored and read back. */
    val restored: Boolean? = null,
) {
    /** The host itself restored and verified the original mode (timeout or revert). */
    val hostRestored: Boolean get() = restoreState == "succeeded" && restored == true
}

enum class OperationState { ACCEPTED, DISPATCHED, OBSERVED_RETURN, SUCCEEDED, FAILED, UNKNOWN }

sealed interface WireValue<out T> {
    data class Known<T>(val value: T) : WireValue<T>
    data class Unknown(val raw: String) : WireValue<Nothing>
}

data class DiscoveryAnnouncement(
    val service: String,
    val protocolVersion: Int,
    val hostId: String,
    val certificateFingerprint: String,
)

class RequestValidationException(message: String) : IllegalArgumentException(message)

internal fun String.lowerRoot(): String = lowercase(Locale.ROOT)
