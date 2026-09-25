package io.github.tuthan.steamoscompanion.android

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate A0.2 scaffold probe, driven only by scripts/lan-matrix.sh. It opens one TCP connection to a
 * listener the script runs on the development machine (reached at 10.0.2.2, a private address)
 * and checks the outcome against the compatibility state the script set before rebooting.
 */
@RunWith(AndroidJUnit4::class)
class LanMatrixTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun privateAddressReachabilityMatchesCompatState() {
        val host = args.getString("lanHost")
        val port = args.getString("lanPort")?.toIntOrNull()
        val expectRestricted = args.getString("expectRestricted")?.toBooleanStrictOrNull()
        assumeTrue("run through scripts/lan-matrix.sh", host != null && port != null && expectRestricted != null)

        val outcome = try {
            Socket().use { it.connect(InetSocketAddress(host, port!!), 3_000) }
            "connected"
        } catch (error: IOException) {
            "failed: ${error.javaClass.simpleName}: ${error.message}"
        }
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} expectRestricted=$expectRestricted outcome=$outcome")
        assertEquals(outcome, !expectRestricted!!, outcome == "connected")
    }

    private companion object {
        const val TAG = "LanMatrix"
    }
}
