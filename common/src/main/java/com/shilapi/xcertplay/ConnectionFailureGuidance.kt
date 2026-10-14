package com.shilapi.xcertplay

import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.Locale

/** Suggested user action, never a diagnosis inferred from an arbitrary exception message. */
internal object ConnectionFailureGuidance {
    @StringRes
    fun message(
        failure: CarPlayStatus.Failed,
        wireless: Boolean,
        mode: WirelessHotspotMode = WirelessHotspotMode.LOCAL_ONLY_HOTSPOT,
    ): Int {
        if (!wireless && failure.usbDirectUnavailable) return R.string.connection_help_usb_direct
        if (failure.wifiResetRequired) return R.string.connection_help_reset_wifi
        when (failure.startupFailure) {
            WirelessStartupFailure.BLUETOOTH_UNUSABLE -> return R.string.connection_help_bluetooth_unusable
            WirelessStartupFailure.HOTSPOT_NOT_READY,
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> return network(mode)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> return iphoneWifi(mode)
            null -> Unit
        }

        val error = failure.message.lowercase(Locale.ROOT)
        if ("turn on bluetooth" in error) return R.string.turn_on_bluetooth_in_the_head_unit_s_settings_to_connect
        if ("bluetooth adapter is unavailable" in error) return R.string.connection_help_bluetooth_unusable
        // A permission refused by the firmware is not one the driver can grant.
        if ("securityexception" in error || "lacks permission" in error) return R.string.connection_help_general
        if ("permission" in error || "denied" in error) return R.string.connection_help_permissions
        if (!wireless) {
            return if (listOf("usb", "ncm", "configuration", "iphone", "cable").any { it in error }) {
                R.string.connection_help_usb
            } else {
                R.string.connection_help_general
            }
        }
        if ("bluetooth" in error || "rfcomm" in error || "paired" in error) {
            return R.string.connection_help_bluetooth
        }
        if ("hotspot" in error || "access point" in error) {
            return network(mode)
        }
        if ("wifi" in error || "wi-fi" in error || "tcp" in error || "airplay" in error) {
            return iphoneWifi(mode)
        }
        return R.string.connection_help_general
    }

    @StringRes
    private fun network(mode: WirelessHotspotMode): Int = if (mode == WirelessHotspotMode.MANUAL)
        R.string.settings_manual_hotspot_join else R.string.connection_help_temporary_hotspot

    @StringRes
    private fun iphoneWifi(mode: WirelessHotspotMode): Int = if (mode == WirelessHotspotMode.MANUAL)
        R.string.settings_manual_hotspot_join else R.string.connection_help_temporary_hotspot_join
}
