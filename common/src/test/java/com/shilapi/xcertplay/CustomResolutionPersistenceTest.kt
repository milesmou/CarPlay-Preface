package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CustomResolutionPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clearPreferences() { context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().apply() }

    @Test fun customPercentKeepsTheExtendedRange() {
        AirPlayPersistence.saveDisplayScalePercent(context, 150)
        assertEquals(150, AirPlayPersistence.loadDisplayScalePercent(context))
        AirPlayPersistence.saveDisplayScalePercent(context, CarPlayDisplayScale.MAX_PERCENT)
        assertEquals(CarPlayDisplayScale.MAX_PERCENT, AirPlayPersistence.loadDisplayScalePercent(context))
        AirPlayPersistence.saveDisplayScalePercent(context, CarPlayDisplayScale.MAX_PERCENT + 60)
        assertEquals(CarPlayDisplayScale.MAX_PERCENT, AirPlayPersistence.loadDisplayScalePercent(context))
        AirPlayPersistence.saveDisplayScalePercent(context, 10)
        assertEquals(30, AirPlayPersistence.loadDisplayScalePercent(context))
    }

    @Test fun directSavedValuesClamp() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().putInt("display_scale_percent", Int.MAX_VALUE).apply()
        assertEquals(160, AirPlayPersistence.loadDisplayScalePercent(context))
        prefs.edit().putInt("display_scale_percent", Int.MIN_VALUE).apply()
        assertEquals(30, AirPlayPersistence.loadDisplayScalePercent(context))

    }


}
