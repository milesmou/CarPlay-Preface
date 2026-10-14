package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CurrentConfigurationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", 0)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun obsoleteKeysDoNotChangeDefaultsOrGetRewrittenWhenSettingsAreRead() {
        prefs.edit()
            .putInt("display_scale_tenths", 6)
            .putInt("navigation_stream_type", 15)
            .putString("wireless_hotspot_mode", "MANUAL")
            .putString("manual_hotspot_ssid", "old")
            .putString("cluster_turn_card_overlay_position", "LEFT")
            .putString("cluster_turn_card_overlay_size", "LARGE")
            .putInt("cluster_marker_horizontal_step", 2)
            .putInt("cluster_marker_vertical_step", -2)
            .putInt("cluster_small_window_marker_x", -1)
            .putInt("cluster_small_window_marker_y", -2)
            .commit()
        val before = prefs.all
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(context))
        assertEquals(12, AirPlayPersistence.loadNavigationAudioChannel(context))
        assertEquals(60, AirPlayPersistence.loadFps(context))
        assertEquals(76, AirPlayPersistence.loadClusterTurnCardOverlayXPercent(context))
        assertEquals(55, AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(context))
        assertEquals(50, AirPlayPersistence.loadClusterMarkerXPercent(context))
        assertEquals(45, AirPlayPersistence.loadClusterMarkerYPercent(context))
        assertEquals(80, AirPlayPersistence.loadClusterSmallWindowMarkerXPercent(context))
        assertEquals(45, AirPlayPersistence.loadClusterSmallWindowMarkerYPercent(context))
        assertEquals(before, prefs.all)
    }

    @Test fun savedCurrentValuesIncludingBrandNamesAreUsedDirectly() {
        AirPlayPersistence.saveDisplayScalePercent(context, 75)
        AirPlayPersistence.saveNavigationAudioChannel(context, 0)
        AirPlayPersistence.saveFps(context, 30)
        AirPlayPersistence.saveOemLabel(context, "Geely")
        assertEquals(75, AirPlayPersistence.loadDisplayScalePercent(context))
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        assertEquals("Geely", AirPlayPersistence.loadOemLabel(context))
    }
}
