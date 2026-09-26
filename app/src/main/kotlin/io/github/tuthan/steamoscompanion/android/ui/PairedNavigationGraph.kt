package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.JsonLimits
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue

/** App destinations. Paired screens share one root-level recovery state. */
enum class UiDestination(val wireName: String, val paired: Boolean = false) {
    ONBOARDING("onboarding"),
    DISCOVERY("discovery"),
    MANUAL_ADDRESS("manual_address"),
    CANDIDATE_DETAILS("candidate_details"),
    PAIRING("pairing"),
    SCANNER("scanner"),
    REMOTE_OVERVIEW("remote_overview", paired = true),
    DISPLAY("display", paired = true),
    DISPLAY_ORDER("display_order", paired = true),
    POWER("power", paired = true),
    WAKE("wake", paired = true),
    SUNSHINE("sunshine", paired = true),
    CONNECTION_DETAILS("connection_details", paired = true),
}

/** Route arguments are allowlisted per destination so saved navigation cannot carry credentials. */
data class UiRoute(
    val destination: UiDestination,
    val arguments: Map<String, String> = emptyMap(),
) {
    init {
        require(arguments.keys.all { it in ALLOWED_ARGUMENTS.getValue(destination) }) {
            "Route contains an argument that is not safe to save"
        }
        require(arguments.values.all { value ->
            value.length <= MAX_ARGUMENT_LENGTH && value.none { it.isISOControl() }
        }) { "Route argument is invalid" }
        if ("generation" in arguments) require(arguments.getValue("generation").toLongOrNull()?.let { it >= 0 } == true)
        if ("candidate_generation" in arguments) {
            require(arguments.getValue("candidate_generation").toLongOrNull()?.let { it > 0 } == true)
        }
        if ("host" in arguments) {
            val host = arguments.getValue("host")
            require(host.isEmpty() || host.length <= 253 && runCatching { HostEndpoint.parse(host) }.isSuccess) {
                "Manual address route may save only a host name or IP address"
            }
        }
        if ("endpoint" in arguments) {
            require(runCatching { HostEndpoint.parse(arguments.getValue("endpoint")) }.isSuccess) {
                "Candidate route must contain a host endpoint only"
            }
        }
        if ("identity_fingerprint" in arguments) {
            require(Regex("sha256:[0-9a-f]{64}").matches(arguments.getValue("identity_fingerprint")))
        }
        if ("port" in arguments) {
            val port = arguments.getValue("port")
            require(port.isEmpty() || port.toIntOrNull()?.let { it in 1..65535 } == true)
        }
        listOf("output_id", "mode_id").forEach { key ->
            arguments[key]?.let { require(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}").matches(it)) }
        }
        if ("pairing_variant" in arguments) {
            require(arguments.getValue("pairing_variant") in setOf("verification", "advanced_payload"))
        }
        if ("action" in arguments) require(arguments.getValue("action") in setOf("suspend", "restart", "shutdown"))
    }

    companion object {
        private const val MAX_ARGUMENT_LENGTH = 512
        private val ALLOWED_ARGUMENTS = mapOf(
            UiDestination.ONBOARDING to emptySet(),
            UiDestination.DISCOVERY to emptySet(),
            UiDestination.MANUAL_ADDRESS to setOf("host", "port"),
            UiDestination.CANDIDATE_DETAILS to setOf("endpoint", "identity_fingerprint"),
            // Pending pairing is restored from encrypted persistent state, never route arguments.
            UiDestination.PAIRING to setOf("candidate_generation", "pairing_variant"),
            UiDestination.SCANNER to emptySet(),
            UiDestination.REMOTE_OVERVIEW to emptySet(),
            UiDestination.DISPLAY to setOf("output_id", "mode_id", "generation"),
            UiDestination.DISPLAY_ORDER to emptySet(),
            // A confirmation dialog is deliberately not a route and will not survive process death.
            UiDestination.POWER to emptySet(),
            UiDestination.WAKE to emptySet(),
            UiDestination.SUNSHINE to emptySet(),
            UiDestination.CONNECTION_DETAILS to emptySet(),
        )
    }
}

/**
 * Small serializable navigation stack for SavedStateHandle. Its payload contains routes and
 * non-secret selection state only. Pairing credentials and operation history remain encrypted
 * in :core; active preview state is re-read from the host after restoration.
 */
class PairedNavigationGraph private constructor(private val backStack: MutableList<UiRoute>) {
    constructor(initialRoute: UiRoute = UiRoute(UiDestination.ONBOARDING)) : this(mutableListOf(initialRoute))

    val current: UiRoute
        get() = backStack.last()

    val routes: List<UiRoute>
        get() = backStack.toList()

    fun navigate(route: UiRoute): Boolean {
        if (route == current) return false
        require(backStack.size < MAX_BACK_STACK_SIZE) { "Navigation stack is full" }
        require(canNavigate(current.destination, route.destination)) {
            "Invalid navigation from ${current.destination.wireName} to ${route.destination.wireName}"
        }
        backStack += route
        return true
    }

    fun back(): Boolean {
        if (backStack.size == 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun saveState(): String {
        val bytes = BoundedJson.encode(
            JsonValue.ObjectValue(
                mapOf(
                    "schemaVersion" to JsonValue.NumberValue(SCHEMA_VERSION.toString()),
                    "routes" to JsonValue.ArrayValue(backStack.map(::routeJson)),
                ),
            ),
        )
        require(bytes.size <= ROUTE_LIMITS.maxBytes) { "Saved navigation state is too large" }
        return bytes.decodeToString()
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_BACK_STACK_SIZE = 16
        private val ROUTE_LIMITS = JsonLimits(maxBytes = 8 * 1024, maxDepth = 5, maxContainerItems = 64)

        fun restore(savedState: String): PairedNavigationGraph {
            val root = BoundedJson.parseObject(savedState.toByteArray(Charsets.UTF_8), ROUTE_LIMITS)
            require(root.fields.keys == setOf("schemaVersion", "routes")) { "Saved navigation state has unknown fields" }
            require(root["schemaVersion"] == JsonValue.NumberValue(SCHEMA_VERSION.toString())) {
                "Unsupported saved navigation schema"
            }
            val routeValues = (root["routes"] as? JsonValue.ArrayValue)?.values
                ?: throw IllegalArgumentException("Saved navigation routes are missing")
            require(routeValues.size in 1..MAX_BACK_STACK_SIZE) { "Saved navigation stack size is invalid" }
            val restoredRoutes = routeValues.map(::parseRoute)
            restoredRoutes.zipWithNext().forEach { (from, to) ->
                require(canNavigate(from.destination, to.destination)) {
                    "Invalid saved navigation transition from ${from.destination.wireName} to ${to.destination.wireName}"
                }
            }
            return PairedNavigationGraph(restoredRoutes.toMutableList())
        }

        private fun routeJson(route: UiRoute) = JsonValue.ObjectValue(
            mapOf(
                "destination" to JsonValue.StringValue(route.destination.wireName),
                "arguments" to JsonValue.ObjectValue(route.arguments.mapValues { JsonValue.StringValue(it.value) }),
            ),
        )

        private fun parseRoute(value: JsonValue): UiRoute {
            val fields = (value as? JsonValue.ObjectValue)?.fields
                ?: throw IllegalArgumentException("Saved route must be an object")
            require(fields.keys == setOf("destination", "arguments")) { "Saved route has unknown fields" }
            val destinationName = (fields["destination"] as? JsonValue.StringValue)?.value
                ?: throw IllegalArgumentException("Saved route destination is missing")
            val destination = UiDestination.entries.singleOrNull { it.wireName == destinationName }
                ?: throw IllegalArgumentException("Saved route destination is unknown")
            val argumentsObject = fields["arguments"] as? JsonValue.ObjectValue
                ?: throw IllegalArgumentException("Saved route arguments are missing")
            val arguments = argumentsObject.fields.entries.associate { (key, argument) ->
                key to ((argument as? JsonValue.StringValue)?.value
                    ?: throw IllegalArgumentException("Saved route argument must be a string"))
            }
            return UiRoute(destination, arguments)
        }

        private fun canNavigate(from: UiDestination, to: UiDestination): Boolean = when (from) {
            UiDestination.ONBOARDING -> to in setOf(UiDestination.DISCOVERY, UiDestination.MANUAL_ADDRESS, UiDestination.SCANNER)
            UiDestination.DISCOVERY -> to in setOf(UiDestination.ONBOARDING, UiDestination.CANDIDATE_DETAILS)
            UiDestination.MANUAL_ADDRESS -> to in setOf(UiDestination.ONBOARDING, UiDestination.CANDIDATE_DETAILS)
            UiDestination.CANDIDATE_DETAILS -> to in setOf(
                UiDestination.ONBOARDING,
                UiDestination.DISCOVERY,
                UiDestination.MANUAL_ADDRESS,
                UiDestination.PAIRING,
            )
            UiDestination.PAIRING -> to in setOf(UiDestination.CANDIDATE_DETAILS, UiDestination.REMOTE_OVERVIEW)
            UiDestination.SCANNER -> to in setOf(UiDestination.ONBOARDING, UiDestination.PAIRING)
            UiDestination.REMOTE_OVERVIEW,
            UiDestination.DISPLAY,
            UiDestination.DISPLAY_ORDER,
            UiDestination.POWER,
            UiDestination.WAKE,
            UiDestination.SUNSHINE,
            UiDestination.CONNECTION_DETAILS,
            -> to.paired || (from == UiDestination.CONNECTION_DETAILS && to == UiDestination.ONBOARDING)
        }
    }
}

/** The single recovery slot supplied to every paired destination from host/journal state. */
data class PairedRecoveryState(val card: PairedRecoveryCard? = null) {
    fun cardsFor(destination: UiDestination): List<PairedRecoveryCard> {
        if (!destination.paired) return emptyList()
        return listOfNotNull(card)
    }
}

sealed interface PairedRecoveryCard

data class PreviewRecoveryCard(
    val outputLabel: String,
    val modeLabel: String,
    val remainingMillis: Long?,
) : PairedRecoveryCard

data class UnresolvedOperationCard(
    val actionLabel: String,
    val operationId: String?,
    val summary: String,
) : PairedRecoveryCard
