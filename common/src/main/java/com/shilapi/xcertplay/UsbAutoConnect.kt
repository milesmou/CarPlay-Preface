package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.transport.IphoneUsbMatcher

/** USB has priority until the driver explicitly chooses wireless after a failed wired attempt. */
internal object UsbAutoConnect {
    private var attemptedDevice: String? = null
    private var failedDevice: String? = null
    private var wirelessDevice: String? = null
    private const val EXTRA_GENERATION = "com.shihab.diplay.USB_REQUEST_GENERATION"
    var generation = 0L
        private set

    fun attached(context: Context): UsbDevice? = runCatching {
        context.getSystemService(UsbManager::class.java)?.deviceList?.values
            ?.filter { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }?.sortedBy { it.deviceName }?.firstOrNull()
    }.getOrNull()

    fun wirelessSelected(context: Context): Boolean =
        attached(context)?.deviceName?.let { it == wirelessDevice } == true

    fun priorityDevice(context: Context): UsbDevice? =
        attached(context)?.takeUnless { it.deviceName == wirelessDevice }

    fun canChooseWireless(context: Context): Boolean {
        val device = priorityDevice(context) ?: return true
        val session = CarPlayBackgroundSession.snapshot()?.controller
        val activeWired = CarPlayBackgroundSession.active && session != null && !session.isClosed() &&
            session.connectionTransport() == CarPlayTransport.WIRED
        return failedDevice == device.deviceName && !activeWired
    }

    /** Only the explicit home-device action releases this cable's failed USB priority. */
    fun chooseWireless(context: Context): Boolean {
        if (!canChooseWireless(context)) return false
        attached(context)?.let { device ->
            if (wirelessDevice != device.deviceName) {
                wirelessDevice = device.deviceName
                generation++ // invalidate queued USB launches and authorization callbacks
            }
        }
        return true
    }

    fun beginWiredAttempt(context: Context): Long {
        attached(context)?.let { device ->
            attemptedDevice = device.deviceName
            failedDevice = null
            wirelessDevice = null
            generation++
        }
        return generation
    }

    fun wiredFailed(context: Context, attempt: Long) {
        if (attempt == generation && attached(context)?.deviceName == attemptedDevice) failedDevice = attemptedDevice
    }

    fun wiredConnected(attempt: Long) {
        if (attempt == generation) failedDevice = null
    }

    fun isCurrentRequest(intent: Intent): Boolean =
        !intent.hasExtra(EXTRA_GENERATION) || intent.getLongExtra(EXTRA_GENERATION, -1) == generation

    fun request(context: Context, force: Boolean = false): Boolean {
        val device = priorityDevice(context) ?: return false
        val session = CarPlayBackgroundSession.snapshot()?.controller
        if (!force && session != null && !session.isClosed() && session.connectionTransport() == CarPlayTransport.WIRED) return true
        if (attemptedDevice == device.deviceName) return true
        val attempt = beginWiredAttempt(context)
        val launch = Intent(context, CarPlayHostActivity::class.java)
            .setAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .putExtra(UsbManager.EXTRA_DEVICE, device)
            .putExtra(EXTRA_GENERATION, attempt)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return runCatching { context.startActivity(launch); true }.getOrElse {
            android.util.Log.w("DiPlayUsbStart", "USB auto-connect launch failed: ${it.javaClass.simpleName}")
            wiredFailed(context, attempt)
            true // only an explicit wireless selection releases the failed cable's priority
        }
    }

    fun register(context: Context) {
        runCatching { ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                if (device.vendorId != IphoneUsbMatcher.APPLE_VENDOR_ID) return
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    if (device.deviceName in listOf(attemptedDevice, failedDevice, wirelessDevice)) {
                        attemptedDevice = null
                        failedDevice = null
                        wirelessDevice = null
                        generation++
                    }
                } else if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) request(context, force = true)
            }
        }, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED) }.onFailure {
            android.util.Log.w("DiPlayUsbStart", "USB receiver registration failed: ${it.javaClass.simpleName}")
        }
    }

    internal fun resetForTest() {
        attemptedDevice = null
        failedDevice = null
        wirelessDevice = null
        generation = 0
    }
}
