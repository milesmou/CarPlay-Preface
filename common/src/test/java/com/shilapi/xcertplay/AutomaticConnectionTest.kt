package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AutomaticConnectionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var screen: ActivityController<DiPlayActivity>? = null
    private val phone = ConnectedPhone("12:34:56:78:90:AB", "Current iPhone")

    @Before fun setup() {
        UsbAutoConnect.resetForTest()
        CarPlayBackgroundSession.clear()
        app.getSharedPreferences("diplay", 0).edit().clear().putBoolean("notification_asked", true).commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
    }
    @After fun cleanup() {
        screen?.let {
            ReflectionHelpers.getField<Handler>(it.get(), "handler").removeCallbacksAndMessages(null)
            it.pause().stop().destroy()
        }
        CarPlayBackgroundSession.clear()
        UsbAutoConnect.resetForTest()
    }
    private fun home(page: String? = null): DiPlayActivity {
        val intent = Intent(app, DiPlayActivity::class.java)
        if (page != null) intent.putExtra("page", page)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent).create()
        screen = controller
        ReflectionHelpers.setField(controller.get(), "setupError", null)
        controller.start().resume()
        return controller.get()
    }
    private fun observe(activity: DiPlayActivity, phones: List<ConnectedPhone> = listOf(phone)) {
        ReflectionHelpers.setField(activity, "connectedPhoneState", ConnectedPhoneState(true, true, phones))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "autoConnectAvailableDevice")
    }
    private fun attach(): UsbDevice = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", "usb-auto-phone")
        ReflectionHelpers.setField(this, "mVendorId", 0x05ac)
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(this, false)
    }

    @Test fun bluetoothConnectionCanReachTheAppThroughTheManifest() {
        val receiver = app.packageManager.getReceiverInfo(android.content.ComponentName(app, PhoneBluetoothReceiver::class.java), 0)
        assertTrue(receiver.enabled)
        assertTrue(receiver.exported)
    }

    @Test fun defaultsEnableAutomaticConnectionAndKeepBootStartIndependent() {
        assertTrue(DiPlayPreferences.autoConnect(app))
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(app))
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, false)
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertFalse(DiPlayPreferences.autoConnect(app))
    }

    @Test fun automaticCardHasExactlyTwoPersistedOptionsAndNoPhoneOrModePicker() {
        val activity = home("settings")
        val card = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "automaticConnectionControls", from(LinearLayout::class.java, card))
        val switches = descendants(card).filterIsInstance<Switch>().toList()
        assertEquals(listOf(activity.getString(R.string.settings_auto_connect), activity.getString(R.string.settings_start_on_boot)),
            switches.map { it.contentDescription.toString() })
        assertEquals(listOf(true, false), switches.map { it.isChecked })
        assertTrue(descendants(card).none { it is Button && it !is Switch })
        switches[0].isChecked = false
        switches[1].isChecked = true
        assertFalse(DiPlayPreferences.autoConnect(app))
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun anAlreadyConnectedPhoneIsChosenInsteadOfTheLastTransportOrSavedPhone() {
        DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:FF", "Old phone")
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val activity = home()
        observe(activity)
        val launch = shadowOf(activity).nextStartedActivity!!
        assertEquals(CarPlayHostActivity::class.java.name, launch.component?.className)
        assertNull(launch.action)
        assertFalse(UsbAutoConnect.isManualRequest(launch))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
        assertEquals(phone.address, DiPlayPreferences.phoneAddress(app))
        observe(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aSavedPairWithoutLiveEvidenceDoesNotStartWireless() {
        DiPlayPreferences.savePhone(app, phone.address, "iPhone")
        val activity = home()
        observe(activity, emptyList())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun usbAttachmentWinsOverAnAlreadyConnectedBluetoothPhone() {
        attach()
        val activity = home()
        observe(activity)
        val launch = shadowOf(activity).nextStartedActivity!!
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, launch.action)
        assertFalse(UsbAutoConnect.isManualRequest(launch))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun disablingAutomaticConnectionBlocksBothTransportsButAllowsManualUsb() {
        DiPlayPreferences.saveAutoConnect(app, false)
        attach()
        val activity = home()
        observe(activity)
        assertFalse(UsbAutoConnect.request(activity, force = true))
        assertNull(shadowOf(activity).nextStartedActivity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        val device = descendants(activity.findViewById(android.R.id.content)).single { it.tag == "home-device:usb" }
        assertTrue(device.performClick())
        val manual = shadowOf(activity).nextStartedActivity!!
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, manual.action)
        assertTrue(UsbAutoConnect.isManualRequest(manual))
        assertFalse(UsbAutoConnect.automaticRequestDisabled(activity, manual))
    }

    @Test fun aRawSystemUsbIntentCannotBypassTheDisabledOption() {
        DiPlayPreferences.saveAutoConnect(app, false)
        val launch = Intent(app, CarPlayHostActivity::class.java).setAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .putExtra(UsbManager.EXTRA_DEVICE, attach())
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java, launch).create()
        try {
            assertTrue(host.get().isFinishing)
            assertNull(ReflectionHelpers.getField<Any?>(host.get(), "controller"))
            assertFalse(ReflectionHelpers.getField(host.get(), "awaitingVpnConsent"))
            assertEquals("home", shadowOf(host.get()).nextStartedActivity?.getStringExtra("page"))
        } finally { host.destroy() }
    }

    @Test fun disablingAutomaticConnectionRejectsQueuedWirelessButAllowsProjectionShortcuts() {
        DiPlayPreferences.saveAutoConnect(app, false)
        val shortcut = Intent(app, CarPlayHostActivity::class.java).putExtra("picture_controls", true)
        assertFalse(UsbAutoConnect.automaticRequestDisabled(app, shortcut))
        val automatic = UsbAutoConnect.markAutomaticRequest(Intent(app, CarPlayHostActivity::class.java))
        assertTrue(UsbAutoConnect.automaticRequestDisabled(app, automatic))
    }

    @Test fun returningHomeCancellationStillBlocksAutomaticConnection() {
        val activity = home()
        ConnectionAutoStart.cancel()
        observe(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun settingsNeverAutoConnectADevice() {
        val activity = home("settings")
        observe(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
