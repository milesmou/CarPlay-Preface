package com.shilapi.xcertplay

/** Cable-scoped state. Android discovery and launching are owned by [UsbAutoConnect]. */
internal class UsbConnectionPriority {
    private var attemptedDevice: String? = null
    private var failedDevice: String? = null
    private var wirelessDevice: String? = null
    private var cancelledDevice: String? = null
    var generation = 0L
        private set

    fun wirelessSelected(device: String?): Boolean = device != null && device == wirelessDevice
    fun canChooseWireless(device: String?, activeWired: Boolean): Boolean =
        device == null || wirelessSelected(device) || (failedDevice == device && !activeWired)

    fun chooseWireless(device: String?) {
        if (device != null && wirelessDevice != device) {
            wirelessDevice = device
            generation++
        }
    }

    fun cancelForHome(device: String?) {
        cancelledDevice = device
        attemptedDevice = device
        failedDevice = device
        generation++
    }

    fun beginWiredAttempt(device: String?): Long {
        if (device != null) {
            attemptedDevice = device
            failedDevice = null
            wirelessDevice = null
            generation++
        }
        return generation
    }

    fun wiredFailed(device: String?, attempt: Long) {
        if (attempt == generation && device == attemptedDevice) failedDevice = attemptedDevice
    }

    fun wiredConnected(attempt: Long) {
        if (attempt == generation) failedDevice = null
    }

    fun alreadyAttempted(device: String): Boolean = attemptedDevice == device
    fun wasCancelled(device: String): Boolean = cancelledDevice == device
    fun detached(device: String) {
        if (device == cancelledDevice) cancelledDevice = null
        if (device in listOf(attemptedDevice, failedDevice, wirelessDevice)) {
            attemptedDevice = null
            failedDevice = null
            wirelessDevice = null
            generation++
        }
    }
}
