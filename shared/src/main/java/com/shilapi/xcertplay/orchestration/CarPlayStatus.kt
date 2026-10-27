package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.network.WirelessStartupFailure

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
        val passphrase: String = "",
    ) : CarPlayStatus() {
        override fun toString(): String = "HotspotReady(band=$band, channel=$channel, backend=$backend)"
    }
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object WirelessActiveFallback : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object TestingUsbNcm : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String, val wifiResetRequired: Boolean = false,
        val startupFailure: WirelessStartupFailure? = null, val retryable: Boolean = true,
        val usbDirectUnavailable: Boolean = false, val usbNcmTested: Boolean = false,
        val usbVpnUnavailable: Boolean = false) : CarPlayStatus()
}

/** A Release event keeps failure reasons without dumping hotspot credentials or device details. */
internal fun CarPlayStatus.retainedDiagnosticMessage(): String = when (this) {
    is CarPlayStatus.Failed -> "Connection state=Failed retryable=$retryable reason=$message"
    else -> "Connection state=${javaClass.simpleName}"
}
