package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class ConnectedBluetoothPhonesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val adapter get() = BluetoothAdapter.getDefaultAdapter()
    private val phone get() = adapter.getRemoteDevice("12:34:56:78:90:AB")
    private val states = mutableListOf<ConnectedPhoneState>()
    private lateinit var monitor: ConnectedBluetoothPhones
    private val profile = PhonesProfile()

    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBondedDevices(setOf(phone))
        shadowOf(adapter).setProfileProxy(11, profile)
        monitor = ConnectedBluetoothPhones(app, states::add)
    }
    @After fun cleanup() { monitor.close() }

    @Test fun pairingAloneDoesNotAppearAsConnected() {
        monitor.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(states.last().phones.isEmpty())
    }

    @Test fun liveReceiverProfileAppearsAndDisappearsWithoutReopeningHome() {
        profile.devices = listOf(phone)
        monitor.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(phone.address), states.last().phones.map { it.address })
        profile.devices = emptyList()
        monitor.refresh()
        assertTrue(states.last().phones.isEmpty())
    }

    @Test fun aclDisconnectOverridesAStaleProfileSnapshotAndReconnectRestoresIt() {
        profile.devices = listOf(phone)
        monitor.start()
        shadowOf(Looper.getMainLooper()).idle()
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, phone))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(states.last().phones.isEmpty())
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, phone))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(phone.address), states.last().phones.map { it.address })
    }

    @Test fun turningBluetoothOffClearsDevicesAndClosingStopsUpdates() {
        profile.devices = listOf(phone)
        monitor.start()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(adapter).setEnabled(false)
        monitor.refresh()
        assertFalse(states.last().enabled)
        assertTrue(states.last().phones.isEmpty())
        monitor.close()
        val count = states.size
        shadowOf(adapter).setEnabled(true)
        monitor.refresh()
        assertEquals(count, states.size)
    }

    private class PhonesProfile : BluetoothProfile {
        var devices: List<BluetoothDevice> = emptyList()
        override fun getConnectedDevices() = devices
        override fun getDevicesMatchingConnectionStates(states: IntArray) = devices
        override fun getConnectionState(device: BluetoothDevice) =
            if (device in devices) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED
    }
}
