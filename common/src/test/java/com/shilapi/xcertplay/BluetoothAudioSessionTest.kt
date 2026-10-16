package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BluetoothAudioSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val adapter get() = BluetoothAdapter.getDefaultAdapter()
    private val address = "02:11:22:33:44:55"
    private lateinit var profile: MusicProfile
    private lateinit var controller: CarPlayController
    private lateinit var listener: AirPlaySessionListener
    private val playing = mutableListOf<Boolean>()

    @Before fun prepare() {
        shadowOf(adapter).setEnabled(true)
        val phone = adapter.getRemoteDevice(address)
        shadowOf(phone).setBondState(BluetoothDevice.BOND_BONDED)
        shadowOf(adapter).setBondedDevices(setOf(phone))
        profile = MusicProfile(phone)
        shadowOf(adapter).setProfileProxy(11, profile)
    }

    @After fun cleanup() {
        controller.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun connect(bluetooth: Boolean): AirPlaySession {
        controller = CarPlayController(app,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig("test", address, "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 720), disableAudioOutput = bluetooth),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
            onNativeBluetoothPlaying = playing::add)
        ReflectionHelpers.setField(controller, "wirelessPeerBluetoothAddress", address)
        listener = ReflectionHelpers.getField(controller, "sessionListener")
        return mock(AirPlaySession::class.java).also { listener.onSessionActive(it); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun bluetoothModeKeepsNativeMusicEvenWhenPhoneAsksToDisableBluetoothAndSinkClaimsFocus() {
        val session = connect(true)
        AirPlayPersistence.saveCarBluetoothAudio(app, false) // applies only to a future connection
        controller.setBluetoothAudioOwnership(true)
        listener.onCommand(session, "disableBluetooth", mapOf("deviceID" to address))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(controller.usesCarBluetoothAudio)
        assertEquals(0, profile.disconnects)
        assertTrue(playing.contains(true))
    }

    @Test fun queuedOldSessionCannotTouchReplacementPhoneSession() {
        val first = connect(false)
        listener.onCommand(first, "disableBluetooth", mapOf("deviceID" to address))
        val replacementProfile = MusicProfile(profile.phone)
        shadowOf(adapter).setProfileProxy(11, replacementProfile)
        val next = mock(AirPlaySession::class.java)
        // Replacement happens before the command's posted handoff can execute.
        listener.onSessionActive(next)
        listener.onSessionEnded(first)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, profile.disconnects)
        listener.onCommand(next, "disableBluetooth", mapOf("deviceID" to address))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, replacementProfile.disconnects)
    }

    @Test fun phoneCannotRedirectHandoffToAnotherBondedDevice() {
        val session = connect(false)
        val other = adapter.getRemoteDevice("02:66:77:88:99:AA")
        shadowOf(other).setBondState(BluetoothDevice.BOND_BONDED)
        shadowOf(adapter).setBondedDevices(setOf(profile.phone, other))
        listener.onCommand(session, "disableBluetooth", mapOf("deviceID" to other.address))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, profile.disconnects)
        listener.onCommand(session, "disableBluetooth", mapOf("deviceID" to address))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, profile.disconnects)
    }

    class MusicProfile(val phone: BluetoothDevice) : BluetoothProfile {
        var disconnects = 0
        private var state = BluetoothProfile.STATE_CONNECTED
        override fun getConnectedDevices() = if (state == BluetoothProfile.STATE_CONNECTED) listOf(phone) else emptyList()
        override fun getDevicesMatchingConnectionStates(states: IntArray) = if (state in states) listOf(phone) else emptyList()
        override fun getConnectionState(device: BluetoothDevice) = if (device == phone) state else BluetoothProfile.STATE_DISCONNECTED
        fun isA2dpPlaying(device: BluetoothDevice) = getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
        fun disconnect(device: BluetoothDevice): Boolean { check(device == phone); disconnects++; state = BluetoothProfile.STATE_DISCONNECTED; return true }
        fun connect(device: BluetoothDevice): Boolean { check(device == phone); state = BluetoothProfile.STATE_CONNECTING; return true }
    }
}
