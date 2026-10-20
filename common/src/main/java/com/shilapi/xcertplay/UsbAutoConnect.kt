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
    private var priority = UsbConnectionPriority()
    private const val EXTRA_MANUAL = "com.preface.carplay.MANUAL_CONNECTION_REQUEST"
    private const val EXTRA_AUTOMATIC = "com.preface.carplay.AUTOMATIC_CONNECTION_REQUEST"
    fun markManualRequest(intent: Intent): Intent = intent.putExtra(EXTRA_MANUAL, true)
    fun markAutomaticRequest(intent: Intent): Intent = intent.putExtra(EXTRA_AUTOMATIC, true)
    fun isManualRequest(intent: Intent): Boolean = intent.getBooleanExtra(EXTRA_MANUAL, false)
    fun automaticRequestDisabled(context: Context, intent: Intent): Boolean =
        !isManualRequest(intent) && !DiPlayPreferences.autoConnect(context) &&
            (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED || intent.getBooleanExtra(EXTRA_AUTOMATIC, false))

    private const val EXTRA_GENERATION = "com.preface.carplay.USB_REQUEST_GENERATION"
    val generation get() = priority.generation

    fun attached(context: Context): UsbDevice? = runCatching {
        context.getSystemService(UsbManager::class.java)?.deviceList?.values
            ?.filter { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }?.sortedBy { it.deviceName }?.firstOrNull()
    }.getOrNull()

    fun wirelessSelected(context: Context): Boolean =
        priority.wirelessSelected(attached(context)?.deviceName)

    fun priorityDevice(context: Context): UsbDevice? =
        attached(context)?.takeUnless { priority.wirelessSelected(it.deviceName) }

    fun canChooseWireless(context: Context): Boolean {
        val device = priorityDevice(context) ?: return true
        val session = CarPlayBackgroundSession.snapshot()?.controller
        val activeWired = CarPlayBackgroundSession.active && session != null && !session.isClosed() &&
            session.connectionTransport() == CarPlayTransport.WIRED
        return priority.canChooseWireless(device.deviceName, activeWired)
    }

    /** Only the explicit home-device action releases this cable's failed USB priority. */
    fun chooseWireless(context: Context): Boolean {
        if (!canChooseWireless(context)) return false
        priority.chooseWireless(attached(context)?.deviceName)
        return true
    }

    /** Do not let polling or duplicate attachment events restart the cable the driver cancelled. */
    fun cancelForHome(context: Context) {
        priority.cancelForHome(attached(context)?.deviceName)
    }

    fun beginWiredAttempt(context: Context): Long = priority.beginWiredAttempt(attached(context)?.deviceName)
    fun wiredFailed(context: Context, attempt: Long) = priority.wiredFailed(attached(context)?.deviceName, attempt)
    fun wiredConnected(attempt: Long) = priority.wiredConnected(attempt)

    fun isCurrentRequest(intent: Intent): Boolean =
        !intent.hasExtra(EXTRA_GENERATION) || intent.getLongExtra(EXTRA_GENERATION, -1) == generation

    fun request(context: Context, force: Boolean = false, manual: Boolean = false): Boolean {
        if (manual) ConnectionAutoStart.resume()
        else if (!DiPlayPreferences.autoConnect(context) || ConnectionAutoStart.paused) return false
        val device = priorityDevice(context) ?: return false
        val session = CarPlayBackgroundSession.snapshot()?.controller
        if (!force && session != null && !session.isClosed() && session.connectionTransport() == CarPlayTransport.WIRED) return true
        if (!manual && priority.alreadyAttempted(device.deviceName)) return true
        val attempt = beginWiredAttempt(context)
        val launch = ConnectionAutoStart.stamp(Intent(context, CarPlayHostActivity::class.java))
            .setAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .putExtra(UsbManager.EXTRA_DEVICE, device)
            .putExtra(EXTRA_GENERATION, attempt)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (manual) markManualRequest(launch) else markAutomaticRequest(launch)
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
                    priority.detached(device.deviceName)
                } else if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                    if (!DiPlayPreferences.autoConnect(context)) return
                    if (ConnectionAutoStart.paused) {
                        if (priority.wasCancelled(device.deviceName)) return
                        ConnectionAutoStart.resume()
                    }
                    request(context, force = true)
                }
            }
        }, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED) }.onFailure {
            android.util.Log.w("DiPlayUsbStart", "USB receiver registration failed: ${it.javaClass.simpleName}")
        }
    }

    internal fun resetForTest() {
        priority = UsbConnectionPriority()
        ConnectionAutoStart.resetForTest()
    }
}
