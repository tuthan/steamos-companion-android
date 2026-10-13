package io.github.tuthan.steamoscompanion.android.data

import io.github.tuthan.steamoscompanion.core.ports.LegacyLocalNetworkFailure
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPolicy
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the Android 16 local-network failure is classified on the status path. */
class PinnedStatusGatewayFailureTest {
    @Test fun theQualifiedApi36FailureCarriesTheDocumentedWordingAndNothingElseDoes() {
        for (reason in TransportFailure.entries) {
            val error = statusReadFailure("/v1/status", reason, "diagnostic")
            if (reason == TransportFailure.LEGACY_PERMISSION_MAY_BE_REQUIRED) {
                assertTrue(error is LegacyLocalNetworkFailure)
                assertEquals(LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE, error.message)
            } else {
                assertFalse("$reason must not borrow the qualified wording", error is LegacyLocalNetworkFailure)
                assertFalse("$reason", error.message == LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE)
            }
        }
    }

    @Test fun aChangedPinStaysItsOwnFailureSoPairingIsNeverOfferedAsAWifiProblem() {
        val error = statusReadFailure("/v1/status", TransportFailure.PIN)
        assertTrue(error is PinnedCertificateChanged)
        assertTrue(error.message!!.contains("re-pairing is required"))
    }

    @Test fun anyOtherFailureNamesTheRouteAndTheReason() {
        val error = statusReadFailure("/v1/status", TransportFailure.entries.first {
            it != TransportFailure.PIN && it != TransportFailure.LEGACY_PERMISSION_MAY_BE_REQUIRED }, "no route")
        assertTrue(error.message!!.startsWith("/v1/status: "))
        assertTrue(error.message!!.endsWith("(no route)"))
    }
}
