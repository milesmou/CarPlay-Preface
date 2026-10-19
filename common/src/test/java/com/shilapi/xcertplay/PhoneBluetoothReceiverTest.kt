package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29, 33])
class PhoneBluetoothReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val selected = "12:34:56:78:9A:BC"

    @Before fun reset() {
        DiPlayPreferences.saveAutoConnect(app, false)
        app.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_phone_wake_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        ConnectionAutoStart.resetForTest()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        // Saved in lowercase on purpose: the receiver must match the (always uppercase) device
        // address case-insensitively; Robolectric refuses to build a lowercase remote device.
        DiPlayPreferences.savePhone(app, selected.lowercase(), "iPhone")
    }

    @org.junit.After fun cleanupLaunchPolicy() { ConnectionAutoStart.resetForTest() }

    private fun event(address: String): Intent {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        return Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
    }

    @Test fun returningHomeSuppressesEvenANewBluetoothProfileConnection() {
        DiPlayPreferences.saveAutoConnect(app, true)
        ConnectionAutoStart.cancel()
        PhoneBluetoothReceiver().onReceive(app, event(selected))
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(PhoneWakeDiagnostics.report(app).contains("cancelled-on-home"))
    }

    @Test fun aNewInstallConnectsTheEventPhoneByDefault() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        assertTrue(DiPlayPreferences.autoConnect(app))
        PhoneBluetoothReceiver().onReceive(app, event(selected))
        assertEquals(selected, DiPlayPreferences.phoneAddress(app))
        assertEquals(DiPlayActivity::class.java.name, shadowOf(app).nextStartedActivity?.component?.className)
    }

    @Test fun anotherConnectedPhoneCannotReplaceAnExistingSession() {
        DiPlayPreferences.saveAutoConnect(app, true)
        val stop: ((() -> Unit) -> Unit) = { fail("Automatic connection must preserve the current session") }
        org.robolectric.util.ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        PhoneBluetoothReceiver().onReceive(app, event("AA:BB:CC:DD:EE:FF"))
        assertEquals(selected.lowercase(), DiPlayPreferences.phoneAddress(app))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun currentPhoneLaunchesOnceWithoutASeparateOptInOrPhoneSelection() {
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        val receiver = PhoneBluetoothReceiver()
        receiver.onReceive(context, event(selected))
        assertTrue(launches.isEmpty())
        DiPlayPreferences.saveAutoConnect(app, true)
        val current = "AA:BB:CC:DD:EE:FF"
        receiver.onReceive(context, event(current))
        receiver.onReceive(context, event(current))
        assertEquals(1, launches.size)
        assertEquals(current, DiPlayPreferences.phoneAddress(app))
        assertEquals(current, launches.single().getStringExtra(PhoneBluetoothReceiver.EXTRA_PHONE_ADDRESS))
        assertEquals(DiPlayActivity::class.java.name, launches.single().component?.className)
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=startActivity-returned"))
    }

    @Test fun receiverProfilesLaunchOnlyWhenConnectedAndShareAclDebounce() {
        for (action in PhoneBluetoothReceiver.ACTIONS.keys.filter { it != BluetoothDevice.ACTION_ACL_CONNECTED &&
            it != BluetoothDevice.ACTION_ACL_DISCONNECTED }) {
            reset()
            DiPlayPreferences.saveAutoConnect(app, true)
            val launches = mutableListOf<Intent>()
            val context = object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) { launches += intent }
            }
            val receiver = PhoneBluetoothReceiver()
            for (state in listOf(BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING, -1)) {
                receiver.onReceive(context, event(selected).setAction(action).putExtra(BluetoothProfile.EXTRA_STATE, state))
            }
            receiver.onReceive(context, event(selected).setAction(action)
                .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED))
            receiver.onReceive(context, event(selected))
            assertEquals(action, 1, launches.size)
            assertTrue(PhoneWakeDiagnostics.report(app).contains("lastEventDecision=debounced"))
        }
    }

    @Test fun failedActivityLaunchDoesNotBlockFollowingProfileEvent() {
        DiPlayPreferences.saveAutoConnect(app, true)
        var attempts = 0
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                if (attempts++ == 0) throw SecurityException("Launch rejected")
            }
        }
        val receiver = PhoneBluetoothReceiver()
        receiver.onReceive(context, event(selected))
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=failed"))
        receiver.onReceive(context, event(selected).setAction(
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED))
        assertEquals(2, attempts)
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=startActivity-returned"))
    }

    @Test fun aConnectedPhoneDoesNotRequirePreselection() {
        DiPlayPreferences.saveAutoConnect(app, true)
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().remove("phone_address").commit()
        PhoneBluetoothReceiver().onReceive(app, event(selected))
        assertEquals(selected, DiPlayPreferences.phoneAddress(app))
        assertEquals(DiPlayActivity::class.java.name, shadowOf(app).nextStartedActivity?.component?.className)
    }

    @Test fun aRealDisconnectAllowsAnImmediateReconnectButOtherPhoneDoesNot() {
        DiPlayPreferences.saveAutoConnect(app, true)
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        val receiver = PhoneBluetoothReceiver()
        receiver.onReceive(context, event(selected))
        receiver.onReceive(context, event("AA:BB:CC:DD:EE:FF").setAction(BluetoothDevice.ACTION_ACL_DISCONNECTED))
        receiver.onReceive(context, event(selected))
        assertEquals(1, launches.size)
        receiver.onReceive(context, event(selected).setAction(BluetoothDevice.ACTION_ACL_DISCONNECTED))
        assertEquals(1, launches.size)
        receiver.onReceive(context, event(selected))
        assertEquals(2, launches.size)
        assertEquals(selected, launches.last().getStringExtra(PhoneBluetoothReceiver.EXTRA_PHONE_ADDRESS))
    }

    @Test fun changingSelectedPhoneDoesNotInheritPreviousPhoneDebounce() {
        DiPlayPreferences.saveAutoConnect(app, true)
        var launches = 0
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches++ }
        }
        val receiver = PhoneBluetoothReceiver()
        receiver.onReceive(context, event(selected))
        DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:FF", "Other")
        receiver.onReceive(context, event("AA:BB:CC:DD:EE:FF"))
        assertEquals(2, launches)
    }

    @Test fun rebootDoesNotInheritElapsedTimeDebounce() {
        DiPlayPreferences.saveAutoConnect(app, true)
        var launches = 0
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches++ }
        }
        val receiver = PhoneBluetoothReceiver()
        android.provider.Settings.Global.putInt(app.contentResolver, "boot_count", 1)
        receiver.onReceive(context, event(selected))
        android.provider.Settings.Global.putInt(app.contentResolver, "boot_count", 2)
        receiver.onReceive(context, event(selected))
        assertEquals(2, launches)
    }

    @Test @Config(sdk = [33]) fun missingBluetoothPermissionDoesNotLaunch() {
        DiPlayPreferences.saveAutoConnect(app, true)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        var launches = 0
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches++ }
        }
        PhoneBluetoothReceiver().onReceive(context, event(selected))
        assertEquals(0, launches)
        assertTrue(PhoneWakeDiagnostics.report(app).contains("lastEventDecision=permission-missing"))
    }
}
