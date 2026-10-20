package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Looper
import android.view.KeyEvent
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
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PrefaceSiriSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: CarPlayController
    private var foreground = true

    @Before fun setup() {
        WheelMediaLearning.resetForTest()
        CarPlayBackgroundSession.clear()
        app.getSharedPreferences(WheelMediaBindings.PREFS, 0).edit().clear().commit()
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.managesPlaybackAudioFocus).thenReturn(true)
        `when`(controller.requestSiri()).thenReturn(true)
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        attach()
    }

    @After fun cleanup() {
        CarPlayMediaKeys.detach(controller)
        CarPlayBackgroundSession.clear()
        WheelMediaLearning.resetForTest()
        app.getSharedPreferences(WheelMediaBindings.PREFS, 0).edit().clear().commit()
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        idle()
    }

    private fun attach() {
        CarPlayMediaKeys.attach(app, controller) { foreground }
        CarPlayBackgroundSession.active = true
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun voiceEvent() = Intent(PrefaceVoiceButtonReceiver.ACTION)
        .addCategory(Intent.CATEGORY_DEFAULT)
        .putExtra(PrefaceVoiceButtonReceiver.KEY_TYPE, 200231)
        .putExtra(PrefaceVoiceButtonReceiver.KEY_ACTION, 0)
    private fun voiceBroadcast() { app.sendOrderedBroadcast(voiceEvent(), null); idle() }
    private fun key(code: Int, down: Boolean) = KeyEvent(100, 100,
        if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0)

    @Test fun bluetoothVoiceBroadcastWorksWithoutMusicFocusSessionOrAccessibility() {
        CarPlayMediaKeys.detach(controller)
        `when`(controller.usesCarBluetoothAudio).thenReturn(true)
        foreground = false
        attach()

        voiceBroadcast()

        verify(controller).requestSiri()
        verify(controller, never()).sendMediaButton(anyInt())
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "session"))
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusRequest"))
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "trackInterception"))
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "prefaceReceiver"))
    }

    @Test fun visibleCarPlayAcceptsVoiceBroadcastBeforeMusicHasFocus() {
        assertFalse(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        voiceBroadcast()
        verify(controller).requestSiri()
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun visibleVoiceKeyWorksWithoutMakingMediaKeysEligible() {
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(231, true)) == true)
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(key(231, false)) == true)
        verify(controller).requestSiri()
        assertFalse(CarPlayMediaKeys.onWindowMediaKey(key(87, true)) == true)
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun callDisconnectDisabledBindingAndAnotherForegroundSourceKeepOemVoice() {
        var oemRequests = 0
        val oem = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { oemRequests++ }
        }
        app.registerReceiver(oem, IntentFilter(PrefaceVoiceButtonReceiver.ACTION).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        })
        try {
            app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
            voiceBroadcast()
            app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
            CarPlayBackgroundSession.active = false
            voiceBroadcast()
            CarPlayBackgroundSession.active = true
            foreground = false
            voiceBroadcast()
            foreground = true
            assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.SIRI, null))
            idle()
            voiceBroadcast()
            assertEquals(4, oemRequests)
            verify(controller, never()).requestSiri()

            assertTrue(WheelMediaBindings.assign(app, WheelMediaBindings.Action.SIRI, 231))
            idle()
            voiceBroadcast()
            verify(controller).requestSiri()
            assertEquals(4, oemRequests)
        } finally { app.unregisterReceiver(oem) }
    }

    @Test fun retiredVoiceReceiverCannotWakeAReattachedController() {
        `when`(controller.managesPlaybackAudioFocus).thenReturn(false)
        val retired = ReflectionHelpers.getField<PrefaceVoiceButtonReceiver>(CarPlayMediaKeys, "prefaceVoiceReceiver")
        CarPlayMediaKeys.detach(controller)
        attach()
        retired.onReceive(app, voiceEvent())
        verify(controller, never()).requestSiri()
        voiceBroadcast()
        verify(controller).requestSiri()
    }
}
