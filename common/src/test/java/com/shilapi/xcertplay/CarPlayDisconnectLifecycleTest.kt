package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayDisconnectLifecycleTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var controller: CarPlayController
    private lateinit var worker: PausedExecutorService

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        UsbAutoConnect.resetForTest()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        controller = mock(CarPlayController::class.java)
        `when`(controller.awaitClosed(anyLong())).thenReturn(true)
        val sink = mock(AndroidMediaSink::class.java)
        ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
        worker = PausedExecutorService()
        ReflectionHelpers.setField(host, "teardownExecutor", worker)
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "sink", sink)
        CarPlayBackgroundSession.store(controller, sink, 1920, 720, host,
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) { done -> shutdown(done) }
    }

    @After fun cleanup() {
        com.shilapi.xcertplay.network.TemporaryHotspotReservationStore.release()
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        UsbAutoConnect.resetForTest()
        worker.shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun disconnectAndImmediateReconnectWaitUntilOldResourcesActuallyClose() {
        var completed = 0
        var waits = 0
        `when`(controller.awaitClosed(anyLong())).thenAnswer {
            waits++
            assertTrue("No new connection may claim the retiring owner's slot", CarPlayBackgroundSession.hasSession())
            assertTrue(CarPlayBackgroundSession.isOwner(host))
            assertNull("A retiring session cannot be adopted", CarPlayBackgroundSession.snapshot())
            assertEquals(0, completed)
            waits >= 3
        }
        CarPlayBackgroundSession.stop { completed++ }
        // connect() issues another stop request when the driver presses Wireless immediately.
        CarPlayBackgroundSession.stop { completed++ }
        assertEquals(0, completed)
        assertTrue(CarPlayBackgroundSession.hasSession())
        worker.runAll()
        assertEquals(3, waits)
        assertEquals(0, completed)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, completed)
        assertFalse(CarPlayBackgroundSession.hasSession())
        verify(controller, times(1)).close()
        verify(controller, times(1)).prepareTemporaryHotspotHandoff()
    }

    @Test fun repeatedDirectShutdownQueuesEveryCompletionUntilTheBarrierFinishes() {
        var completed = 0
        shutdown { completed++ }
        shutdown { completed++ }
        CarPlayBackgroundSession.stop { completed++ }
        assertEquals(0, completed)
        worker.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, completed)
        assertFalse(CarPlayBackgroundSession.hasSession())
        shutdown { completed++ }
        assertEquals(4, completed)
        verify(controller, times(1)).close()
    }

    @Test fun disconnectDuringAQueuedRestartStillWaitsForThatRetiringController() {
        val sizeType = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeType.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 720)
        ReflectionHelpers.setField(host, "activeDisplaySize", size)
        var completed = false
        var waits = 0
        `when`(controller.awaitClosed(anyLong())).thenAnswer {
            assertFalse(completed)
            assertTrue(CarPlayBackgroundSession.hasSession())
            ++waits >= 3
        }
        host.javaClass.getDeclaredMethod("restartCarPlay", String::class.java).apply { isAccessible = true }
            .invoke(host, "Test queued restart")
        CarPlayBackgroundSession.stop { completed = true }
        worker.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, waits)
        assertTrue(completed)
        assertFalse(CarPlayBackgroundSession.hasSession())
        verify(controller, times(1)).close()
    }

    @Test fun fullExitReleasesAnIdleHotspotAndDoesNotRequestAnotherHandoff() {
        val manager = mock(com.shilapi.xcertplay.network.LocalOnlyHotspotManager::class.java)
        val info = com.shilapi.xcertplay.network.WirelessHotspotInfo("test", "test-password",
            com.shilapi.xcertplay.transport.Iap2WirelessSecurity.WPA_WPA2, 0, null, null, "ap0",
            java.net.InetAddress.getByName("192.0.2.1"), "2.4 GHz",
            com.shilapi.xcertplay.network.WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT)
        com.shilapi.xcertplay.network.TemporaryHotspotReservationStore.retain(manager, info)
        shutdown(true) {}
        worker.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        verify(manager, times(1)).close()
        verify(controller, never()).prepareTemporaryHotspotHandoff()
        assertNull(com.shilapi.xcertplay.network.TemporaryHotspotReservationStore.take())
    }

    @Test fun returningHomeCancelsWirelessRetriesAndLateStatusCallbacks() {
        ReflectionHelpers.setField(host, "wirelessEnabled", true)
        `when`(controller.canRetryWirelessStartup()).thenReturn(true)
        val reporter = ReflectionHelpers.callInstanceMethod<(com.shilapi.xcertplay.orchestration.CarPlayStatus) -> Unit>(host,
            "createStatusReporter", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val failure = com.shilapi.xcertplay.orchestration.CarPlayStatus.Failed("connection lost")
        // A failure status may arrive before the old session's ended callback.
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(com.shilapi.xcertplay.airplay.AirPlaySession::class.java))
        CarPlayBackgroundSession.active = true
        reporter(failure)
        assertTrue(ReflectionHelpers.getField(host, "reconnectScheduled"))
        returnHome()
        reporter(failure)
        assertHomeCancellation()
        assertEquals("session-stopping", host.onPhoneBluetoothConnected("phone"))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(40))
        verify(controller, never()).retryWirelessStartup()
        worker.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller).close()
        verify(controller).prepareTemporaryHotspotHandoff()
        assertFalse(CarPlayBackgroundSession.hasSession())
    }

    @Test fun backFromUsbConnectionInvalidatesPendingVpnConsentAndRetry() {
        ReflectionHelpers.setField(host, "wirelessEnabled", false)
        ReflectionHelpers.setField(host, "awaitingVpnConsent", true)
        val generation = UsbAutoConnect.generation
        host.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        ReflectionHelpers.callInstanceMethod<Unit>(host, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, android.app.Activity.RESULT_OK))
        assertHomeCancellation()
        assertTrue(UsbAutoConnect.generation > generation)
        assertFalse(ReflectionHelpers.getField(host, "awaitingVpnConsent"))
        assertFalse(ReflectionHelpers.getField(host, "vpnReady"))
        ReflectionHelpers.callInstanceMethod<Unit>(host, "requestStartupPrerequisites")
        ReflectionHelpers.callInstanceMethod<Unit>(host, "retryConnectionFromButton")
        assertNull(ReflectionHelpers.getField<Any?>(host, "controller"))
        worker.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller).close()
    }

    @Test fun returnHomeDuringStackReplacementDoesNotStartTheReplacement() {
        val sizeType = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        ReflectionHelpers.setField(host, "activeDisplaySize",
            sizeType.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(1920, 720))
        host.javaClass.getDeclaredMethod("restartCarPlay", String::class.java).apply { isAccessible = true }
            .invoke(host, "Test queued restart")
        returnHome()
        worker.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertHomeCancellation()
        assertNull(ReflectionHelpers.getField<Any?>(host, "controller"))
        assertFalse(CarPlayBackgroundSession.hasSession())
        verify(controller, times(1)).close()
    }

    @Test fun aQueuedHostLaunchCannotReopenAfterCancellationAndManualResume() {
        val launch = ConnectionAutoStart.stamp(android.content.Intent())
        returnHome()
        ConnectionAutoStart.resume()
        val staleHost = Robolectric.buildActivity(CarPlayHostActivity::class.java, launch).create()
        assertTrue(staleHost.get().isFinishing)
        assertNull(ReflectionHelpers.getField<Any?>(staleHost.get(), "controller"))
        staleHost.destroy()
    }

    @Test fun returningHomeAfterATransportErrorCancelsRecoveryOfAPreviouslyActiveSession() {
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(com.shilapi.xcertplay.airplay.AirPlaySession::class.java))
        CarPlayBackgroundSession.active = true
        val listener = ReflectionHelpers.callInstanceMethod<com.shilapi.xcertplay.airplay.AirPlaySessionListener>(host,
            "createSessionListener", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0))
        listener.onTransportError("connection lost")
        assertFalse(CarPlayBackgroundSession.active)
        assertTrue(ReflectionHelpers.getField(host, "reconnectScheduled"))
        returnHome()
        assertHomeCancellation()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(40))
        worker.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller).close()
        verify(controller, never()).retryWirelessStartup()
    }

    @Test fun returningHomeFromAnActiveProjectionKeepsTheSession() {
        CarPlayBackgroundSession.active = true
        returnHome()
        assertFalse(ConnectionAutoStart.paused)
        assertFalse(host.isFinishing)
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).close()
    }

    private fun returnHome() = host.javaClass.getDeclaredMethod("showDiPlayHome", String::class.java)
        .apply { isAccessible = true }.invoke(host, "home")

    private fun assertHomeCancellation() {
        assertTrue(ConnectionAutoStart.paused)
        assertTrue(host.isFinishing)
        assertTrue(ReflectionHelpers.getField(host, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
    }

    private fun shutdown(done: () -> Unit) = shutdown(false, done)

    private fun shutdown(terminate: Boolean, done: () -> Unit) {
        host.javaClass.getDeclaredMethod("shutdown", Boolean::class.javaPrimitiveType,
            String::class.java, Function0::class.java).apply { isAccessible = true }
            .invoke(host, terminate, "DiPlay disconnect", done)
    }
}
