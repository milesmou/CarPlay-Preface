package com.shilapi.xcertplay

import android.content.Intent
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaOwnershipTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()

    @Before fun setup() {
        app.getSharedPreferences(WheelMediaBindings.PREFS, 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, true)
    }

    @After fun cleanup() {
        app.getSharedPreferences(WheelMediaBindings.PREFS, 0).edit().clear().commit()
        controllers.forEach(CarPlayMediaKeys::detach)
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun queuedAudioStartCannotActivateReplacementController() {
        val first = attach()
        CarPlayMediaKeys.onMediaAudioChanged(true, first)
        val second = attach()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(currentSession())
        assertFalse(mediaActive())
        CarPlayMediaKeys.onMediaAudioChanged(true, second)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(currentSession()!!.isActive)
    }

    @Test fun lateAudioStopCannotClearReplacementPlaybackOrSession() {
        val first = attach()
        val second = attach()
        CarPlayMediaKeys.onMediaAudioChanged(true, second)
        shadowOf(Looper.getMainLooper()).idle()
        val current = currentSession()
        // A retired renderer may deliver its stop after replacement attach and audio start.
        CarPlayMediaKeys.onMediaAudioChanged(false, first)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(mediaActive())
        assertSame(current, currentSession())
        CarPlayMediaKeys.onMediaAudioChanged(false, second)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(mediaActive())
    }

    @Test fun oldMediaSessionCannotPauseOrSkipOnReplacementEvenAfterSameControllerReattaches() {
        val first = attach()
        val old = callback()
        val second = attach()
        CarPlayBackgroundSession.active = true
        assertFalse(old.onMediaButtonEvent(mediaIntent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        old.onPlay(); old.onPause(); old.onSkipToNext(); old.onSkipToPrevious()
        verify(first, never()).sendMediaButton(anyInt())
        verify(second, never()).sendMediaButton(anyInt())
        callback().onPlay()
        verify(second).sendMediaButton(CarPlayMediaButton.PLAY)

        val retired = callback()
        CarPlayMediaKeys.detach(second)
        CarPlayMediaKeys.attach(app, second) { true }
        retired.onPause()
        verify(second, never()).sendMediaButton(CarPlayMediaButton.PAUSE)
        callback().onPause()
        verify(second).sendMediaButton(CarPlayMediaButton.PAUSE)
    }

    @Test fun adoptingForegroundRetainsRendererOwnerAndDoesNotRevivePreviousOwner() {
        val owner = Any()
        val current = attach(owner)
        val adoptedReport = mutableListOf<String>()
        CarPlayMediaKeys.updateForeground(current) { false }
        CarPlayBackgroundSession.active = true
        assertFalse(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_MEDIA_NEXT)) == true)
        CarPlayMediaKeys.updateForeground(current, diagnostic = adoptedReport::add) { true }
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_MEDIA_NEXT)) == true)
        CarPlayMediaKeys.onMediaAudioChanged(true, owner)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(mediaActive())
        assertTrue(adoptedReport.any { it.contains("Media stream active=true") })
        val previous = mock(CarPlayController::class.java)
        CarPlayMediaKeys.updateForeground(previous, diagnostic = { error("retired reporter") }) { false }
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)) == true)
    }

    @Test fun explicitVideoPlayAndPauseStayIdempotentWhileCombinedKeyToggles() {
        CarPlayVideo.activity = mock(CarPlayVideoActivity::class.java)
        try {
            CarPlayVideo.setPlaying(false)
            assertTrue(CarPlayVideo.onMediaKey(CarPlayMediaButton.PLAY))
            assertTrue(CarPlayVideo.onMediaKey(CarPlayMediaButton.PLAY))
            assertTrue(CarPlayVideo.playing)
            assertTrue(CarPlayVideo.onMediaKey(CarPlayMediaButton.PAUSE))
            assertTrue(CarPlayVideo.onMediaKey(CarPlayMediaButton.PAUSE))
            assertFalse(CarPlayVideo.playing)
            assertTrue(CarPlayVideo.onMediaKey(CarPlayMediaButton.PLAY_PAUSE))
            assertTrue(CarPlayVideo.playing)
            assertFalse(CarPlayVideo.onMediaKey(99))
            assertTrue(CarPlayVideo.playing)
        } finally {
            CarPlayVideo.activity = null
            CarPlayVideo.setPlaying(false)
        }
    }

    @Test fun transportPauseReportIdentifiesCallingPackageWithoutProcessIdentifiers() {
        val controller = attach()
        CarPlayBackgroundSession.active = true
        val lines = mutableListOf<String>()
        CarPlayMediaKeys.attach(app, controller, diagnostic = lines::add) { true }
        val platformSession = mock(MediaSession::class.java)
        `when`(platformSession.currentControllerInfo).thenReturn(
            MediaSessionManager.RemoteUserInfo("com.ecarx.multimedia", 1234, 5678))
        ReflectionHelpers.setField(CarPlayMediaKeys, "session", platformSession)
        callback().onPause()
        verify(controller).sendMediaButton(CarPlayMediaButton.PAUSE)
        assertTrue(lines.any { it.contains("source=pause caller=com.ecarx.multimedia") })
        assertFalse(lines.any { it.contains("1234") || it.contains("5678") })
    }

    @Test fun legacyVolumePreferenceCannotRestoreAThirdModeOrChangeCurrentMediaOwnership() {
        val legacy = app.getSharedPreferences("diplay_navigation_wheel", 0)
        try {
            for (enabled in listOf(true, false)) {
                legacy.edit().putBoolean("enabled", enabled).putBoolean("authorized", true).commit()
                MusicWheelMode.save(app, MusicWheelMode.CARPLAY)
                assertEquals(MusicWheelMode.CARPLAY, MusicWheelMode.load(app))
                attach()
                CarPlayBackgroundSession.active = true
                assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_MEDIA_NEXT)) == true)
                assertNotNull(ReflectionHelpers.getField<PrefaceMediaCenter>(CarPlayMediaKeys, "mediaCenter"))
                MusicWheelMode.save(app, MusicWheelMode.CAR_BLUETOOTH)
                assertEquals(MusicWheelMode.CAR_BLUETOOTH, MusicWheelMode.load(app))
                // Saving a transport must not take keys away from the existing CarPlay controller.
                assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)) == true)
                assertEquals(enabled, legacy.getBoolean("enabled", !enabled))
            }
        } finally {
            MusicWheelMode.save(app, MusicWheelMode.CARPLAY)
            legacy.edit().clear().commit()
        }
    }

    @Test fun learnedKeyDispatchesOnceAcrossSourcesAndDisabledDefaultCannotStillSend() {
        val controller = attach()
        CarPlayBackgroundSession.active = true
        assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.NEXT, KeyEvent.KEYCODE_F1))
        shadowOf(Looper.getMainLooper()).idle()
        val down = KeyEvent(150, 150, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1, 0)
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(down) == true)
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(down) == true)
        CarPlayMediaKeys.handleLearnedMediaKey(true, KeyEvent.KEYCODE_F1, "preface-intercept")
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
        assertFalse(CarPlayMediaKeys.onWindowMediaKey(key(87)) == true)
        assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.NEXT, null))
        shadowOf(Looper.getMainLooper()).idle()
        callback().onSkipToNext()
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    @Test fun disabledSiriCannotBeForwardedThroughTheMediaWindow() {
        val controller = attach()
        CarPlayBackgroundSession.active = true
        WheelMediaBindings.assign(app, WheelMediaBindings.Action.SIRI, null)
        shadowOf(Looper.getMainLooper()).idle()
        CarPlayMediaKeys.onWindowMediaKey(KeyEvent(200, 200, KeyEvent.ACTION_DOWN, 231, 0))
        CarPlayMediaKeys.onWindowMediaKey(KeyEvent(200, 210, KeyEvent.ACTION_UP, 231, 0))
        verify(controller, never()).requestSiri()
    }

    private fun attach(owner: Any? = null): CarPlayController {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.bluetoothHandoffReady).thenReturn(true)
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        CarPlayMediaKeys.attach(app, controller, mediaOwner = owner ?: controller) { true }
        return controller
    }

    private fun currentSession(): MediaSession? = ReflectionHelpers.getField(CarPlayMediaKeys, "session")
    private fun mediaActive(): Boolean = ReflectionHelpers.getField(CarPlayMediaKeys, "mediaAudioActive")
    private fun callback(): CarPlayMediaCallback = ReflectionHelpers.getField(CarPlayMediaKeys, "callback")
    private fun key(code: Int) = KeyEvent(100, 100, KeyEvent.ACTION_DOWN, code, 0)
    private fun mediaIntent(code: Int) = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, key(code))
}
