package com.shilapi.xcertplay

import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayOutputRoute
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
class CarPlayMenuVolumeTest {
    private val audio = mock(AudioManager::class.java)
    private val values = mutableMapOf(3 to 7, 12 to 9)
    private var minimum = 0
    private var bluetooth = false
    private var blocked = false
    private var configuredNavigation = 12
    private var route: CarPlayOutputRoute? = null
    private val volume = CarPlayMenuVolume(audio,
        configuredStream = { if (it) configuredNavigation else 3 },
        bluetoothAudio = { bluetooth }, suppressed = { blocked }, liveRoute = { route })

    @Before fun setup() {
        `when`(audio.getStreamMinVolume(anyInt())).thenAnswer { minimum }
        `when`(audio.getStreamMaxVolume(anyInt())).thenReturn(20)
        `when`(audio.getStreamVolume(anyInt())).thenAnswer { values[it.getArgument<Int>(0)] ?: 0 }
        doAnswer { values[it.getArgument(0)] = it.getArgument(1); null }
            .`when`(audio).setStreamVolume(anyInt(), anyInt(), anyInt())
    }

    @Test fun navigationAndMediaAdjustImmediatelyAndIndependently() {
        volume.apply(true, volume.read(true)!!, 13)
        assertEquals(13, values[12])
        assertEquals(7, values[3])
        volume.apply(false, volume.read(false)!!, 11)
        assertEquals(11, values[3])
        assertEquals(13, values[12])
        verify(audio).setStreamVolume(12, 13, 0)
        verify(audio).setStreamVolume(3, 11, 0)
    }

    @Test fun liveRouteWinsOverSavedNextConnectionPreferences() {
        configuredNavigation = 9
        route = CarPlayOutputRoute(12)
        volume.apply(true, volume.read(true)!!, 14)
        assertEquals(14, values[12])
        verify(audio, never()).setStreamVolume(eq(9), anyInt(), anyInt())
    }

    @Test fun changingRouteOrCallStateDuringADragDoesNotWriteAnotherVolume() {
        val displayed = volume.read(true)!!
        route = CarPlayOutputRoute(3)
        volume.apply(true, displayed, 13)
        route = null
        blocked = true
        volume.apply(true, displayed, 13)
        verify(audio, never()).setStreamVolume(anyInt(), anyInt(), anyInt())
    }

    @Test fun automaticAndActualFallbackRoutesUseTheSharedSystemVolume() {
        configuredNavigation = 0
        assertEquals(3, volume.read(true)?.stream)
        configuredNavigation = 12
        route = CarPlayOutputRoute(null)
        assertEquals(3, volume.read(true)?.stream)
    }

    @Test fun unsupportedNavigationDoesNotSilentlyWriteMediaVolume() {
        `when`(audio.getStreamMaxVolume(12)).thenThrow(IllegalArgumentException("unsupported"))
        assertNull(volume.read(true))
        assertNotNull(volume.read(false))
        volume.apply(true, CarPlayMenuVolume.Level(12, 0, 20, 9), 13)
        verify(audio, never()).setStreamVolume(anyInt(), anyInt(), anyInt())
    }

    @Test fun bluetoothUsesCarMediaAndDoesNotOfferAnIndependentNavigationVolume() {
        bluetooth = true
        route = CarPlayOutputRoute(12)
        assertNull(volume.read(true))
        assertEquals(3, volume.read(false)?.stream)
        volume.apply(false, volume.read(false)!!, 12)
        verify(audio).setStreamVolume(3, 12, 0)
    }

    @Test fun fixedVolumeAndMissingAudioServiceAreUnavailable() {
        `when`(audio.isVolumeFixed).thenReturn(true)
        assertNull(volume.read(false))
        assertNull(CarPlayMenuVolume(null, { 3 }, { false }, { false }).read(false))
    }

    @Test fun nonzeroMinimumIsRespectedAndTheSliderShowsTheActualLevel() {
        minimum = 2
        val controls = controls()
        val slider = sliders(controls).first()
        assertEquals(18, slider.max)
        assertEquals(7, slider.progress)
        change(slider, 0)
        assertEquals(2, values[12])
        assertTrue(descendants(controls).filterIsInstance<TextView>().any { it.text.contains("2/20") })
    }

    @Test fun sharedRoutesSynchronizeBothSlidersAndExplainTheSharedVolume() {
        configuredNavigation = 3
        val controls = controls()
        verify(audio, never()).setStreamVolume(anyInt(), anyInt(), anyInt())
        val sliders = sliders(controls)
        change(sliders.first(), 14)
        assertEquals(listOf(14, 14), sliders.map { it.progress })
        val context = RuntimeEnvironment.getApplication()
        assertTrue(descendants(controls).filterIsInstance<TextView>()
            .any { it.text == context.getString(R.string.settings_volume_shared) })
        verify(audio, times(1)).setStreamVolume(3, 14, 0)
    }

    @Test fun externalVolumeChangesRefreshWithoutWritingOrSavingAnotherLevel() {
        val controls = controls()
        values[3] = 15
        controls.refresh()
        assertEquals(15, sliders(controls).last().progress)
        verify(audio, never()).setStreamVolume(anyInt(), anyInt(), anyInt())
    }

    @Test fun deniedOrIgnoredWritesShowTheSystemValueWithoutCrashing() {
        val controls = controls()
        doThrow(SecurityException("denied")).`when`(audio).setStreamVolume(anyInt(), anyInt(), anyInt())
        change(sliders(controls).first(), 14)
        assertEquals(9, sliders(controls).first().progress)
        doNothing().`when`(audio).setStreamVolume(anyInt(), anyInt(), anyInt())
        change(sliders(controls).first(), 14)
        assertEquals(9, sliders(controls).first().progress)
        assertTrue(descendants(controls).filterIsInstance<TextView>().any {
            it.text == RuntimeEnvironment.getApplication().getString(R.string.settings_volume_adjustment_failed)
        })
    }

    @Test fun suppressionDisablesSlidersAndBluetoothDisablesOnlyNavigation() {
        blocked = true
        val controls = controls()
        assertTrue(sliders(controls).none { it.isEnabled })
        blocked = false
        bluetooth = true
        controls.refresh()
        assertFalse(sliders(controls).first().isEnabled)
        assertTrue(sliders(controls).last().isEnabled)
    }

    private fun controls() = CarPlayMenuVolumeControls(RuntimeEnvironment.getApplication(), DiPlayPalette.DARK, volume)
    private fun sliders(view: View) = descendants(view).filterIsInstance<SeekBar>().toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun change(slider: SeekBar, value: Int) {
        assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value.toFloat()) }))
    }
}
