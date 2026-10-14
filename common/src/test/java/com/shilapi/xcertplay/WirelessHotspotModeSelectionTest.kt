package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WirelessHotspotModeSelectionTest {
    @Test fun manualModeUsesSavedCredentialsAndNeverCreatesATemporaryHotspot() = select(WirelessHotspotMode.MANUAL)
    @Test fun temporaryModeNeverAttachesToTheSystemHotspot() = select(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)

    private fun select(mode: WirelessHotspotMode) {
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
                wirelessHotspotMode = mode, manualHotspotSsid = "Xingrui", manualHotspotPassphrase = "password123",
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 720)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(adapter.isEnabled).thenReturn(true)
        controller.javaClass.getDeclaredField("bluetoothAdapter").apply { isAccessible = true }.set(controller, adapter)
        val phase = controller.javaClass.getDeclaredField("phase").apply { isAccessible = true }
        phase.set(controller, phase.type.enumConstants.first { (it as Enum<*>).name == "WIRELESS" })
        val info = WirelessHotspotInfo("Xingrui", "password123", Iap2WirelessSecurity.WPA_WPA2, 0,
            null, null, "ap0", InetAddress.getByName("192.0.2.1"), "Auto", WirelessHotspotBackend.MANUAL_HOTSPOT)
        var credentials: List<*>? = null
        try {
            mockConstruction(ManualHotspotManager::class.java) { manager, context ->
                credentials = context.arguments()
                `when`(manager.start(anyLong())).thenReturn(info)
            }.use { manual ->
                mockConstruction(LocalOnlyHotspotManager::class.java) { manager, _ ->
                    `when`(manager.start(anyLong())).thenReturn(info)
                }.use { temporary ->
                    val selected = controller.javaClass.getDeclaredMethod("startWirelessHotspot", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(controller, 0)
                    assertSame(info, selected)
                    assertEquals(if (mode == WirelessHotspotMode.MANUAL) 1 else 0, manual.constructed().size)
                    assertEquals(if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) 1 else 0, temporary.constructed().size)
                    if (mode == WirelessHotspotMode.MANUAL) {
                        assertEquals("Xingrui", credentials!![1])
                        assertEquals("password123", credentials!![2])
                    }
                    controller.close()
                    assertTrue(controller.awaitClosed(2_000))
                    val manager: WirelessHotspotManager = if (mode == WirelessHotspotMode.MANUAL)
                        manual.constructed().single() else temporary.constructed().single()
                    verify(manager).close()
                }
            }
        } finally { controller.close() }
    }
}
