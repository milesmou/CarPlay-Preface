package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TemporaryHotspotPreflightTest {
    @Test fun disabledBluetoothNeverConstructsTheHotspotManager() {
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(adapter.isEnabled).thenReturn(false)
        assertBlocked(adapter, BluetoothRadioCheck.RADIO_OFF)
    }

    @Test fun missingAdapterNeverConstructsTheHotspotManager() =
        assertBlocked(null, BluetoothRadioCheck.NO_ADAPTER)

    private fun assertBlocked(adapter: BluetoothAdapter?, message: String) {
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test",
                    manufacturer = "test", serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1536, heightPixels = 792)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        controller.javaClass.getDeclaredField("bluetoothAdapter").apply { isAccessible = true }.set(controller, adapter)
        try {
            mockConstruction(LocalOnlyHotspotManager::class.java).use { construction ->
                val method = controller.javaClass.getDeclaredMethod("startWirelessHotspot", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                val failure = assertThrows(InvocationTargetException::class.java) { method.invoke(controller, 0) }
                assertTrue(failure.targetException is IOException)
                assertEquals(message, failure.targetException.message)
                assertTrue(construction.constructed().isEmpty())
            }
        } finally {
            controller.close()
        }
    }
}
