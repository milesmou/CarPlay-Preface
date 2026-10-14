package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayWheelVolumeRoutingTest {
    private val controller = mock(CarPlayController::class.java)
    private val audio = mock(AudioManager::class.java)
    private val volumes = mutableMapOf(3 to 7, 12 to 9)
    private val diagnostics = mutableListOf<String>()
    private lateinit var context: Context

    @Before fun setup() {
        val application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        context = object : ContextWrapper(application) {
            override fun getSystemService(name: String): Any? = if (name == AUDIO_SERVICE) audio else super.getSystemService(name)
        }
        `when`(audio.getStreamVolume(anyInt())).thenAnswer { volumes[it.getArgument<Int>(0)] ?: 0 }
        `when`(audio.getStreamMaxVolume(anyInt())).thenReturn(20)
        doAnswer { volumes[it.getArgument<Int>(0)] = it.getArgument(1); null }
            .`when`(audio).setStreamVolume(anyInt(), anyInt(), anyInt())
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.mediaPlayingSnapshot()).thenReturn(false)
        CarPlayMediaKeys.attach(context, controller, diagnostic = diagnostics::add) { true }
        CarPlayBackgroundSession.active = true
    }

    @After fun cleanup() {
        CarPlayMediaKeys.detach(controller)
        CarPlayBackgroundSession.clear()
    }

    @Test fun pausedWithoutGuidanceAdjustsOnlyNavigationAndReportsBothVolumes() {
        assertTrue(CarPlayWheelVolume.adjust(context, 1))
        assertEquals(7, volumes[3])
        assertEquals(10, volumes[12])
        verify(audio).setStreamVolume(12, 10, AudioManager.FLAG_SHOW_UI)
        verify(audio, never()).setStreamVolume(eq(3), anyInt(), anyInt())
        assertTrue(diagnostics.any { it.contains("mediaBefore=7 mediaAfter=7") && it.contains("navigationBefore=9 navigationAfter=10") })
    }

    @Test fun playingAdjustsOnlyMedia() {
        `when`(controller.mediaPlayingSnapshot()).thenReturn(true)
        assertTrue(CarPlayWheelVolume.adjust(context, -1))
        assertEquals(6, volumes[3])
        assertEquals(9, volumes[12])
        verify(audio, never()).setStreamVolume(eq(12), anyInt(), anyInt())
    }

    @Test fun anExplicitSharedRouteChangesTheOneSystemVolumeOnlyOnce() {
        AirPlayPersistence.saveNavigationAudioChannel(context, 3)
        assertTrue(CarPlayWheelVolume.adjust(context, 1))
        verify(audio, times(1)).setStreamVolume(3, 8, AudioManager.FLAG_SHOW_UI)
        assertEquals(9, volumes[12])
    }

    @Test fun unknownAutomaticNavigationRouteUsesTheDocumentedMediaFallback() {
        AirPlayPersistence.saveNavigationAudioChannel(context, 0)
        assertTrue(CarPlayWheelVolume.adjust(context, 1))
        verify(audio).setStreamVolume(3, 8, AudioManager.FLAG_SHOW_UI)
        assertEquals(9, volumes[12])
    }
}
