package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29])
class PhoneBluetoothReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val selected = "12:34:56:78:9A:BC"

    @Before fun reset() {
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, false)
        app.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_phone_wake_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        // Saved in lowercase on purpose: the receiver must match the (always uppercase) device
        // address case-insensitively; Robolectric refuses to build a lowercase remote device.
        DiPlayPreferences.savePhone(app, selected.lowercase(), "iPhone")
    }

    private fun event(address: String): Intent {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        return Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
    }

    @Test fun onlySelectedPhoneWithOptInLaunchesOnce() {
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        val receiver = PhoneBluetoothReceiver()
        receiver.onReceive(context, event(selected))
        assertTrue(launches.isEmpty())
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        receiver.onReceive(context, event("AA:BB:CC:DD:EE:FF"))
        assertTrue(launches.isEmpty())
        receiver.onReceive(context, event(selected))
        receiver.onReceive(context, event(selected))
        assertEquals(1, launches.size)
        assertEquals(DiPlayActivity::class.java.name, launches.single().component?.className)
        assertTrue(launches.single().getBooleanExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, false))
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=startActivity-returned"))
    }

    @Test fun receiverProfilesLaunchOnlyWhenConnectedAndShareAclDebounce() {
        for (action in PhoneBluetoothReceiver.ACTIONS.keys.filter { it != BluetoothDevice.ACTION_ACL_CONNECTED }) {
            reset()
            DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
            val launches = mutableListOf<Intent>()
            val context = object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) { launches += intent }
            }
            val receiver = PhoneBluetoothReceiver()
            for (state in listOf(BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING, -1)) {
                receiver.onReceive(context, event(selected).setAction(action).putExtra(BluetoothProfile.EXTRA_STATE, state))
            }
            receiver.onReceive(context, event("AA:BB:CC:DD:EE:FF").setAction(action)
                .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED))
            assertTrue(launches.isEmpty())
            receiver.onReceive(context, event(selected).setAction(action)
                .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED))
            receiver.onReceive(context, event(selected))
            assertEquals(action, 1, launches.size)
            assertTrue(PhoneWakeDiagnostics.report(app).contains("lastEventDecision=debounced"))
        }
    }

    @Test fun failedActivityLaunchDoesNotBlockFollowingProfileEvent() {
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
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

    @Test fun missingSelectedPhoneIsVisibleInDiagnostics() {
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().remove("phone_address").commit()
        PhoneBluetoothReceiver().onReceive(app, event(selected))
        assertTrue(PhoneWakeDiagnostics.report(app).contains("lastEventDecision=no-selected-phone"))
    }
}
