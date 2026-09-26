package io.github.tuthan.steamoscompanion.android.data

import android.content.Context
import android.os.Build
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPolicy
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPrompt
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionState
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkUserActionPlan

/** Reads Android's current LAN gate and exposes prompt decisions to visible, user-started UI only. */
class LocalNetworkAccess(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    /** Re-evaluates runtime API, target SDK, and grants instead of caching an earlier decision. */
    fun state(): LocalNetworkPermissionState = LocalNetworkPermissionPolicy.evaluate(
        runtimeApi = Build.VERSION.SDK_INT,
        targetSdk = appContext.applicationInfo.targetSdkVersion,
        nearbyWifiGranted = isGranted(LocalNetworkPermissionPolicy.NEARBY_WIFI_PERMISSION),
        localNetworkGranted = isGranted(LocalNetworkPermissionPolicy.LOCAL_NETWORK_PERMISSION),
    )

    /**
     * Plans the permission step for an explicit user action. It never opens a system dialog;
     * background status/discovery polling has no call path to this method.
     */
    fun planForUserInitiatedAction(mandatoryPromptCanBeShown: Boolean = true): LocalNetworkUserActionPlan {
        val current = state()
        val alreadyShown = preferences.getBoolean(KEY_NEARBY_PROMPT_SHOWN, false)
        val plan = LocalNetworkPermissionPolicy.forUserInitiatedAction(
            state = current,
            nearbyWifiPromptAlreadyShown = alreadyShown,
            mandatoryPromptCanBeShown = mandatoryPromptCanBeShown,
        )
        if (plan.prompt == LocalNetworkPermissionPrompt.RequestNearbyWifiDevices) {
            synchronized(promptLock) {
                if (preferences.getBoolean(KEY_NEARBY_PROMPT_SHOWN, false)) {
                    return LocalNetworkPermissionPolicy.forUserInitiatedAction(
                        state = state(),
                        nearbyWifiPromptAlreadyShown = true,
                        mandatoryPromptCanBeShown = mandatoryPromptCanBeShown,
                    )
                }
                // Commit before returning so two simultaneous button taps still show the prompt once.
                preferences.edit().putBoolean(KEY_NEARBY_PROMPT_SHOWN, true).commit()
            }
        }
        return plan
    }

    /**
     * Dispatches the planned system action. Call only from a visible user gesture; pass the
     * Activity Result permission launcher and app-settings navigation used by the current UI.
     */
    fun requestForUserInitiatedAction(
        requestPermission: (String) -> Unit,
        openAppSettings: () -> Unit,
        mandatoryPromptCanBeShown: Boolean = true,
    ): LocalNetworkUserActionPlan {
        val plan = planForUserInitiatedAction(mandatoryPromptCanBeShown)
        when (plan.prompt) {
            LocalNetworkPermissionPrompt.None -> Unit
            LocalNetworkPermissionPrompt.RequestNearbyWifiDevices ->
                requestPermission(LocalNetworkPermissionPolicy.NEARBY_WIFI_PERMISSION)
            LocalNetworkPermissionPrompt.RequestLocalNetwork ->
                requestPermission(LocalNetworkPermissionPolicy.LOCAL_NETWORK_PERMISSION)
            LocalNetworkPermissionPrompt.OpenAppSettings -> openAppSettings()
        }
        return plan
    }

    private fun isGranted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    companion object {
        const val LAN_ACCESS_EXPLANATION = "SteamOS Companion talks directly to your device on this network."

        private const val PREFERENCES = "local_network_permission"
        private const val KEY_NEARBY_PROMPT_SHOWN = "nearby_wifi_prompt_shown"
        private val promptLock = Any()
    }
}
