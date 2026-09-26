package io.github.tuthan.steamoscompanion.core.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionPolicyTest {
    @Test
    fun api26Through35NeverRequireRuntimeLanPermissionForEitherTarget() {
        for (runtimeApi in 26..35) {
            assertEquals(
                "API $runtimeApi, target 36",
                LocalNetworkPermissionState.Allowed,
                LocalNetworkPermissionPolicy.evaluate(runtimeApi, 36, false, false),
            )
            assertEquals(
                "API $runtimeApi, target 37",
                LocalNetworkPermissionState.Allowed,
                LocalNetworkPermissionPolicy.evaluate(runtimeApi, 37, false, false),
            )
        }
    }

    @Test
    fun api36KeepsTheRestrictionUnknownUntilNearbyPermissionIsGranted() {
        for (targetSdk in listOf(36, 37)) {
            assertEquals(
                "API 36, target $targetSdk without grant",
                LocalNetworkPermissionState.LegacyRestrictionUnknown,
                LocalNetworkPermissionPolicy.evaluate(36, targetSdk, false, false),
            )
            assertEquals(
                "API 36, target $targetSdk with grant",
                LocalNetworkPermissionState.Allowed,
                LocalNetworkPermissionPolicy.evaluate(36, targetSdk, true, false),
            )
        }
    }

    @Test
    fun api37PermissionIsMandatoryOnlyForTarget37AndNewer() {
        assertEquals(
            LocalNetworkPermissionState.Allowed,
            LocalNetworkPermissionPolicy.evaluate(37, 36, false, false),
        )
        assertEquals(
            LocalNetworkPermissionState.RequiredPermissionMissing,
            LocalNetworkPermissionPolicy.evaluate(37, 37, false, false),
        )
        assertEquals(
            LocalNetworkPermissionState.Allowed,
            LocalNetworkPermissionPolicy.evaluate(37, 37, false, true),
        )
    }

    @Test
    fun userActionOffersNearbyPromptOnceAndStillAllowsBoundedAttemptAfterDenial() {
        val first = LocalNetworkPermissionPolicy.forUserInitiatedAction(
            LocalNetworkPermissionState.LegacyRestrictionUnknown,
            nearbyWifiPromptAlreadyShown = false,
        )
        assertEquals(LocalNetworkPermissionPrompt.RequestNearbyWifiDevices, first.prompt)
        assertTrue(first.mayAttemptWithoutPermission)
        assertEquals(LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE, first.qualifiedFailureMessage)

        val afterDenial = LocalNetworkPermissionPolicy.forUserInitiatedAction(
            LocalNetworkPermissionState.LegacyRestrictionUnknown,
            nearbyWifiPromptAlreadyShown = true,
        )
        assertEquals(LocalNetworkPermissionPrompt.None, afterDenial.prompt)
        assertTrue(afterDenial.mayAttemptWithoutPermission)
    }

    @Test
    fun missingMandatoryPermissionRequestsItOrOffersSettingsWithoutOpeningSockets() {
        val prompt = LocalNetworkPermissionPolicy.forUserInitiatedAction(
            LocalNetworkPermissionState.RequiredPermissionMissing,
            nearbyWifiPromptAlreadyShown = true,
        )
        assertEquals(LocalNetworkPermissionPrompt.RequestLocalNetwork, prompt.prompt)
        assertFalse(prompt.mayAttemptWithoutPermission)

        val settings = LocalNetworkPermissionPolicy.forUserInitiatedAction(
            LocalNetworkPermissionState.RequiredPermissionMissing,
            nearbyWifiPromptAlreadyShown = true,
            mandatoryPromptCanBeShown = false,
        )
        assertEquals(LocalNetworkPermissionPrompt.OpenAppSettings, settings.prompt)
        assertFalse(settings.mayAttemptWithoutPermission)
    }
}
