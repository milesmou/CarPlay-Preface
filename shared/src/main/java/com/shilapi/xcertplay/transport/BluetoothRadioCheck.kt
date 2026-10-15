package com.shilapi.xcertplay.transport

import java.io.IOException
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure

/** Reject a wireless start before reserving a temporary hotspot when Bluetooth cannot reach the iPhone. */
object BluetoothRadioCheck {
    fun blocker(adapterPresent: Boolean, enabled: Boolean): String? = when {
        !adapterPresent -> NO_ADAPTER
        !enabled -> RADIO_OFF
        else -> null
    }
    fun failure(blocker: String): IOException =
        if (blocker == NO_ADAPTER) WirelessStartupException(WirelessStartupFailure.BLUETOOTH_UNUSABLE, blocker)
        else IOException(blocker)

    const val NO_ADAPTER = "Bluetooth adapter is unavailable: Android on this head unit ${BluetoothLinkCheck.NO_SOCKETS_MARK}"
    const val RADIO_OFF_MARK = "Turn on Bluetooth"
    const val RADIO_OFF = "$RADIO_OFF_MARK in the head unit's settings, then reconnect"
}
