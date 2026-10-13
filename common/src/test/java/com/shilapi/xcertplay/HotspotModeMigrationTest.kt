package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class HotspotModeMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Test fun temporaryHotspotCanBeSavedWithoutChangingCarHotspotCredentials() {
        prefs.edit().clear().apply()
        AirPlayPersistence.saveManualHotspotSsid(context, "Test car")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "test-password")
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("Test car", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("test-password", AirPlayPersistence.loadManualHotspotPassphrase(context))
    }

    @Test fun freshInstallAndUnknownSelectionUseBuiltInHotspot() {
        prefs.edit().clear().apply()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        prefs.edit().putString("wireless_hotspot_mode", "unknown").apply()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun removedSelectionsMigrateToTemporaryHotspotAndPreserveSavedDetails() {
        for (old in listOf("WIFI_P2P", "EXISTING_WIFI")) {
            prefs.edit().clear().putString("wireless_hotspot_mode", old)
                .putString("manual_hotspot_ssid", "Saved car").apply()
            assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.loadWirelessHotspotMode(context))
            assertEquals("LOCAL_ONLY_HOTSPOT", prefs.getString("wireless_hotspot_mode", null))
            assertEquals("Saved car", AirPlayPersistence.loadManualHotspotSsid(context))
        }
    }

    @Test @Config(sdk = [25]) fun androidWithoutLocalOnlyHotspotKeepsBuiltInHotspot() {
        prefs.edit().putString("wireless_hotspot_mode", "LOCAL_ONLY_HOTSPOT").apply()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }
}
