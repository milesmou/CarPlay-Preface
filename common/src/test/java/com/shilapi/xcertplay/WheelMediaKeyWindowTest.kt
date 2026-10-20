package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.NavigationOutputs
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * The steering wheel's next/previous keys, claimed by the CarPlay window itself.
 *
 * This is the route that has to work when the wheel key service cannot be turned on — a head unit
 * with no ADB has no route to it — so the tests here drive `dispatchKeyEvent` directly rather than
 * going through an accessibility service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelMediaKeyWindowTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var controller: CarPlayController

    @Before fun setUp() {
        NavigationOutputs.endNow()
        CarPlayBackgroundSession.clear()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        host.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        // A session has to exist: outside one the wheel keeps the car's own action.
        controller = mock(CarPlayController::class.java)
        `when`(controller.bluetoothHandoffReady).thenReturn(true)
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        field("controller", controller)
        field("activeAirPlaySession", mock(AirPlaySession::class.java))
    }

    @After fun tearDown() {
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (host.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(host) as ExecutorService).shutdownNow()
        }
        (host.javaClass.getDeclaredField("mainHandler").apply { isAccessible = true }.get(host) as Handler)
            .removeCallbacksAndMessages(null)
        NavigationOutputs.endNow()
        CarPlayBackgroundSession.clear()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun theWheelTrackKeysReachCarPlay() {
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, true)))
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)))
        verify(controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
    }

    @Test fun oneTapSendsOnePress() {
        // A down/up pair and hold repeats must produce one CarPlay press and consume its release.
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, true)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, true, repeat = 1)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, false)))
        verify(controller, times(1)).sendMediaButton(anyInt())
    }

    @Test fun theKeysKeepTheCarsOwnActionDuringACall() {
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 4); string(4, "active-call")
        })
        assertEquals(AudioManager.MODE_NORMAL, host.getSystemService(AudioManager::class.java).mode)
        assertTrue(inCall(host))
        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, true)))
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun theKeysKeepTheCarsOwnActionWithoutASession() {
        field("activeAirPlaySession", null)
        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT, true)))
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun standardPlayPauseReachesCarPlayAndUnknownPrivateKeysAreLeftAlone() {
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false)))
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
        assertFalse(host.dispatchKeyEvent(key(353, true)))
        verify(controller, times(1)).sendMediaButton(anyInt())
    }

    @Test @Config(sdk = [28])
    fun anUnconfirmedBluetoothHandoffStillBlocksWindowAndBroadcastMediaKeys() {
        `when`(controller.bluetoothHandoffReady).thenReturn(false)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        CarPlayMediaKeys.attach(host, controller) { true }
        CarPlayBackgroundSession.active = true
        try {
            val down = key(KeyEvent.KEYCODE_MEDIA_NEXT, true)
            assertFalse(host.dispatchKeyEvent(down))
            host.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(Intent.EXTRA_KEY_EVENT, down))
            shadowOf(Looper.getMainLooper()).idle()
            verify(controller, never()).sendMediaButton(anyInt())
        } finally { CarPlayMediaKeys.detach(controller) }
    }

    @Test @Config(sdk = [28])
    fun prefaceWindowAndStockBroadcastDoNotSkipTwoTracks() {
        val product = Build.PRODUCT
        val model = Build.MODEL
        val incremental = Build.VERSION.INCREMENTAL
        try {
            ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "IHU519G")
            ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "FS11A1")
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", "327")
            `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
            CarPlayMediaKeys.attach(host, controller) { true }
            CarPlayBackgroundSession.active = true
            for ((code, time) in listOf(87 to 100L, 88 to 200L, 87 to 300L)) {
                val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0)
                assertTrue(host.dispatchKeyEvent(down))
                host.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).addCategory(Intent.CATEGORY_DEFAULT)
                    .putExtra(Intent.EXTRA_KEY_EVENT, down))
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(host.dispatchKeyEvent(KeyEvent(time, time + 10, KeyEvent.ACTION_UP, code, 0)))
            }
            verify(controller, times(2)).sendMediaButton(CarPlayMediaButton.NEXT)
            verify(controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
            verify(controller, times(3)).sendMediaButton(anyInt())
        } finally {
            CarPlayMediaKeys.detach(controller)
            shadowOf(Looper.getMainLooper()).idle()
            ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", product)
            ReflectionHelpers.setStaticField(Build::class.java, "MODEL", model)
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", incremental)
        }
    }

    private fun field(name: String, value: Any?) = host.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(host, value)

    private fun key(code: Int, down: Boolean, repeat: Int = 0) =
        KeyEvent(1_000, 1_000, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, repeat, 0, -1, 0)
}
