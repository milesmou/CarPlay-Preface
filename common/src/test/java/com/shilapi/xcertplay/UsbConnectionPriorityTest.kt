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
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        PhoneBluetoothReceiver().onReceive(activity, Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothAdapter.getDefaultAdapter().getRemoteDevice(phone)))
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, shadowOf(activity).nextStartedActivity?.action)
        assertTrue(PhoneWakeDiagnostics.report(app).contains("usb-priority"))
    }
}
