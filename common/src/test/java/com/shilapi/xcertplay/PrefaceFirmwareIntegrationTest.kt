package com.shilapi.xcertplay

import android.content.Intent
import android.os.Build
import android.os.Looper
import android.media.AudioManager
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.mockito.Mockito.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class PrefaceFirmwareIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var product: String
    private lateinit var model: String
    private lateinit var incremental: String
    private var controller: CarPlayController? = null

    @Before fun setup() {
        product = Build.PRODUCT; model = Build.MODEL; incremental = Build.VERSION.INCREMENTAL
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "test-product")
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "test-model")
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", "test-build")
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }
    @After fun cleanup() {
        controller?.let(CarPlayMediaKeys::detach)
        CarPlayBackgroundSession.clear()
        shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", product)
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", model)
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", incremental)
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun unidentifiedDeviceUsesPrefaceAudioDefaults() {
        assertEquals(3, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(12, AirPlayPersistence.loadNavigationAudioChannel(context))
        assertEquals(12, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun changingFirmwareDoesNotChangePrefaceDefaults() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", "328")
        assertEquals(12, AirPlayPersistence.loadNavigationAudioChannel(context))
        assertEquals(3, AirPlayPersistence.loadMediaAudioChannel(context))
    }

    @Test fun savedAutomaticAndCustomRoutesTakePrecedence() {
        AirPlayPersistence.saveMediaAudioChannel(context, 0)
        AirPlayPersistence.saveNavigationAudioChannel(context, 0)
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        AirPlayPersistence.saveNavigationAudioChannel(context, 9)
        assertEquals(9, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun originalFirmwareBroadcastPairsAndRepeatsForwardEachTapOnlyOnce() {
        val sent = mutableListOf<Int>()
        val receiver = PrefaceMediaButtonReceiver({ true }, sent::add)
        fun broadcast(action: Int, key: Int, downTime: Long, repeat: Int = 0) {
            receiver.onReceive(context, Intent(Intent.ACTION_MEDIA_BUTTON).addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(downTime, downTime, action, key, repeat)))
        }
        broadcast(KeyEvent.ACTION_DOWN, 87, 100)
        broadcast(KeyEvent.ACTION_DOWN, 87, 100, 1)
        broadcast(KeyEvent.ACTION_UP, 87, 100)
        broadcast(KeyEvent.ACTION_DOWN, 88, 200)
        broadcast(KeyEvent.ACTION_UP, 88, 200)
        broadcast(KeyEvent.ACTION_DOWN, 85, 300)
        broadcast(KeyEvent.ACTION_UP, 85, 300)
        assertEquals(listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.PLAY_PAUSE), sent)
    }

    @Test fun mediaSessionAndBroadcastShareDeduplication() {
        val sent = mutableListOf<Int>()
        val receiver = PrefaceMediaButtonReceiver({ true }, sent::add)
        val callback = CarPlayMediaCallback(hardware = receiver::handle, send = { index, _ -> sent += index })
        val event = KeyEvent(123, 123, KeyEvent.ACTION_DOWN, 87, 0)
        val broadcast = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)
        receiver.onReceive(context, broadcast)
        assertTrue(callback.onMediaButtonEvent(broadcast))
        assertEquals(listOf(CarPlayMediaButton.NEXT), sent)
    }

    @Test fun nextAndPreviousUseOneDispatchAcrossWindowSessionAndBroadcast() {
        val active = mock(CarPlayController::class.java).also { controller = it }
        `when`(active.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(active.sendMediaButton(anyInt())).thenReturn(true)
        CarPlayMediaKeys.attach(context, active) { true }
        CarPlayBackgroundSession.active = true
        val callback = ReflectionHelpers.getField<CarPlayMediaCallback>(CarPlayMediaKeys, "callback")
        fun press(key: Int, time: Long) {
            val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0)
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(Intent.EXTRA_KEY_EVENT, down)
            assertTrue(CarPlayMediaKeys.onWindowMediaKey(down) == true)
            assertTrue(callback.onMediaButtonEvent(intent))
            context.sendBroadcast(intent)
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(time, time + 10, KeyEvent.ACTION_UP, key, 0)))
            shadowOf(Looper.getMainLooper()).idle()
        }
        press(KeyEvent.KEYCODE_MEDIA_NEXT, 100)
        press(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 200)
        press(KeyEvent.KEYCODE_MEDIA_NEXT, 300)
        inOrder(active).apply {
            verify(active).sendMediaButton(CarPlayMediaButton.NEXT)
            verify(active).sendMediaButton(CarPlayMediaButton.PREVIOUS)
            verify(active).sendMediaButton(CarPlayMediaButton.NEXT)
        }
        verify(active, times(3)).sendMediaButton(anyInt())
        CarPlayMediaKeys.detach(active)
        assertNull(CarPlayMediaKeys.onWindowMediaKey(KeyEvent(400, 400, KeyEvent.ACTION_DOWN, 87, 0)))
    }

    @Test fun backgroundTrackKeysRequireMediaOwnershipAndStopWhenAnotherSourceTakesFocus() {
        val active = mock(CarPlayController::class.java).also { controller = it }
        `when`(active.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(active.sendMediaButton(anyInt())).thenReturn(true)
        CarPlayMediaKeys.attach(context, active)
        CarPlayBackgroundSession.active = true
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        fun broadcast(key: Int, time: Long) {
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0)))
            shadowOf(Looper.getMainLooper()).idle()
        }
        broadcast(87, 100)
        verify(active, never()).sendMediaButton(anyInt())
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        broadcast(87, 200)
        broadcast(88, 300)
        verify(active).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(active).sendMediaButton(CarPlayMediaButton.PREVIOUS)
        val request = ReflectionHelpers.getField<AudioFocusRequestCompat>(CarPlayMediaKeys, "focusRequest")
        ReflectionHelpers.getField<AudioManager.OnAudioFocusChangeListener>(request, "listener")
            .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        shadowOf(Looper.getMainLooper()).idle()
        broadcast(87, 400)
        verify(active, times(2)).sendMediaButton(anyInt())
    }

    @Test fun failedDispatchCanRetryThroughAnotherDeliveryPath() {
        var accepts = false
        val delivered = mutableListOf<Int>()
        val receiver = PrefaceMediaButtonReceiver({ true }) {
            if (accepts) { delivered += it; true } else false
        }
        val event = KeyEvent(100, 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0)
        assertFalse(receiver.handle(event))
        accepts = true
        assertTrue(receiver.handle(event))
        assertTrue(receiver.handle(event))
        assertEquals(listOf(CarPlayMediaButton.NEXT), delivered)
    }

    @Test fun inputInterceptionReleasesOnFocusLossEvenWithCarPlayInForeground() {
        val active = mock(CarPlayController::class.java).also { controller = it }
        `when`(active.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        CarPlayMediaKeys.attach(context, active) { true }
        CarPlayBackgroundSession.active = true
        var requests = 0
        var releases = 0
        val input = object : PrefaceTrackKeyInterception.Backend {
            override fun request(callback: (Boolean, Int) -> Unit) { requests++ }
            override fun release() { releases++ }
        }
        ReflectionHelpers.setField(CarPlayMediaKeys, "trackInterception", PrefaceTrackKeyInterception(input) {})
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requests)
        val request = ReflectionHelpers.getField<AudioFocusRequestCompat>(CarPlayMediaKeys, "focusRequest")
        ReflectionHelpers.getField<AudioManager.OnAudioFocusChangeListener>(request, "listener")
            .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, releases)
        CarPlayMediaKeys.detach(active)
        assertEquals(1, releases)
    }

    @Test fun physicalCallbackAndOemBroadcastForwardTheReportedTapOnlyOnce() {
        val active = mock(CarPlayController::class.java).also { controller = it }
        `when`(active.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(active.sendMediaButton(CarPlayMediaButton.NEXT)).thenReturn(true)
        val diagnostics = mutableListOf<String>()
        CarPlayMediaKeys.attach(context, active, diagnostic = diagnostics::add) { true }
        CarPlayBackgroundSession.active = true
        ReflectionHelpers.callInstanceMethod<Boolean>(CarPlayMediaKeys, "dispatchMedia",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, CarPlayMediaButton.NEXT),
            ReflectionHelpers.ClassParameter.from(String::class.java, "preface-intercept"))
        val event = KeyEvent(100, 246, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0)
        assertTrue(CarPlayMediaKeys.onWindowMediaKey(event) == true)
        verify(active, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
        assertTrue(diagnostics.any { it.contains("suppressed=paired-wheel-echo") })
    }

    @Test fun privateObserverNeverRequestsOrAdjustsVolumeAlongsideTheVehicle() {
        val active = mock(CarPlayController::class.java).also { controller = it }
        `when`(active.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        CarPlayMediaKeys.attach(context, active) { true }
        CarPlayBackgroundSession.active = true
        val requests = mutableListOf<Boolean>()
        lateinit var callback: (Boolean, Int) -> Unit
        val input = object : PrefaceTrackKeyInterception.Backend {
            override fun request(next: (Boolean, Int) -> Unit) = request(next, false)
            override fun request(next: (Boolean, Int) -> Unit, volumeKeys: Boolean) {
                requests += volumeKeys
                callback = next
            }
            override fun release() = Unit
        }
        var adjustments = 0
        ReflectionHelpers.setField(CarPlayMediaKeys, "trackInterception",
            PrefaceTrackKeyInterception(input, volume = { _, _ -> adjustments++ }) {})
        ReflectionHelpers.callInstanceMethod<Unit>(CarPlayMediaKeys, "refreshTrackOwnership")
        shadowOf(Looper.getMainLooper()).idle()
        callback(true, 301)
        callback(false, 301)
        assertEquals(listOf(false), requests)
        assertEquals(0, adjustments)
    }

    @Test fun receiverExistsOnlyDuringOwnershipAndBluetoothDoesNotDoubleForward() {
        val active = mock(CarPlayController::class.java).also { controller = it }
        `when`(active.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(active.sendMediaButton(CarPlayMediaButton.NEXT)).thenReturn(true)
        CarPlayMediaKeys.attach(context, active) { true }
        CarPlayBackgroundSession.active = true
        assertNotNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "prefaceReceiver"))
        fun broadcast(time: Long) {
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(time, time, KeyEvent.ACTION_DOWN, 87, 0)))
            shadowOf(Looper.getMainLooper()).idle()
        }
        broadcast(123)
        AirPlayPersistence.saveCarBluetoothAudio(context, true)
        broadcast(124)
        verify(active, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
        CarPlayMediaKeys.detach(active)
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "prefaceReceiver"))
    }

    @Test fun ineligiblePlaybackAndUnsupportedKeysCannotControlCarPlay() {
        val sent = mutableListOf<Int>()
        var eligible = false
        val receiver = PrefaceMediaButtonReceiver({ eligible }, sent::add)
        assertFalse(receiver.handle(KeyEvent(1, 1, KeyEvent.ACTION_DOWN, 87, 0)))
        eligible = true
        assertFalse(receiver.handle(KeyEvent(2, 2, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0)))
        receiver.onReceive(context, Intent("other.action").putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(0, 87)))
        assertTrue(sent.isEmpty())
    }
}
