package io.github.tuthan.steamoscompanion.android

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.steamoscompanion.android.data.LocalNetworkAccess
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPrompt
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPolicy
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime adapter check to run on each API/target variant in the A0.2 matrix. */
@RunWith(AndroidJUnit4::class)
class LocalNetworkPermissionRuntimeTest {
    @Test
    fun adapterRechecksActualRuntimeTargetAndPermissionGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val targetSdk = context.applicationInfo.targetSdkVersion
        val nearbyGranted = context.checkSelfPermission(LocalNetworkPermissionPolicy.NEARBY_WIFI_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val localNetworkGranted = context.checkSelfPermission(LocalNetworkPermissionPolicy.LOCAL_NETWORK_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val expected = LocalNetworkPermissionPolicy.evaluate(
            runtimeApi = Build.VERSION.SDK_INT,
            targetSdk = targetSdk,
            nearbyWifiGranted = nearbyGranted,
            localNetworkGranted = localNetworkGranted,
        )

        assertEquals(expected, LocalNetworkAccess(context).state())
        assertTrue(targetSdk == 36 || targetSdk == 37)
    }

    @Test
    fun deniedApi37Target37PermissionIsReportedAsMandatory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT < LocalNetworkPermissionPolicy.API_37 ||
            context.applicationInfo.targetSdkVersion < LocalNetworkPermissionPolicy.API_37
        ) return

        val localNetworkGranted = context.checkSelfPermission(LocalNetworkPermissionPolicy.LOCAL_NETWORK_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!localNetworkGranted) {
            assertEquals(
                LocalNetworkPermissionState.RequiredPermissionMissing,
                LocalNetworkAccess(context).state(),
            )
        }
    }

    @Test
    fun api36NearbyPromptIsDispatchedOnlyOnceAndDenialStillAllowsTheBoundedAttempt() {
        if (Build.VERSION.SDK_INT != LocalNetworkPermissionPolicy.API_36) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permission = LocalNetworkPermissionPolicy.NEARBY_WIFI_PERMISSION
        val originallyGranted = isGranted(context, permission)
        val preferences = context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
        val promptWasAlreadyShown = preferences.getBoolean(KEY_NEARBY_PROMPT_SHOWN, false)

        try {
            setRuntimePermission(context.packageName, permission, granted = false)
            preferences.edit().remove(KEY_NEARBY_PROMPT_SHOWN).commit()
            val access = LocalNetworkAccess(context)
            assertEquals(LocalNetworkPermissionState.LegacyRestrictionUnknown, access.state())

            val requested = mutableListOf<String>()
            var settingsOpened = false
            val first = access.requestForUserInitiatedAction({ requested += it }, { settingsOpened = true })

            assertEquals(LocalNetworkPermissionPrompt.RequestNearbyWifiDevices, first.prompt)
            assertTrue(first.mayAttemptWithoutPermission)
            assertEquals(LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE, first.qualifiedFailureMessage)
            assertEquals(listOf(permission), requested)
            assertFalse(settingsOpened)

            val afterDenial = access.requestForUserInitiatedAction({ requested += it }, { settingsOpened = true })
            assertEquals(LocalNetworkPermissionPrompt.None, afterDenial.prompt)
            assertTrue(afterDenial.mayAttemptWithoutPermission)
            assertEquals(listOf(permission), requested)
        } finally {
            preferences.edit().putBoolean(KEY_NEARBY_PROMPT_SHOWN, promptWasAlreadyShown).commit()
            setRuntimePermission(context.packageName, permission, originallyGranted)
        }
    }

    @Test
    fun api37Target37DispatchesMandatoryRequestOrSettingsAndNeverAllowsAnAttemptWithoutGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT < LocalNetworkPermissionPolicy.API_37 ||
            context.applicationInfo.targetSdkVersion < LocalNetworkPermissionPolicy.API_37
        ) return

        val permission = LocalNetworkPermissionPolicy.LOCAL_NETWORK_PERMISSION
        val originallyGranted = isGranted(context, permission)
        try {
            setRuntimePermission(context.packageName, permission, granted = false)
            val access = LocalNetworkAccess(context)
            assertEquals(LocalNetworkPermissionState.RequiredPermissionMissing, access.state())

            val requested = mutableListOf<String>()
            var settingsOpened = false
            val request = access.requestForUserInitiatedAction({ requested += it }, { settingsOpened = true })
            assertEquals(LocalNetworkPermissionPrompt.RequestLocalNetwork, request.prompt)
            assertFalse(request.mayAttemptWithoutPermission)
            assertEquals(listOf(permission), requested)
            assertFalse(settingsOpened)

            val fallback = access.requestForUserInitiatedAction(
                requestPermission = { requested += it },
                openAppSettings = { settingsOpened = true },
                mandatoryPromptCanBeShown = false,
            )
            assertEquals(LocalNetworkPermissionPrompt.OpenAppSettings, fallback.prompt)
            assertFalse(fallback.mayAttemptWithoutPermission)
            assertEquals(listOf(permission), requested)
            assertTrue(settingsOpened)
        } finally {
            setRuntimePermission(context.packageName, permission, originallyGranted)
        }
    }

    @Test
    fun api37Target36KeepsImplicitLocalNetworkAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT < LocalNetworkPermissionPolicy.API_37 ||
            context.applicationInfo.targetSdkVersion >= LocalNetworkPermissionPolicy.API_37
        ) return

        assertEquals(LocalNetworkPermissionState.Allowed, LocalNetworkAccess(context).state())
        val requested = mutableListOf<String>()
        val plan = LocalNetworkAccess(context).requestForUserInitiatedAction({ requested += it }, {})
        assertEquals(LocalNetworkPermissionPrompt.None, plan.prompt)
        assertTrue(plan.mayAttemptWithoutPermission)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun apisBelow36DoNotRequestLanPermissions() {
        if (Build.VERSION.SDK_INT >= LocalNetworkPermissionPolicy.API_36) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requested = mutableListOf<String>()
        var settingsOpened = false
        val plan = LocalNetworkAccess(context).requestForUserInitiatedAction({ requested += it }, { settingsOpened = true })
        assertEquals(LocalNetworkPermissionPrompt.None, plan.prompt)
        assertTrue(plan.mayAttemptWithoutPermission)
        assertTrue(requested.isEmpty())
        assertFalse(settingsOpened)
    }

    private fun isGranted(context: android.content.Context, permission: String) =
        context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun setRuntimePermission(packageName: String, permission: String, granted: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (granted) instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
        else instrumentation.uiAutomation.revokeRuntimePermission(packageName, permission)
    }

    private companion object {
        const val PREFERENCES = "local_network_permission"
        const val KEY_NEARBY_PROMPT_SHOWN = "nearby_wifi_prompt_shown"
    }
}
