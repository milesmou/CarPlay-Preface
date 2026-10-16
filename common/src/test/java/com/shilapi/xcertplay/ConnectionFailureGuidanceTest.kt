package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionFailureGuidanceTest {
    @Test fun wiredVpnFailureExplainsPermissionAndSystemComponents() {
        assertEquals(R.string.connection_help_usb_vpn, ConnectionFailureGuidance.message(
            CarPlayStatus.Failed("VPN failed", retryable = false, usbVpnUnavailable = true), false))
    }
    @Test fun automaticFrameTestAsksForTheReportAndReplug() {
        assertEquals(R.string.connection_help_usb_ncm_test, ConnectionFailureGuidance.message(
            CarPlayStatus.Failed("test ended", retryable = false, usbNcmTested = true), false))
    }
    @Test fun directUsbFailureExplainsTheNetworkRequirementWithoutRequestingVpn() {
        assertEquals(R.string.connection_help_usb_direct, ConnectionFailureGuidance.message(
            CarPlayStatus.Failed("network listener failed", retryable = false, usbDirectUnavailable = true), false))
    }
    @Test fun bluetoothOffAsksToEnableTheHeadUnitRadio() {
        assertEquals(R.string.turn_on_bluetooth_in_the_head_unit_s_settings_to_connect,
            ConnectionFailureGuidance.message(CarPlayStatus.Failed("Turn on Bluetooth in the head unit"), true))
    }

    @Test fun missingBluetoothAdapterOffersWiredConnection() {
        assertEquals(R.string.connection_help_bluetooth_unusable,
            ConnectionFailureGuidance.message(CarPlayStatus.Failed("Bluetooth adapter is unavailable"), true))
    }

    @Test fun hotspotSetupAndJoinFailuresDescribeTheTemporaryNetwork() {
        assertEquals(R.string.connection_help_temporary_hotspot, ConnectionFailureGuidance.message(
            CarPlayStatus.Failed("AP failed", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION), true))
        assertEquals(R.string.connection_help_temporary_hotspot_join, ConnectionFailureGuidance.message(
            CarPlayStatus.Failed("TCP timed out", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT), true))
    }

    @Test fun wiredNetworkFailureKeepsTheUsbCableGuidance() {
        assertEquals(R.string.connection_help_usb,
            ConnectionFailureGuidance.message(CarPlayStatus.Failed("NCM USB interface missing"), false))
    }
}
