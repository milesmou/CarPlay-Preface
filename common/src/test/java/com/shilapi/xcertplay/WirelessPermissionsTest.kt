package com.shilapi.xcertplay

import android.Manifest
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessPermissionsTest {
    @Test fun hotspotModesRequestLocalNetworkOnlyOnAndroid17() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ),
            WirelessPermissions.required(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, 37),
        )
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, 29),
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, 31),
        )
    }

    @Test fun temporaryHotspotUsesLocationOnAndroid9AndNearbyDevicesOnAndroid13() {
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            WirelessPermissions.required(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, 28))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES),
            WirelessPermissions.required(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, 33))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_LOCAL_NETWORK),
            WirelessPermissions.required(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, 37))
    }
}
