package io.github.tuthan.steamoscompanion.android.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.wake.WakeSendResult
import io.github.tuthan.steamoscompanion.core.wake.WakeSender
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Sends through the real Android socket stack, bound to the real Wi-Fi network; no host is woken. */
@RunWith(AndroidJUnit4::class)
class WakeSendLiveTest {
    @Test fun sendsOnTheBoundLanNetworkOrReportsThePlatformReason() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val networks = AndroidNetworkAccess(context)
        val paths = networks.activeLanPaths()
        Log.i("WakeLive", "paths=" + paths.joinToString { "${it.id}:${it.interfaceName}:${it.addresses}:${it.broadcastAddresses}:tunnel=${it.tunnel}" })
        val path = paths.firstOrNull { !it.tunnel }
        assumeTrue("no Wi-Fi/Ethernet network on this device", path != null)
        val target = StoredWakeTarget(true, "001122aabbcc", path!!.interfaceName, null, null)
        val result = WakeSender(networks, AndroidSocketFactory()).send(target, path.id)
        Log.i("WakeLive", "result=$result")
        assertTrue(result.toString(), result is WakeSendResult.Sent)
    }
}
