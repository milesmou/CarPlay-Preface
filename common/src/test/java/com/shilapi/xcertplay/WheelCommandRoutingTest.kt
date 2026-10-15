package com.shilapi.xcertplay

import android.media.AudioManager
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Cross-entry regressions from the six-issue wheel design review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelCommandRoutingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: CarPlayController
    private lateinit var api: MediaBackend
    private var foreground = true
    private var service: WheelKeyService? = null
    private val knobs = mutableListOf<AirPlayKnobState>()
    private var eventTime = 100L

    @Before fun setup() {
        WheelMediaLearning.resetForTest()
        CarPlayBackgroundSession.clear()
        for (name in listOf(WheelMediaBindings.PREFS, "diplay_wheel_map_zoom"))
            app.getSharedPreferences(name, 0).edit().clear().commit()
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, true)
        center()?.close()
        ReflectionHelpers.setField(CarPlayMediaKeys, "mediaCenter", null)
        api = MediaBackend()
        CarPlayMediaKeys.initialize(app, Executor { it.run() }) { _, _ -> api }
        controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying(playing = true))
        `when`(controller.mediaPlayingSnapshot()).thenReturn(true)
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        `when`(controller.requestSiri()).thenReturn(true)
        CarPlayMediaKeys.attach(app, controller) { foreground }
        CarPlayBackgroundSession.active = true
        idle()
    }

    @After fun cleanup() {
        service?.onDestroy()
        CarPlayMediaKeys.detach(controller)
        center()?.close()
        ReflectionHelpers.setField(CarPlayMediaKeys, "mediaCenter", null)
        CarPlayBackgroundSession.clear()
        WheelMediaLearning.resetForTest()
        for (name in listOf(WheelMediaBindings.PREFS, "diplay_wheel_map_zoom"))
            app.getSharedPreferences(name, 0).edit().clear().commit()
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private fun center(): PrefaceMediaCenter? = ReflectionHelpers.getField(CarPlayMediaKeys, "mediaCenter")
    private fun callback(): CarPlayMediaCallback = ReflectionHelpers.getField(CarPlayMediaKeys, "callback")
    private fun observer(): PrefaceTrackKeyInterception = ReflectionHelpers.getField(CarPlayMediaKeys, "trackInterception")
    private fun key(code: Int, down: Boolean) =
        KeyEvent(eventTime, eventTime, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0)
    private fun startMusic() { CarPlayMediaKeys.onMediaAudioChanged(true, controller); idle(); assertEquals(1, api.requests) }

    @Test fun semanticCommandsCannotBypassCallsDisconnectOrMediaOwnership() {
        callback().onSkipToNext()
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        clearInvocations(controller)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        assertFalse(CarPlayMediaKeys.onWindowMediaKey(key(87, true)) == true)
        callback().onSkipToNext(); callback().onSkipToPrevious(); callback().onPause(); callback().onPlay()
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        CarPlayBackgroundSession.active = false
        callback().onPlay()
        CarPlayBackgroundSession.active = true
        foreground = false
        callback().onPause(); callback().onSkipToNext()
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun sessionAndOemActionsRemainSemanticAfterLearningAnotherPhysicalKey() {
        assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.NEXT, KeyEvent.KEYCODE_F1))
        idle()
        callback().onSkipToNext()
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        startMusic()
        advance(800)
        assertTrue(api.command(CarPlayMediaButton.NEXT))
        verify(controller, times(2)).sendMediaButton(CarPlayMediaButton.NEXT)
        advance(800)
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_F1, true)) == true)
        verify(controller, times(3)).sendMediaButton(CarPlayMediaButton.NEXT)
        assertFalse(CarPlayMediaKeys.onWindowMediaKey(key(87, true)) == true)
    }

    @Test fun siriAndCallSuppressionKeepTheSourceAndRecoverWithoutRequestingPlaybackAgain() {
        startMusic()
        assertTrue(center()!!.status().ownsSource)
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(231, true)) == true)
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(231, false)) == true)
        advance(300)
        assertTrue(center()!!.status().ownsSource)
        assertFalse(api.command(CarPlayMediaButton.NEXT))
        callback().onPause()
        verify(controller, never()).sendMediaButton(anyInt())
        advance(1600)
        assertTrue(api.command(CarPlayMediaButton.NEXT))
        assertEquals(1, api.requests)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        advance(300)
        assertTrue(center()!!.status().ownsSource)
        assertFalse(api.command(CarPlayMediaButton.PREVIOUS))
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        advance(300)
        assertTrue(api.command(CarPlayMediaButton.PREVIOUS))
        assertEquals(1, api.requests)
    }

    @Test fun actualSourceLossStillRequiresARealNewPlaybackRequest() {
        startMusic()
        foreground = false
        advance(300)
        assertFalse(center()!!.status().ownsSource)
        foreground = true
        advance(300)
        assertFalse(api.command(CarPlayMediaButton.NEXT))
        assertEquals(1, api.requests)
        CarPlayMediaKeys.onMediaAudioChanged(false, controller); idle()
        CarPlayMediaKeys.onMediaAudioChanged(true, controller); idle()
        assertEquals(2, api.requests)
        assertTrue(api.command(CarPlayMediaButton.NEXT))
    }

    @Test fun observerRestartDiscardsItsHeldStateWhileRejectingRetiredCallbacks() {
        var delivery: ((Boolean, Int) -> Unit)? = null
        val input = object : PrefaceTrackKeyInterception.Backend {
            override fun request(callback: (Boolean, Int) -> Unit) { delivery = callback }
            override fun release() = Unit
        }
        val interception = observer() // Keep the production release hook, replacing only the system API.
        ReflectionHelpers.setField(interception, "backend", input)
        ReflectionHelpers.setField(interception, "failed", false)
        interception.update(true)
        val retired = delivery!!
        retired(true, 305)
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        interception.update(false)
        interception.update(true)
        retired(true, 305); retired(false, 305)
        delivery!!(true, 305)
        verify(controller, times(2)).sendMediaButton(CarPlayMediaButton.NEXT)
        assertEquals(1L, interception.status().receivedEvents)
    }

    @Test fun releasingTheInputObserverDoesNotDiscardAnotherEntrysSiriPress() {
        assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.SIRI, KeyEvent.KEYCODE_F6))
        idle()
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_F6, true)) == true)
        val input = object : PrefaceTrackKeyInterception.Backend {
            override fun request(callback: (Boolean, Int) -> Unit) = Unit
            override fun release() = Unit
        }
        val interception = observer()
        ReflectionHelpers.setField(interception, "backend", input)
        ReflectionHelpers.setField(interception, "failed", false)
        interception.update(true); interception.close()
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_F6, false)) == true)
        verify(controller).requestSiri()
    }

    @Test fun anIneligibleWindowReleaseClearsSiriWithoutStartingItAfterFocusReturns() {
        assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.SIRI, KeyEvent.KEYCODE_F6))
        idle()
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_F6, true)) == true)
        foreground = false
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_F6, false)) == true)
        foreground = true
        eventTime += 100
        CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_F6, false))
        verify(controller, never()).requestSiri()
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_F6, true)) == true)
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(KeyEvent.KEYCODE_F6, false)) == true)
        verify(controller).requestSiri()
    }

    private fun joystickSetup() {
        val phone = Any()
        val wheel = Robolectric.buildService(WheelKeyService::class.java).create().get().also { service = it }
        wheel.session = { phone }
        wheel.knob = { knobs.add(it) }
        WheelZoomSettings.setJoystick(wheel, true)
        WheelZoomSettings.assign(wheel, WheelZoomSettings.Role.JOYSTICK, WheelKey(KeyEvent.KEYCODE_F4, 0, "?"))
        WheelZoomSettings.assign(wheel, WheelZoomSettings.Role.NEXT, WheelKey(87, 0, "?"))
        ReflectionHelpers.callInstanceMethod<Unit>(wheel, "onServiceConnected")
    }
    private fun serviceKey(code: Int, down: Boolean): Boolean = ReflectionHelpers.callInstanceMethod(service!!, "onKeyEvent",
        ReflectionHelpers.ClassParameter(KeyEvent::class.java, key(code, down)))
    private fun servicePress(code: Int) {
        assertTrue(serviceKey(code, true)); assertTrue(serviceKey(code, false)); eventTime += 100
    }

    @Test fun activeJoystickKeepsItsKeysAcrossWindowInputSessionAndOemMediaCallbacks() {
        startMusic()
        joystickSetup()
        servicePress(87)
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        clearInvocations(controller)
        servicePress(KeyEvent.KEYCODE_F4)
        assertTrue(serviceKey(87, true))
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(87, true)) == true)
        assertTrue(CarPlayMediaKeys.handleLearnedMediaKey(true, 305, "preface-intercept") == true)
        assertTrue(api.command(CarPlayMediaButton.NEXT))
        callback().onSkipToNext()
        assertTrue(serviceKey(87, false))
        assertEquals(listOf(1), knobs.map { it.wheel })
        verify(controller, never()).sendMediaButton(anyInt())
        servicePress(KeyEvent.KEYCODE_F4)
        eventTime += 100
        servicePress(87)
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    @Test fun joystickDisableKeepsItsReleaseButReturnsTheNextPressToMedia() {
        joystickSetup()
        servicePress(KeyEvent.KEYCODE_F4)
        assertTrue(serviceKey(87, true))
        WheelZoomSettings.setJoystick(service!!, false)
        assertTrue(serviceKey(87, false))
        eventTime += 100
        servicePress(87)
        assertEquals(listOf(1), knobs.map { it.wheel })
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    private class MediaBackend : PrefaceMediaCenter.Backend {
        lateinit var command: (Int) -> Boolean
        var requests = 0
        override fun init(snapshot: () -> PrefaceMediaCenter.Snapshot, command: (Int) -> Boolean, ready: (Boolean) -> Unit) {
            this.command = command; ready(true)
        }
        override fun register(): Any = Any()
        override fun requestPlay(token: Any): Boolean { requests++; return true }
        override fun publish(token: Any, info: CarPlayNowPlaying) = true
        override fun unregister(token: Any) = true
    }
}
