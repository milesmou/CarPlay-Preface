package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayTransport
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class UsbConnectionPriorityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val usb get() = app.getSystemService(UsbManager::class.java)
    private lateinit var activity: DiPlayActivity
    private val phone = "12:34:56:78:90:AB"

    @Before fun setup() {
        UsbAutoConnect.resetForTest()
        CarPlayBackgroundSession.clear()
        app.getSharedPreferences("diplay", 0).edit().clear().putBoolean("notification_asked", true).commit()
        DiPlayPreferences.savePhone(app, phone, "iPhone")
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).create().get()
        ReflectionHelpers.setField(activity, "setupError", null)
    }
    @After fun cleanup() {
        ReflectionHelpers.getField<android.os.Handler>(activity, "handler").removeCallbacksAndMessages(null)
        CarPlayBackgroundSession.clear()
        UsbAutoConnect.resetForTest()
    }

    private fun attach(vendor: Int = 0x05ac): UsbDevice = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", "usb-test-phone")
        ReflectionHelpers.setField(this, "mVendorId", vendor)
        ReflectionHelpers.setField(this, "mProductId", 0x12a8)
        shadowOf(usb).addOrUpdateUsbDevice(this, false)
    }
    private fun requestWireless() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "connect",
        ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))

    @Test fun usbIsSelectedBeforeAWirelessRequestCanOpenTheHost() {
        attach()
        requestWireless()
        val launch = shadowOf(activity).nextStartedActivity
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, launch?.action)
        assertEquals(CarPlayHostActivity::class.java.name, launch?.component?.className)
    }

    @Test fun repeatObservationOfTheSameCableDoesNotLaunchAgain() {
        attach()
        repeat(3) { assertTrue(UsbAutoConnect.request(activity)) }
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun failedUsbKeepsAutomaticPriorityUntilTheDriverSelectsWireless() {
        attach()
        assertTrue(UsbAutoConnect.request(activity))
        val launch = shadowOf(activity).nextStartedActivity!!
        UsbAutoConnect.wiredFailed(app, UsbAutoConnect.generation)
        assertTrue(UsbAutoConnect.canChooseWireless(app))
        repeat(3) { assertTrue(UsbAutoConnect.request(activity)) }
        assertNotNull(UsbAutoConnect.priorityDevice(app))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(UsbAutoConnect.chooseWireless(app))
        assertFalse(UsbAutoConnect.isCurrentRequest(launch))
        assertNull(UsbAutoConnect.priorityDevice(app))
        assertFalse(UsbAutoConnect.request(activity, force = true))
    }

    @Test fun anActiveWiredSessionCannotBeOverriddenByAStaleFailure() {
        attach()
        val attempt = UsbAutoConnect.beginWiredAttempt(app)
        UsbAutoConnect.wiredFailed(app, attempt)
        val controller = mock(CarPlayController::class.java)
        `when`(controller.connectionTransport()).thenReturn(CarPlayTransport.WIRED)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) {}
        CarPlayBackgroundSession.active = true
        assertFalse(UsbAutoConnect.canChooseWireless(app))
        assertFalse(UsbAutoConnect.chooseWireless(app))
        assertNotNull(UsbAutoConnect.priorityDevice(app))
    }

    @Test fun usbRetryClearsTheFailureAndRejectsAnOlderFailureCallback() {
        attach()
        val oldAttempt = UsbAutoConnect.beginWiredAttempt(app)
        UsbAutoConnect.wiredFailed(app, oldAttempt)
        assertTrue(UsbAutoConnect.canChooseWireless(app))
        val retry = UsbAutoConnect.beginWiredAttempt(app)
        UsbAutoConnect.wiredFailed(app, oldAttempt)
        assertFalse(UsbAutoConnect.canChooseWireless(app))
        UsbAutoConnect.wiredFailed(app, retry)
        UsbAutoConnect.wiredConnected(retry)
        assertFalse(UsbAutoConnect.canChooseWireless(app))
    }

    @Test fun replugAfterWirelessSelectionRestoresUsbAndCancelsDelayedWirelessLaunch() {
        UsbAutoConnect.register(app)
        val device = attach()
        val attempt = UsbAutoConnect.beginWiredAttempt(app)
        UsbAutoConnect.wiredFailed(app, attempt)
        assertTrue(UsbAutoConnect.chooseWireless(app))
        val controller = mock(CarPlayController::class.java)
        `when`(controller.connectionTransport()).thenReturn(CarPlayTransport.WIRED)
        var done: (() -> Unit)? = null
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) { done = it }
        requestWireless()
        assertNotNull(done)
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, device))
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, shadowOf(app).nextStartedActivity?.action)
        assertNotNull(UsbAutoConnect.priorityDevice(app))
        CarPlayBackgroundSession.clear()
        done!!()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun attachmentAndReplugBroadcastsStartUsbWithoutAButton() {
        UsbAutoConnect.register(app)
        val device = attach()
        fun broadcast(action: String) {
            app.sendBroadcast(Intent(action).putExtra(UsbManager.EXTRA_DEVICE, device))
            shadowOf(Looper.getMainLooper()).idle()
        }
        broadcast(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, shadowOf(app).nextStartedActivity?.action)
        broadcast(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        assertNull(shadowOf(app).nextStartedActivity)
        broadcast(UsbManager.ACTION_USB_DEVICE_DETACHED)
        broadcast(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, shadowOf(app).nextStartedActivity?.action)
    }

    @Test fun returnHomeBlocksPollingDuplicateAttachAndOldLaunchUntilCableReplug() {
        UsbAutoConnect.register(app)
        val device = attach()
        UsbAutoConnect.request(activity)
        val oldLaunch = shadowOf(activity).nextStartedActivity!!
        ConnectionAutoStart.cancel()
        UsbAutoConnect.cancelForHome(app)
        repeat(3) { assertFalse(UsbAutoConnect.request(activity)) }
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ConnectionAutoStart.paused)
        assertNull(shadowOf(app).nextStartedActivity)
        assertFalse(UsbAutoConnect.isCurrentRequest(oldLaunch))
        assertTrue(UsbAutoConnect.canChooseWireless(app))
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, device))
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device))
        shadowOf(Looper.getMainLooper()).idle()
        val launch = shadowOf(app).nextStartedActivity!!
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, launch.action)
        assertTrue(ConnectionAutoStart.accepts(launch))
        assertFalse(ConnectionAutoStart.accepts(oldLaunch))
    }

    @Test fun delayedWirelessLaunchCannotEscapeCancellationAfterAnotherManualRequest() {
        val controller = mock(CarPlayController::class.java)
        var done: (() -> Unit)? = null
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) { done = it }
        requestWireless()
        assertNotNull(done)
        ConnectionAutoStart.cancel()
        ConnectionAutoStart.resume()
        CarPlayBackgroundSession.clear()
        done!!()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        requestWireless()
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
    }

    @Test fun otherUsbAccessoriesDoNotTakePriority() {
        attach(0x1a86)
        assertNull(UsbAutoConnect.attached(app))
        requestWireless()
        assertNull(shadowOf(activity).nextStartedActivity?.action)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
    }

    @Test fun delayedWirelessTeardownCannotUndoANewerUsbRequest() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.connectionTransport()).thenReturn(CarPlayTransport.WIRELESS)
        var done: (() -> Unit)? = null
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) { done = it }
        requestWireless()
        assertNotNull(done)
        attach()
        UsbAutoConnect.request(activity)
        val launch = shadowOf(activity).nextStartedActivity
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, launch?.action)
        CarPlayBackgroundSession.clear()
        done!!()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun bluetoothWakeCannotResumeWirelessWhenUsbIsPresent() {
        attach()
        DiPlayPreferences.saveAutoConnect(app, true)
        PhoneBluetoothReceiver().onReceive(activity, Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothAdapter.getDefaultAdapter().getRemoteDevice(phone)))
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, shadowOf(activity).nextStartedActivity?.action)
        assertTrue(PhoneWakeDiagnostics.report(app).contains("usb-priority"))
    }
}
