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

    private fun attach(lines: MutableList<String> = mutableListOf(), delegated: Boolean = false): CarPlayController {
        val phone = mock(CarPlayController::class.java)
        `when`(phone.managesPlaybackAudioFocus).thenReturn(delegated)
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

    @Test fun rendererFocusIsSharedWithKeysAndOemSourceWithoutASecondRequest() {
        initialize(); drain()
        val phone = attach(delegated = true)
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.onMediaAudioChanged(true, phone); drain()
        assertEquals(0, backend.requests)
        assertTrue(CarPlayMediaKeys.report().contains("focusRequested=false focusHeld=false"))
        assertTrue(CarPlayMediaKeys.report().contains("mediaSessionActive=false"))
        CarPlayMediaKeys.onPlaybackFocusChanged(phone, true); drain()
        assertEquals(1, backend.requests)
        assertTrue(CarPlayMediaKeys.report().contains("focusRequested=false focusHeld=true"))
        assertTrue(CarPlayMediaKeys.report().contains("mediaSessionActive=true"))
        assertTrue(backend.command(CarPlayMediaButton.NEXT))
        CarPlayMediaKeys.onPlaybackFocusChanged(phone, false); drain()
        assertFalse(backend.command(CarPlayMediaButton.NEXT))
        assertTrue(CarPlayMediaKeys.report().contains("mediaSessionActive=false"))
        CarPlayMediaKeys.onPlaybackFocusChanged(phone, true); drain()
        assertEquals(2, backend.requests)
    }

    @Test fun oldPlaybackFocusGrantCannotClaimReplacementSourceAndPausedGainDoesNotRequestPlay() {
        initialize(); drain()
        val old = attach(delegated = true)
        CarPlayMediaKeys.onPlaybackFocusChanged(old, true)
        val current = attach(delegated = true)
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.onMediaAudioChanged(true, current); drain()
        assertEquals(0, backend.requests)
        assertTrue(CarPlayMediaKeys.report().contains("focusHeld=false"))
        CarPlayMediaKeys.onMediaAudioChanged(false, current)
        CarPlayMediaKeys.onPlaybackFocusChanged(current, true); drain()
        assertEquals(0, backend.requests)
        CarPlayMediaKeys.onMediaAudioChanged(true, current); drain()
        assertEquals(1, backend.requests)
    }

    @Test fun bluetoothRouteDoesNotClaimMusicFocusSourceOrMediaKeysEvenOnUnexpectedAudio() {
        initialize(); drain()
        val phone = mock(CarPlayController::class.java)
        controller = phone
        `when`(phone.usesCarBluetoothAudio).thenReturn(true)
        `when`(phone.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying(title = "Bluetooth track", playing = true))
        `when`(phone.mediaPlayingSnapshot()).thenReturn(true)
        CarPlayMediaKeys.attach(app, phone) { true }
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.onMediaAudioChanged(true, phone)
        phone.playbackListener?.invoke(true)
        drain()
        assertEquals(0, backend.requests)
        assertFalse(backend.command(CarPlayMediaButton.NEXT))
        assertTrue(CarPlayMediaKeys.report().contains("carBluetoothRoute=true"))
        assertTrue(CarPlayMediaKeys.report().contains("mediaSessionActive=false"))
        assertTrue(CarPlayMediaKeys.report().contains("focusRequested=false"))
        assertTrue(CarPlayMediaKeys.report().contains("broadcastRegistered=false"))
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "trackInterception"))
        assertNull(CarPlayMediaKeys.onWindowMediaKey(android.view.KeyEvent(0, 87)))
        verify(phone, never()).sendMediaButton(anyInt())
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

    @Test fun newCarPlayControllerRetriesFailedStartupOnceWithoutRetryingMetadataOrSameController() {
        backend.autoReady = false
        initialize(); drain()
        backend.ready(false); drain()
        val phone = attach(); drain()
        assertEquals(2, factories)
        backend.ready(false); drain()
        CarPlayMediaKeys.attach(app, phone) { true }
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.onMediaAudioChanged(true, phone); drain()
        assertEquals(2, factories)
        CarPlayMediaKeys.detach(phone); drain()
        CarPlayMediaKeys.attach(app, phone) { true }; drain()
        assertEquals(2, factories)
        attach(); drain()
        assertEquals(3, factories)
        assertEquals(0, backend.requests)
    }

    @Test fun bluetoothControllerDoesNotRetryTheFailedOemMediaInitialization() {
        backend.autoReady = false
        initialize(); drain()
        backend.ready(false); drain()
        val phone = mock(CarPlayController::class.java)
        controller = phone
        `when`(phone.usesCarBluetoothAudio).thenReturn(true)
        `when`(phone.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        CarPlayMediaKeys.attach(app, phone) { true }; drain()
        assertEquals(1, factories)
        assertEquals(0, backend.requests)
    }

    @Test fun disconnectRetainsTheProcessTokenAndTheNextSessionMustClaimPlaybackAgain() {
        initialize(); drain()
        val first = attach()
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.onMediaAudioChanged(true, first); drain()
        val before = backend.disconnections
        CarPlayMediaKeys.detach(first); drain()
        assertEquals(before + 1, backend.disconnections)
        assertEquals(0, backend.unregistrations)
        assertFalse(backend.command(CarPlayMediaButton.PAUSE))
        backend.ready(true); drain()
        assertEquals(1, backend.registrations)
        val next = attach(); drain()
        assertEquals(1, backend.registrations)
        assertFalse(backend.command(CarPlayMediaButton.NEXT))
        verify(next, never()).sendMediaButton(anyInt())
        CarPlayMediaKeys.onMediaAudioChanged(true, next); drain()
        assertTrue(backend.command(CarPlayMediaButton.NEXT))
        verify(next).sendMediaButton(CarPlayMediaButton.NEXT)
        assertEquals(2, backend.requests)
    }

    @Test fun audioStopPausesTheOemRecordEvenBeforeThePhoneUpdatesItsPlayingFlag() {
        initialize(); drain()
        val phone = attach()
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.onMediaAudioChanged(true, phone); drain()
        assertTrue(backend.publications.last().playing)
        val requests = backend.requests
        CarPlayMediaKeys.onMediaAudioChanged(false, phone); drain()
        assertFalse(backend.publications.last().playing)
        assertEquals(requests, backend.requests)
        assertEquals(0, backend.unregistrations)
    }

    @Test fun binderKeyReturnsBeforeMainRunsAndCannotCrossAControllerReplacement() {
        initialize(); drain()
        val first = attach()
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.onMediaAudioChanged(true, first); drain()
        val accepted = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val thread = Thread { accepted.set(backend.command(CarPlayMediaButton.NEXT)) }.apply { start() }
        thread.join(1000)
        assertFalse("Binder callback must not wait for the paused main looper", thread.isAlive)
        assertEquals(true, accepted.get())
        verify(first, never()).sendMediaButton(anyInt())
        CarPlayMediaKeys.detach(first)
        val next = attach()
        CarPlayMediaKeys.onMediaAudioChanged(true, next); drain()
        verify(first, never()).sendMediaButton(anyInt())
        verify(next, never()).sendMediaButton(anyInt())
    }

    @Test fun binderKeysAreAcknowledgedImmediatelyAndDispatchedInOrderOnMain() {
        initialize(); drain()
        val phone = attach()
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.onMediaAudioChanged(true, phone); drain()
        val thread = Thread {
            assertTrue(backend.command(CarPlayMediaButton.NEXT))
            assertTrue(backend.command(CarPlayMediaButton.PREVIOUS))
        }.apply { start() }
        thread.join(1000)
        assertFalse(thread.isAlive)
        verify(phone, never()).sendMediaButton(anyInt())
        drain()
        val ordered = inOrder(phone)
        ordered.verify(phone).sendMediaButton(CarPlayMediaButton.NEXT)
        ordered.verify(phone).sendMediaButton(CarPlayMediaButton.PREVIOUS)
    }

    @Test fun defaultSdkExecutionUsesMainAndRegistersWithinTheReadyCallbackWithoutHoldingTheKeyMonitor() {
        val events = mutableListOf<String>()
        var insideReady = false
        val direct = object : PrefaceMediaCenter.Backend {
            override fun init(snapshot: () -> PrefaceMediaCenter.Snapshot, command: (Int) -> Boolean,
                ready: (Boolean) -> Unit) {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                assertFalse(Thread.holdsLock(CarPlayMediaKeys))
                events.add("init")
                insideReady = true
                ready(true)
                insideReady = false
                events.add("init-return")
            }
            override fun register(): Any {
                assertTrue(insideReady)
                events.add("register")
                return Any()
            }
            override fun requestPlay(token: Any) = false
            override fun publish(token: Any, info: CarPlayNowPlaying) = true
            override fun unregister(token: Any) = true
        }
        val caller = Thread { CarPlayMediaKeys.initialize(app, backendFactory = { _, _ -> direct }) }
        caller.start(); caller.join(1000)
        assertFalse(caller.isAlive)
        assertTrue(events.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("init", "register", "init-return"), events)
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
        var disconnections = 0
        val publications = mutableListOf<CarPlayNowPlaying>()
        private val token = Any()
        override fun init(snapshot: () -> PrefaceMediaCenter.Snapshot, command: (Int) -> Boolean,
            ready: (Boolean) -> Unit) {
            this.command = command; this.ready = ready
            if (autoReady) ready(true)
        }
        override fun register(): Any { registrations++; return token }
        override fun requestPlay(token: Any): Boolean { requests++; return true }
        override fun publish(token: Any, info: CarPlayNowPlaying): Boolean { publications.add(info); return true }
        override fun disconnect() { disconnections++ }
        override fun unregister(token: Any): Boolean { assertSame(this.token, token); unregistrations++; return true }
    }
}
