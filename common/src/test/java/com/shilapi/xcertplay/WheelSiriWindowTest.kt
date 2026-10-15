package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.ExecutorService
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

/** Window, optional key service and settings all use the same current Siri binding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelSiriWindowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var host: CarPlayHostActivity
    private lateinit var controller: CarPlayController
    private var service: WheelKeyService? = null
    private var foreground = true
    private var eventTime = 1_000L

    @Before fun setup() {
        WheelMediaLearning.resetForTest()
        CarPlayBackgroundSession.clear()
        for (name in listOf(WheelMediaBindings.PREFS, "diplay_wheel_map_zoom"))
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, true)
        controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.requestSiri()).thenReturn(true)
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        CarPlayMediaKeys.attach(app, controller) { foreground }
        CarPlayBackgroundSession.active = true
        idle()
    }

    @After fun cleanup() {
        service?.onDestroy()
        CarPlayMediaKeys.detach(controller)
        CarPlayBackgroundSession.clear()
        WheelMediaLearning.resetForTest()
        for (name in listOf(WheelMediaBindings.PREFS, "diplay_wheel_map_zoom"))
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
            ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun bind(code: Int?) {
        assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.SIRI, code))
        idle()
    }
    private fun event(code: Int, down: Boolean, repeat: Int = 0) =
        KeyEvent(eventTime, eventTime, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, repeat)
    private fun press(code: Int) {
        assertTrue(host.dispatchKeyEvent(event(code, true)))
        assertTrue(host.dispatchKeyEvent(event(code, false)))
        eventTime += 100
    }

    @Test fun defaultVoiceAndLearnedKeyUseOneReleaseAndSharedDebounce() {
        assertTrue(host.dispatchKeyEvent(event(231, true)))
        assertTrue(host.dispatchKeyEvent(event(231, true, repeat = 1)))
        verify(controller, never()).requestSiri()
        assertTrue(host.dispatchKeyEvent(event(231, false)))
        verify(controller).requestSiri()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        bind(KeyEvent.KEYCODE_F6)
        press(KeyEvent.KEYCODE_F6)
        verify(controller, times(2)).requestSiri()
    }

    @Test fun disablingTheCurrentBindingCannotFallBackToObsoleteSiriPreferences() {
        app.getSharedPreferences("diplay_wheel_map_zoom", 0).edit()
            .putBoolean("siri_key", true).putString("key_SIRI", WheelKey(KeyEvent.KEYCODE_F6, 0, "?").encode()).commit()
        bind(null)
        host.dispatchKeyEvent(event(231, true)); host.dispatchKeyEvent(event(231, false))
        host.dispatchKeyEvent(event(KeyEvent.KEYCODE_F6, true)); host.dispatchKeyEvent(event(KeyEvent.KEYCODE_F6, false))
        verify(controller, never()).requestSiri()
    }

    @Test fun currentSiriBindingHonorsCallsAndDisconnectedSessions() {
        bind(KeyEvent.KEYCODE_F6)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        host.dispatchKeyEvent(event(KeyEvent.KEYCODE_F6, true)); host.dispatchKeyEvent(event(KeyEvent.KEYCODE_F6, false))
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        CarPlayBackgroundSession.active = false
        ReflectionHelpers.setField(host, "activeAirPlaySession", null)
        host.dispatchKeyEvent(event(KeyEvent.KEYCODE_F6, true)); host.dispatchKeyEvent(event(KeyEvent.KEYCODE_F6, false))
        verify(controller, never()).requestSiri()
    }

    @Test fun aMediaKeyRemappedToSiriReceivesItsReleaseThroughTheSameHandler() {
        assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.NEXT, null))
        bind(87)
        press(87)
        verify(controller, times(1)).requestSiri()
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun bluetoothKeyServiceUsesTheNewSiriBindingWithoutASecondSiriScheme() {
        CarPlayMediaKeys.detach(controller)
        `when`(controller.usesCarBluetoothAudio).thenReturn(true)
        foreground = false
        CarPlayMediaKeys.attach(app, controller) { foreground }
        bind(KeyEvent.KEYCODE_F6)
        val wheel = Robolectric.buildService(WheelKeyService::class.java).create().get().also { service = it }
        ReflectionHelpers.callInstanceMethod<Unit>(wheel, "onServiceConnected")
        for (down in listOf(true, false)) {
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(wheel, "onKeyEvent",
                ReflectionHelpers.ClassParameter(KeyEvent::class.java, event(KeyEvent.KEYCODE_F6, down))))
        }
        verify(controller, times(1)).requestSiri()
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun sharedServiceSetupAndJoystickStayAvailableWithTheCurrentSiriChoice() {
        val settings = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        settings.setTheme(android.R.style.Theme_Material_NoActionBar)
        bind(KeyEvent.KEYCODE_F6)
        WheelZoomSettings.setJoystick(settings, true)
        val controls = LinearLayout(settings)
        ReflectionHelpers.callInstanceMethod<Unit>(settings, "wheelKeysSettings",
            ReflectionHelpers.ClassParameter(LinearLayout::class.java, controls))
        val labels = views(controls).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        for (id in listOf(R.string.wheel_keys_service_off, R.string.wheel_keys_enable_adb, R.string.wheel_keys_open_settings))
            assertEquals(1, labels.count { it == settings.getString(id) })
        assertTrue(labels.any { it.startsWith(settings.getString(R.string.settings_wheel_siri) + " · ") })
        assertTrue(labels.contains(settings.getString(R.string.settings_wheel_takeover_title)))
        assertFalse(views(controls).filterIsInstance<Switch>().any {
            it.contentDescription == settings.getString(R.string.settings_wheel_siri)
        })
        assertTrue(views(controls).filterIsInstance<Switch>().any {
            it.contentDescription == settings.getString(R.string.wheel_joystick)
        })
        ReflectionHelpers.getField<Handler>(settings, "handler").removeCallbacksAndMessages(null)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
