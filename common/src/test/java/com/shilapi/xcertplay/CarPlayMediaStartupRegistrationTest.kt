package com.shilapi.xcertplay

import android.os.Looper
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
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaStartupRegistrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val tasks = ArrayDeque<Runnable>()
    private val worker = Executor { tasks.add(it) }
    private val backend = Backend()
    private var factories = 0
    private var controller: CarPlayController? = null

    @Before fun setup() {
        release()
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, true)
    }

    @After fun cleanup() {
        controller?.let(CarPlayMediaKeys::detach)
        release()
        drain()
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
    }

    private fun initialize() = CarPlayMediaKeys.initialize(app, worker) { context, _ ->
        assertSame(app, context)
        factories++
        backend
    }

    private fun drain() {
        do {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
            shadowOf(Looper.getMainLooper()).idle()
        } while (tasks.isNotEmpty())
    }

    private fun attach(lines: MutableList<String> = mutableListOf()): CarPlayController {
        val phone = mock(CarPlayController::class.java)
        `when`(phone.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying(title = "Track", playing = true))
        `when`(phone.mediaPlayingSnapshot()).thenReturn(true)
        `when`(phone.sendMediaButton(anyInt())).thenReturn(true)
        CarPlayMediaKeys.attach(app, phone, diagnostic = lines::add) { true }
        controller = phone
        return phone
    }

    @Test fun launchRegistersOnceWithoutAControllerPlaybackClaimOrMediaSession() {
        initialize(); initialize(); drain()
        assertEquals(1, factories)
        assertEquals(1, backend.registrations)
        assertEquals(0, backend.requests)
        assertFalse(backend.command(CarPlayMediaButton.NEXT))
        assertTrue(CarPlayMediaKeys.report().contains("controllerAttached=false"))
        assertTrue(CarPlayMediaKeys.report().contains("mediaSessionActive=false"))
        assertTrue(CarPlayMediaKeys.report().contains("focusRequested=false"))
        assertTrue(CarPlayMediaKeys.report().contains("registered=true sourceOwned=false"))
    }

    @Test fun firstConnectionAdoptsTheStartupTokenAndOnlyRealPlaybackClaimsTheSource() {
        initialize(); drain()
        val registered = ReflectionHelpers.getField<PrefaceMediaCenter>(CarPlayMediaKeys, "mediaCenter")
        val lines = mutableListOf<String>()
        val phone = attach(lines)
        drain()
        assertSame(registered, ReflectionHelpers.getField<PrefaceMediaCenter>(CarPlayMediaKeys, "mediaCenter"))
        assertEquals(1, backend.registrations)
        assertEquals(0, backend.unregistrations)
        assertEquals(0, backend.requests)
        assertFalse(backend.command(CarPlayMediaButton.NEXT))
        assertTrue(lines.any { it == "OEM media center registered" })
        assertTrue(lines.any { it.contains("startup registration adopted") })
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.onMediaAudioChanged(true, phone)
        drain()
        assertEquals(1, backend.requests)
        assertTrue(backend.command(CarPlayMediaButton.NEXT))
        verify(phone).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    @Test fun connectingBeforeSdkReadyKeepsTheStartupInitializationAndQueuedPlayRequest() {
        backend.autoReady = false
        initialize(); drain()
        val phone = attach()
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.onMediaAudioChanged(true, phone)
        drain()
        assertEquals(1, factories)
        assertEquals(0, backend.registrations)
        assertEquals(0, backend.requests)
        backend.ready(true); drain()
        assertEquals(1, backend.registrations)
        assertEquals(1, backend.requests)
    }

    @Test fun disconnectClosesTheAdoptedTokenAndLateSdkCallbacksCannotReachTheNextController() {
        initialize(); drain()
        val first = attach()
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.onMediaAudioChanged(true, first); drain()
        CarPlayMediaKeys.detach(first); drain()
        assertEquals(1, backend.unregistrations)
        assertFalse(backend.command(CarPlayMediaButton.PAUSE))
        backend.ready(true); drain()
        assertEquals(1, backend.registrations)
        val next = attach()
        assertFalse(backend.command(CarPlayMediaButton.NEXT))
        verify(next, never()).sendMediaButton(anyInt())
    }

    private fun release() {
        ReflectionHelpers.getField<CarPlayController?>(CarPlayMediaKeys, "controller")?.let(CarPlayMediaKeys::detach)
        CarPlayMediaKeys.javaClass.getDeclaredMethod("releaseLocked", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(CarPlayMediaKeys, false)
    }

    private class Backend : PrefaceMediaCenter.Backend {
        lateinit var ready: (Boolean) -> Unit
        lateinit var command: (Int) -> Boolean
        var autoReady = true
        var registrations = 0
        var requests = 0
        var unregistrations = 0
        private val token = Any()
        override fun init(snapshot: () -> PrefaceMediaCenter.Snapshot, command: (Int) -> Boolean,
            ready: (Boolean) -> Unit) {
            this.command = command; this.ready = ready
            if (autoReady) ready(true)
        }
        override fun register(): Any { registrations++; return token }
        override fun requestPlay(token: Any): Boolean { requests++; return true }
        override fun publish(token: Any, info: CarPlayNowPlaying) = true
        override fun unregister(token: Any): Boolean { assertSame(this.token, token); unregistrations++; return true }
    }
}
