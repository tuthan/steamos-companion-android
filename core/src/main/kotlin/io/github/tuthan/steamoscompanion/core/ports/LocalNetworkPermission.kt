package io.github.tuthan.steamoscompanion.core.ports

/** The LAN permission branch currently reported by the Android adapter. */
enum class LocalNetworkPermissionState {
    Allowed,
    RequiredPermissionMissing,
    LegacyRestrictionUnknown,
}

/** A UI may request these actions only after an explicit user action. */
enum class LocalNetworkPermissionPrompt {
    None,
    RequestNearbyWifiDevices,
    RequestLocalNetwork,
    OpenAppSettings,
}

data class LocalNetworkUserActionPlan(
    val prompt: LocalNetworkPermissionPrompt,
    /** API 36 permits one bounded attempt even when the optional permission was denied. */
    val mayAttemptWithoutPermission: Boolean,
    val qualifiedFailureMessage: String? = null,
)

/**
 * A request failed in the API 36 case whose compatibility restriction cannot be proven from a timeout or `EPERM`. The
 * message is the qualified wording itself, so a status read that fails this way carries it to the screen unchanged.
 */
class LegacyLocalNetworkFailure : java.io.IOException(LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE)

/** Pure target/runtime policy shared by the Android adapter and its matrix tests. */
object LocalNetworkPermissionPolicy {
    const val API_36 = 36
    const val API_37 = 37
    const val NEARBY_WIFI_PERMISSION = "android.permission.NEARBY_WIFI_DEVICES"
    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    const val API_36_FAILURE_MESSAGE =
        "Local connection failed; nearby-device permission may be required"

    fun evaluate(
        runtimeApi: Int,
        targetSdk: Int,
        nearbyWifiGranted: Boolean,
        localNetworkGranted: Boolean,
    ): LocalNetworkPermissionState = when {
        runtimeApi >= API_37 && targetSdk >= API_37 && !localNetworkGranted ->
            LocalNetworkPermissionState.RequiredPermissionMissing
        runtimeApi == API_36 && !nearbyWifiGranted ->
            LocalNetworkPermissionState.LegacyRestrictionUnknown
        else -> LocalNetworkPermissionState.Allowed
    }

    fun forUserInitiatedAction(
        state: LocalNetworkPermissionState,
        nearbyWifiPromptAlreadyShown: Boolean,
        mandatoryPromptCanBeShown: Boolean = true,
    ): LocalNetworkUserActionPlan = when (state) {
        LocalNetworkPermissionState.Allowed -> LocalNetworkUserActionPlan(
            prompt = LocalNetworkPermissionPrompt.None,
            mayAttemptWithoutPermission = true,
        )
        LocalNetworkPermissionState.RequiredPermissionMissing -> LocalNetworkUserActionPlan(
            prompt = if (mandatoryPromptCanBeShown) {
                LocalNetworkPermissionPrompt.RequestLocalNetwork
            } else {
                LocalNetworkPermissionPrompt.OpenAppSettings
            },
            mayAttemptWithoutPermission = false,
        )
        LocalNetworkPermissionState.LegacyRestrictionUnknown -> LocalNetworkUserActionPlan(
            prompt = if (nearbyWifiPromptAlreadyShown) {
                LocalNetworkPermissionPrompt.None
            } else {
                LocalNetworkPermissionPrompt.RequestNearbyWifiDevices
            },
            mayAttemptWithoutPermission = true,
            qualifiedFailureMessage = API_36_FAILURE_MESSAGE,
        )
    }
}
