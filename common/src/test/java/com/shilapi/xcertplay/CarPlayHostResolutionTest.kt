package com.shilapi.xcertplay

import android.os.Handler
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostResolutionTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        set("airPlayIdentity", AirPlayIdentity.generate())
    }

    @After fun tearDown() {
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
    }

    @Test fun hiddenNavigationBarAlwaysUses1920By720() {
        for (width in listOf(1760, 1920, 960)) {
            val display = config(true, width).main
            assertEquals(1920, display.widthPixels)
            assertEquals(720, display.heightPixels)
        }
    }

    @Test fun bluetoothMusicSettingChangesOnlyTheNextAudioOfferAndPreservesTheDisplay() {
        try {
            AirPlayPersistence.saveCarBluetoothAudio(activity, false)
            val current = config(true, 1920)
            AirPlayPersistence.saveCarBluetoothAudio(activity, true)
            val next = config(true, 1920)
            assertFalse(current.disableAudioOutput)
            assertTrue(next.disableAudioOutput)
            assertEquals(current.main, next.main)
            val offer = com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(next)
            assertFalse(offer.containsKey("audioFormats"))
            assertFalse(offer.containsKey("audioLatencies"))
            assertTrue(offer.containsKey("displays"))
            assertTrue(offer.containsKey("hidDevices"))
        } finally { AirPlayPersistence.saveCarBluetoothAudio(activity, false) }
    }

    @Test fun visibleNavigationBarAlwaysUses1760By720() {
        for (width in listOf(1920, 1760, 960)) {
            val display = config(false, width).main
            assertEquals(1760, display.widthPixels)
            assertEquals(720, display.heightPixels)
        }
    }

    @Test fun obsoleteResolutionAndCanvasScaleCannotOverrideEitherFixedSize() {
        for (hidden in listOf(true, false)) {
            for (percent in listOf(30, 73, 160)) {
                AirPlayPersistence.saveDisplayScalePercent(activity, percent)
                AirPlayPersistence.saveUiScalePercent(activity, 75)
                val display = config(hidden, 960).main
                assertEquals(if (hidden) 1920 else 1760, display.widthPixels)
                assertEquals(720, display.heightPixels)
            }
        }
    }

    @Test fun transientMaximumWindowDoesNotChangePhysicalSize() {
        val before = config(true, 1760).main
        set("maximumDetectedWidthPixels", 2250)
        set("maximumDetectedHeightPixels", 1080)
        val after = config(true, 1920).main
        assertEquals(before.widthPhysicalMm, after.widthPhysicalMm)
        assertEquals(before.heightPhysicalMm, after.heightPhysicalMm)
    }

    @Test fun physicalIconSizePreferenceDoesNotChangePixelResolution() {
        for (widthMm in listOf(200, 300, 400)) {
            set("widthPhysicalMm", widthMm)
            assertEquals(1920, config(true, 1920).main.widthPixels)
            assertEquals(1760, config(false, 1920).main.widthPixels)
        }
    }

    private fun config(hidden: Boolean, width: Int): AirPlayConfig {
        set("hideBottomBar", hidden)
        val type = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = type.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, 720)
        return activity.javaClass.getDeclaredMethod("createAirPlayConfig", type)
            .apply { isAccessible = true }.invoke(activity, size) as AirPlayConfig
    }

    private fun get(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
