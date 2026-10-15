package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1920dp-h720dp-land-mdpi")
class RemovedClusterMapTest {
    private val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()

    @After fun cleanup() {
        host.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        for (name in listOf("diplay_split_screen", "diplay_carplay_rotation", "diplay_side_panel", "diplay_carplay_dock")) {
            host.getSharedPreferences(name, 0).edit().clear().commit()
        }
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
        }
    }

    @Test fun legacySavedMapSettingsCannotReenableTheIndependentStream() {
        host.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("cluster_map_enabled", true).putBoolean("center_map_overlay", true)
            .putBoolean("launcher_map_sharing", true).commit()
        val config = config()
        assertNull(config.cluster)
        assertEquals(1920, config.main.widthPixels)
        assertEquals(720, config.main.heightPixels)
    }

    @Test fun oldExperimentalDisplaySwitchesCannotAddSquareSplitOrPanelAreas() {
        for (name in listOf("diplay_split_screen", "diplay_carplay_rotation", "diplay_side_panel")) {
            host.getSharedPreferences(name, 0).edit().putBoolean("enabled", true).commit()
        }
        host.getSharedPreferences("diplay_side_panel", 0).edit().putInt("carplay_sixths", 2).commit()
        val automatic = config()
        assertEquals(1920, automatic.main.widthPixels)
        assertEquals(720, automatic.main.heightPixels)
        assertNull(automatic.main.viewAreas)

        CarPlayDock.save(host, CarPlayDock.BOTTOM)
        val dock = config()
        val areas = dock.main.viewAreas!!
        assertEquals(2, areas.size)
        assertTrue(areas.all { it.width == 1920 && it.height == 720 && it.originX == 0 && it.originY == 0 })
    }

    private fun config(): AirPlayConfig {
        ReflectionHelpers.setField(host, "airPlayIdentity", AirPlayIdentity.generate())
        ReflectionHelpers.callInstanceMethod<Unit>(host, "loadPersistedSettings")
        val sizeType = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeType.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 720)
        return host.javaClass.getDeclaredMethod("createAirPlayConfig", sizeType)
            .apply { isAccessible = true }.invoke(host, size) as AirPlayConfig
    }

    @Test fun externalLauncherMapServiceIsNotExportedAnymore() {
        val app = RuntimeEnvironment.getApplication()
        val services = app.packageManager.getPackageInfo(app.packageName,
            android.content.pm.PackageManager.GET_SERVICES).services.orEmpty()
        assertFalse(services.any { it.name.endsWith(".MapEmbedService") })
    }
}
