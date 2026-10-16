package com.shilapi.xcertplay

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TemporaryHotspotOwnershipTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun wifi() = ReflectionHelpers.getField<WifiManager>(LocalOnlyHotspotManager(context), "wifiManager")

    private fun start(wifi: WifiManager) {
        Shadow.directlyOn<Any>(wifi, WifiManager::class.java.name, "startLocalOnlyHotspot",
            ClassParameter.from(WifiManager.LocalOnlyHotspotCallback::class.java, object : WifiManager.LocalOnlyHotspotCallback() {}),
            ClassParameter.from(Handler::class.java, Handler(Looper.getMainLooper())))
    }

    private fun reservation(wifi: WifiManager): WifiManager.LocalOnlyHotspotReservation =
        ReflectionHelpers.callConstructor(WifiManager.LocalOnlyHotspotReservation::class.java,
            ClassParameter.from(WifiManager::class.java, wifi),
            ClassParameter.from(WifiConfiguration::class.java, WifiConfiguration()))

    private fun finalizeReservation(value: WifiManager.LocalOnlyHotspotReservation) {
        Shadow.directlyOn<Any>(value, WifiManager.LocalOnlyHotspotReservation::class.java.name, "finalize")
    }

    @Test fun android9SharedManagerReproducesOldFinalizerStoppingNewRequest() {
        val wifi = wifi()
        val service = mock(Class.forName("android.net.wifi.IWifiManager"))
        ReflectionHelpers.setField(wifi, "mService", service)
        start(wifi)
        val old = reservation(wifi)
        old.close()
        start(wifi)
        finalizeReservation(old)
        assertNull(ReflectionHelpers.getField<Any?>(wifi, "mLOHSCallbackProxy"))
        assertEquals(2, mockingDetails(service).invocations.count { it.method.name == "stopLocalOnlyHotspot" })
    }

    @Test fun oldReservationFinalizerCannotStopReplacementOrApplicationWifiRequest() {
        val oldWifi = wifi()
        val replacementWifi = wifi()
        val appWifi = context.getSystemService(WifiManager::class.java)
        assertNotSame(oldWifi, replacementWifi)
        assertNotSame(appWifi, oldWifi)
        val service = mock(Class.forName("android.net.wifi.IWifiManager"))
        for (wifi in listOf(oldWifi, replacementWifi, appWifi)) ReflectionHelpers.setField(wifi, "mService", service)
        start(oldWifi)
        val old = reservation(oldWifi)
        old.close()
        start(replacementWifi)
        start(appWifi)
        finalizeReservation(old)
        assertNotNull(ReflectionHelpers.getField<Any?>(replacementWifi, "mLOHSCallbackProxy"))
        assertNotNull(ReflectionHelpers.getField<Any?>(appWifi, "mLOHSCallbackProxy"))
        assertEquals(1, mockingDetails(service).invocations.count { it.method.name == "stopLocalOnlyHotspot" })
        reservation(replacementWifi).close()
        reservation(appWifi).close()
    }
}
