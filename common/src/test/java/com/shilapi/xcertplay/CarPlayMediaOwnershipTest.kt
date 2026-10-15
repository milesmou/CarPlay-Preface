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
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, true)
    }

    @After fun cleanup() {
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

    @Test fun disablingVolumeSelectionKeepsTrackKeysAndDefaultMediaCenterAttached() {
        val controller = attach()
        CarPlayBackgroundSession.active = true
        NavigationWheelSettings.setEnabled(app, false)
        try {
            assertFalse(CarPlayMediaKeys.volumeKeysEligible(app))
            assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_MEDIA_NEXT)) == true)
            verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
            assertNotNull(ReflectionHelpers.getField<PrefaceMediaCenter>(CarPlayMediaKeys, "mediaCenter"))
        } finally { NavigationWheelSettings.setEnabled(app, true) }
    }

    private fun attach(owner: Any? = null): CarPlayController {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
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
