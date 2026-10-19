package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.airplay.AirPlaySession
import org.mockito.Mockito.*
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupRecoveryTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var retry: Button
    private val failure = CarPlayStatus.Failed("timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        retry = Button(activity).apply { visibility = View.GONE }
        ReflectionHelpers.setField(activity, "startupRetryButton", retry)
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    private fun report(generation: Int = 0, status: CarPlayStatus = failure) {
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation))(status)
    }
    private fun budget() = ReflectionHelpers.getField<WirelessStartupRetryBudget>(activity, "startupRetryBudget")

    @Test fun frameTestingStopsQueuedRecoveryAndDoesNotOfferManualRetryUntilItEnds() {
        report()
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        report(status = CarPlayStatus.TestingUsbNcm)
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.GONE, retry.visibility)
        report(status = CarPlayStatus.Failed("old listener socket closed"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        report(status = CarPlayStatus.Failed("NCM test complete", retryable = false, usbNcmTested = true))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun unavailableUsbStopsAutomaticRetriesAndExposesManualRetry() {
        val stage = android.widget.TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        report(status = CarPlayStatus.Failed("USB AirPlay did not connect", retryable = false, usbDirectUnavailable = true))
        assertEquals(activity.getString(com.shilapi.xcertplay.host.R.string.wireless_startup_retries_exhausted), stage.text.toString())
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
        assertEquals(View.VISIBLE, retry.visibility)
        report(status = CarPlayStatus.Failed("cleanup socket closed"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun unavailableUsbInvalidatesAnAlreadyQueuedRetryEvenDuringSettings() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.canRetryWirelessStartup()).thenReturn(true)
        ReflectionHelpers.setField(activity, "controller", controller)
        report()
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        ReflectionHelpers.setField(activity, "menuOpen", true)
        report(status = CarPlayStatus.Failed("USB unavailable", retryable = false, usbDirectUnavailable = true))
        ReflectionHelpers.setField(activity, "menuOpen", false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        verify(controller, never()).retryWirelessStartup()
        verify(controller, never()).close()
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
    }

    @Test fun duplicateFailureAndCleanupErrorScheduleOnlyOneRetry() {
        report()
        report()
        report(status = CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(1, budget().retries)
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
    }

    @Test fun exhaustedBudgetStopsAutomaticRecoveryAndExposesRetry() {
        repeat(6) { generation ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation)
        }
        assertEquals(5, budget().retries)
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
        report(5, CarPlayStatus.Failed("Bluetooth socket closed"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun unrecoverableConfigurationDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Permission missing", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // No further attempt can make a head unit's Bluetooth reach the phone.
    @Test fun bluetoothThatReachesNoPhoneDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Android Bluetooth on this head unit reports connections that are not real",
            startupFailure = WirelessStartupFailure.BLUETOOTH_UNUSABLE))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun staleGenerationUserDisconnectMenuAndDifferentOwnerCannotSchedule() {
        report(1)
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", false)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(false)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        report()
        assertEquals(0, budget().retries)
    }

    @Test fun timeoutRetriesOnTheSameControllerWithoutClosingItAndAllowsNextFailure() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.canRetryWirelessStartup()).thenReturn(true)
        `when`(controller.retryWirelessStartup()).thenReturn(true)
        ReflectionHelpers.setField(activity, "controller", controller)
        report()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        verify(controller).retryWirelessStartup()
        verify(controller, never()).close()
        assertSame(controller, ReflectionHelpers.getField(activity, "controller"))
        assertEquals(-1, ReflectionHelpers.getField<Int>(activity, "startupFailureGeneration"))
        report()
        assertEquals(2, budget().retries)
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun genericControlFailuresWithRetainedHotspotAlsoStopAtTheStartupBudget() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.canRetryWirelessStartup()).thenReturn(true)
        ReflectionHelpers.setField(activity, "controller", controller)
        repeat(6) {
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(status = CarPlayStatus.Failed("control channel closed"))
        }
        assertEquals(5, budget().retries)
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        verify(controller, never()).close()
    }

    @Test fun aSessionThatConnectsBeforeQueuedStartupRetryIsNotInterrupted() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.canRetryWirelessStartup()).thenReturn(true)
        ReflectionHelpers.setField(activity, "controller", controller)
        report()
        ReflectionHelpers.setField(activity, "activeAirPlaySession", mock(AirPlaySession::class.java))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        verify(controller, never()).retryWirelessStartup()
        verify(controller, never()).close()
    }

    private fun stoppedWirelessController(): CarPlayController {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.matchesWirelessPhone("phone")).thenReturn(true)
        `when`(controller.canRetryWirelessStartup()).thenReturn(true)
        `when`(controller.retryWirelessStartup()).thenReturn(true)
        ReflectionHelpers.setField(activity, "controller", controller)
        repeat(6) {
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(status = CarPlayStatus.Failed("control channel closed"))
        }
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        return controller
    }

    @Test fun bluetoothAfterExhaustionResumesSameHotspotOnceAndInvalidatesOldTimers() {
        val controller = stoppedWirelessController()
        assertEquals("wireless-retry-resumed", activity.onPhoneBluetoothConnected("phone"))
        assertEquals("connection-in-progress", activity.onPhoneBluetoothConnected("phone"))
        assertEquals(0, budget().retries)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        verify(controller, times(1)).retryWirelessStartup()
        verify(controller, never()).close()
    }

    @Test fun bluetoothCannotReplaceOtherPhoneUsbLiveSessionOrSettings() {
        val controller = stoppedWirelessController()
        assertEquals("session-other-transport-or-phone", activity.onPhoneBluetoothConnected("other"))
        ReflectionHelpers.setField(activity, "activeAirPlaySession", mock(AirPlaySession::class.java))
        assertEquals("session-active", activity.onPhoneBluetoothConnected("phone"))
        ReflectionHelpers.setField(activity, "activeAirPlaySession", null)
        ReflectionHelpers.setField(activity, "menuOpen", true)
        assertEquals("session-busy", activity.onPhoneBluetoothConnected("phone"))
        verify(controller, never()).retryWirelessStartup()
    }

    @Test fun bluetoothCannotRestartPermissionOrConfigurationFailure() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.matchesWirelessPhone("phone")).thenReturn(true)
        ReflectionHelpers.setField(activity, "controller", controller)
        report(status = CarPlayStatus.Failed("Configuration missing",
            startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals("requires-user-action", activity.onPhoneBluetoothConnected("phone"))
        verify(controller, never()).retryWirelessStartup()
        verify(controller, never()).close()
    }

    @Test fun genericRetryDoesNotCloseASessionThatConnectedDuringItsDelay() {
        val controller = mock(CarPlayController::class.java)
        ReflectionHelpers.setField(activity, "controller", controller)
        report(status = CarPlayStatus.Failed("Bluetooth not ready"))
        ReflectionHelpers.setField(activity, "activeAirPlaySession", mock(AirPlaySession::class.java))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        verify(controller, never()).close()
        verify(controller, never()).retryWirelessStartup()
    }

    @Test fun receiverRoutesToRetainedOwnerWithoutOpeningAnotherActivity() {
        val controller = stoppedWirelessController()
        val address = "12:34:56:78:9A:BC"
        `when`(controller.matchesWirelessPhone(address)).thenReturn(true)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        DiPlayPreferences.savePhone(app, address, "iPhone")
        DiPlayPreferences.saveAutoConnect(app, true)
        val stop: ((() -> Unit) -> Unit) = { fail("Must not stop existing owner") }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        val context = object : android.content.ContextWrapper(app) {
            override fun startActivity(intent: android.content.Intent) { fail("Must not launch another activity") }
        }
        val device = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        val event = android.content.Intent(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            .putExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, device)
        PhoneBluetoothReceiver().onReceive(context, event)
        PhoneBluetoothReceiver().onReceive(context, event)
        verify(controller, times(1)).retryWirelessStartup()
        verify(controller, never()).close()
    }

    @Test fun lateSuccessfulHandshakeClearsStoppedStateAndOldRetries() {
        val controller = stoppedWirelessController()
        val listener = ReflectionHelpers.callInstanceMethod<com.shilapi.xcertplay.airplay.AirPlaySessionListener>(
            activity, "createSessionListener", from(Int::class.javaPrimitiveType, 0))
        listener.onSessionActive(mock(AirPlaySession::class.java))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        assertFalse(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(CarPlayBackgroundSession.active)
        assertEquals(View.GONE, retry.visibility)
        // Successful authentication alone must not reset the stable-video retry budget.
        assertEquals(5, budget().retries)
        verify(controller, never()).close()
        verify(controller, never()).retryWirelessStartup()
    }
}
