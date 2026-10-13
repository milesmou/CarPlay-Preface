package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayAudioVolumeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val media = Any()
    private val guidance = Any()
    private var mediaGain = -1f
    private var navigationGain = -1f

    @Before fun setup() {
        context.getSharedPreferences("carplay_audio_volume", 0).edit().clear().commit()
        CarPlayAudioVolume.register(context, media, false) { mediaGain = it; true }
        CarPlayAudioVolume.register(context, guidance, true) { navigationGain = it; true }
    }
    @After fun cleanup() {
        CarPlayAudioVolume.unregister(media)
        CarPlayAudioVolume.unregister(guidance)
        context.getSharedPreferences("carplay_audio_volume", 0).edit().clear().commit()
    }

    @Test fun independentVolumesApplyImmediatelyAndSurviveRendererReplacement() {
        CarPlayAudioVolume.save(context, false, 70)
        CarPlayAudioVolume.save(context, true, 35)
        assertEquals(0.7f, mediaGain, 0.001f)
        assertEquals(0.35f, navigationGain, 0.001f)
        CarPlayAudioVolume.unregister(guidance)
        CarPlayAudioVolume.register(context, guidance, true) { navigationGain = it; true }
        assertEquals(0.35f, navigationGain, 0.001f)
        assertEquals(0.7f, mediaGain, 0.001f)
    }

    @Test fun focusDuckAndRestorePreserveTheSavedMediaVolume() {
        CarPlayAudioVolume.save(context, false, 40)
        CarPlayAudioVolume.setFocusGain(media, 0.2f)
        assertEquals(0.08f, mediaGain, 0.001f)
        CarPlayAudioVolume.save(context, false, 60)
        assertEquals(0.12f, mediaGain, 0.001f)
        CarPlayAudioVolume.setFocusGain(media, 1f)
        assertEquals(0.6f, mediaGain, 0.001f)
        assertEquals(0.5f, navigationGain, 0.001f)
    }

    @Test fun guidanceWheelAdjustmentNeverChangesMediaAndStopsAfterRendererCloses() {
        CarPlayAudioVolume.save(context, true, 50)
        assertTrue(CarPlayAudioVolume.adjustNavigation(context, 1))
        assertEquals(55, CarPlayAudioVolume.percent(context, true))
        assertEquals(0.5f, mediaGain, 0.001f)
        CarPlayAudioVolume.unregister(guidance)
        assertFalse(CarPlayAudioVolume.adjustNavigation(context, -1))
        assertEquals(55, CarPlayAudioVolume.percent(context, true))
    }

    @Test fun failedVolumeWritePassesTheKeyBackAndPreservesThePreference() {
        CarPlayAudioVolume.save(context, true, 50)
        CarPlayAudioVolume.register(context, guidance, true) { false }
        assertFalse(CarPlayAudioVolume.adjustNavigation(context, 1))
        assertEquals(50, CarPlayAudioVolume.percent(context, true))
    }

    @Test fun volumeLimitsAndMuteAreHonored() {
        CarPlayAudioVolume.save(context, true, 500)
        assertTrue(CarPlayAudioVolume.adjustNavigation(context, 1))
        assertEquals(100, CarPlayAudioVolume.percent(context, true))
        CarPlayAudioVolume.save(context, false, -1)
        assertEquals(0f, mediaGain, 0.001f)
        assertEquals(1f, navigationGain, 0.001f)
    }
}
