package com.shilapi.xcertplay.transport

/** Reject a wireless start before reserving a temporary hotspot when Bluetooth cannot reach the iPhone. */
object BluetoothRadioCheck {
    fun blocker(adapterPresent: Boolean, enabled: Boolean): String? = when {
        !adapterPresent -> NO_ADAPTER
        !enabled -> RADIO_OFF
        else -> null
    }
    const val NO_ADAPTER = "Bluetooth adapter is unavailable"
    const val RADIO_OFF_MARK = "Turn on Bluetooth"
    const val RADIO_OFF = "$RADIO_OFF_MARK in the head unit's settings, then reconnect"
}
