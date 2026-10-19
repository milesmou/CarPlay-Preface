package com.shilapi.xcertplay

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import android.view.Surface
import android.widget.Button
import com.shilapi.xcertplay.host.R
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeTransportSwitchTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var screen: ActivityController<DiPlayActivity>? = null
    private var stops = 0
    private var release: (() -> Unit)? = null

    @Before fun setup() {
        UsbAutoConnect.resetForTest()
        CarPlayBackgroundSession.clear()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        DiPlayPreferences.savePhone(app, "12:34:56:78:90:AB", "iPhone")
    }

    @After fun cleanup() { screen?.destroy(); CarPlayBackgroundSession.clear(); UsbAutoConnect.resetForTest() }

    private fun attachFailedUsb() {
        val device = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", "usb-failed-phone")
            ReflectionHelpers.setField(this, "mVendorId", 0x05ac)
        }
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, false)
        UsbAutoConnect.wiredFailed(app, UsbAutoConnect.beginWiredAttempt(app))
    }

    @Test fun failedUsbStillPluggedInCanBeReplacedByAnExplicitWirelessSelection() {
        attachFailedUsb()
        retain(CarPlayTransport.WIRED)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val activity = home()
        assertTrue(wireless(activity).isEnabled)
        wireless(activity).performClick()
        assertEquals(1, stops)
        assertNull(shadowOf(activity).nextStartedActivity)
        release!!()
        shadowOf(Looper.getMainLooper()).idle()
        val launch = shadowOf(activity).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, launch?.component?.className)
        assertNull(launch?.action)
        assertFalse(launch!!.hasExtra(UsbManager.EXTRA_DEVICE))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
        assertNotNull(UsbAutoConnect.attached(app))
        assertFalse(UsbAutoConnect.request(activity))
    }

    @Test fun failedVpnBeforeAControllerExistsCanAlsoStartWirelessWithoutUnplugging() {
        attachFailedUsb()
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val activity = home()
        wireless(activity).performClick()
        assertEquals(0, stops)
        val launch = shadowOf(activity).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, launch?.component?.className)
        assertNull(launch?.action)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
    }

    @Test fun onlyTheSavedPhoneAmongLiveBluetoothDevicesIsShown() {
        val activity = home()
        setPhones(activity, listOf(ConnectedPhone("OTHER", "Other iPhone"),
            ConnectedPhone("12:34:56:78:90:AB", "My iPhone")))
        assertEquals(1, phoneList(activity).childCount)
        assertEquals("home-phone:12:34:56:78:90:AB", phoneList(activity).getChildAt(0).tag)
    }

    @Test fun noDeviceLeavesOnlyTheConnectionHint() {
        val activity = home()
        setPhones(activity, emptyList())
        assertEquals(0, phoneList(activity).childCount)
    }

    @Test fun usbPriorityShowsOneUsbDeviceInsteadOfBluetoothDevices() {
        attachFailedUsb()
        UsbAutoConnect.beginWiredAttempt(app)
        val activity = home()
        assertEquals(1, phoneList(activity).childCount)
        val device = phoneList(activity).getChildAt(0)
        assertEquals("home-device:usb", device.tag)
        assertFalse("USB starts automatically, without a connect button", device.isClickable)
    }

    @Test fun activeWirelessSessionStaysVisibleWhenBluetoothDrops() {
        retain(CarPlayTransport.WIRELESS, active = true)
        val activity = home()
        setPhones(activity, emptyList())
        assertEquals(1, phoneList(activity).childCount)
        assertEquals("home-session:wireless", phoneList(activity).getChildAt(0).tag)
        phoneList(activity).getChildAt(0).performClick()
        assertProjection(activity)
        assertEquals(0, stops)
    }

    @Test fun activeWiredSessionShowsOnlyUsbEvenAfterAFailedAttempt() {
        attachFailedUsb()
        retain(CarPlayTransport.WIRED, active = true)
        val activity = home()
        assertEquals(1, phoneList(activity).childCount)
        assertEquals("home-device:usb", phoneList(activity).getChildAt(0).tag)
    }

    @Test fun changingSessionTransportRefreshesTheSingleDevice() {
        retain(CarPlayTransport.WIRELESS, active = true)
        val activity = home()
        assertEquals("home-session:wireless", phoneList(activity).getChildAt(0).tag)
        attachFailedUsb()
        retain(CarPlayTransport.WIRED, active = true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
        assertEquals(1, phoneList(activity).childCount)
        assertEquals("home-device:usb", phoneList(activity).getChildAt(0).tag)
        CarPlayBackgroundSession.clear()
        val usbManager = shadowOf(app.getSystemService(UsbManager::class.java))
        UsbAutoConnect.attached(app)?.let { usbManager.removeUsbDevice(it) }
        setPhones(activity, emptyList())
        assertEquals(0, phoneList(activity).childCount)
    }

    private fun phoneList(activity: DiPlayActivity) =
        ReflectionHelpers.getField<android.widget.LinearLayout>(activity, "homePhoneList")

    private fun setPhones(activity: DiPlayActivity, phones: List<ConnectedPhone>) {
        ReflectionHelpers.setField(activity, "connectedPhoneState", ConnectedPhoneState(true, true, phones))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
    }

    private fun retain(mode: CarPlayTransport, active: Boolean = false, closed: Boolean = false): CarPlayController {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.connectionTransport()).thenReturn(mode)
        `when`(controller.isClosed()).thenReturn(closed)
        `when`(controller.matchesWirelessPhone("12:34:56:78:90:AB")).thenReturn(mode == CarPlayTransport.WIRELESS)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) { done ->
            stops++
            release = { CarPlayBackgroundSession.clear(controller); done() }
        }
        CarPlayBackgroundSession.active = active
        return controller
    }

    private fun home(): DiPlayActivity {
        screen = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).create()
        val activity = screen!!.get()
        ReflectionHelpers.setField(activity, "setupError", null)
        ReflectionHelpers.setField(activity, "connectedPhoneState", ConnectedPhoneState(true, true,
            listOf(ConnectedPhone("12:34:56:78:90:AB", "iPhone"))))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
        return activity
    }
    private fun wireless(activity: DiPlayActivity) =
        (ReflectionHelpers.getField<android.widget.LinearLayout>(activity, "homePhoneList").getChildAt(0) as Button)
    private fun assertProjection(activity: DiPlayActivity) {
        val launch = shadowOf(activity).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, launch?.component?.className)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
    }

    @Test fun unpluggedUsbStillWaitingCannotBeResumedByTheWirelessDevice() {
        retain(CarPlayTransport.WIRED)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val activity = home()
        assertEquals("iPhone · ${activity.getString(R.string.wireless_carplay)}", wireless(activity).text.toString())
        wireless(activity).performClick()
        assertEquals(1, stops)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(AirPlayPersistence.loadWirelessEnabled(app))
        release!!(); shadowOf(Looper.getMainLooper()).idle()
        assertProjection(activity)
    }

    @Test fun activeUsbSnapshotIsShownInsteadOfAWirelessChoice() {
        retain(CarPlayTransport.WIRED, active = true)
        val activity = home()
        val device = phoneList(activity).getChildAt(0)
        assertEquals("home-device:usb", device.tag)
        device.performClick()
        assertEquals(0, stops)
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
    }

    @Test fun savedWirelessPreferenceCannotMisidentifyAnActualUsbController() {
        retain(CarPlayTransport.WIRED)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val activity = home()
        wireless(activity).performClick()
        assertEquals(1, stops)
        assertNull(shadowOf(activity).nextStartedActivity)
        release!!()
        assertProjection(activity)
    }

    @Test fun matchingWirelessConnectionIsResumedWithoutRecreatingItsHotspot() {
        retain(CarPlayTransport.WIRELESS, active = true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val activity = home()
        assertEquals("iPhone · ${activity.getString(R.string.wireless_carplay)}", wireless(activity).text.toString())
        wireless(activity).performClick()
        assertEquals(0, stops)
        assertProjection(activity)
    }

    @Test fun closedWirelessControllerIsStoppedInsteadOfReused() {
        retain(CarPlayTransport.WIRELESS, closed = true)
        val activity = home()
        wireless(activity).performClick()
        assertEquals(1, stops)
        release!!()
        assertProjection(activity)
    }

    @Test fun aRetiringUsbOwnerWithoutASnapshotStillWaitsForRelease() {
        retain(CarPlayTransport.WIRED)
        CarPlayBackgroundSession.clear(keepOwner = true)
        val activity = home()
        wireless(activity).performClick()
        assertEquals(1, stops)
        assertNull(shadowOf(activity).nextStartedActivity)
        release!!()
        assertProjection(activity)
    }
}
