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

/** USB takes precedence at every launch boundary, including delayed wireless callbacks. */
internal object UsbAutoConnect {
    private var attemptedDevice: String? = null
    var generation = 0L
        private set

    fun attached(context: Context): UsbDevice? = runCatching {
        context.getSystemService(UsbManager::class.java)?.deviceList?.values
            ?.filter { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }?.sortedBy { it.deviceName }?.firstOrNull()
    }.getOrNull()

    fun request(context: Context, force: Boolean = false): Boolean {
        val device = attached(context) ?: return false
        val session = CarPlayBackgroundSession.snapshot()?.controller
        if (!force && session != null && !session.isClosed() && session.connectionTransport() == CarPlayTransport.WIRED) return true
        if (attemptedDevice == device.deviceName) return true
        attemptedDevice = device.deviceName
        generation++
        val launch = Intent(context, CarPlayHostActivity::class.java)
            .setAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .putExtra(UsbManager.EXTRA_DEVICE, device)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return runCatching { context.startActivity(launch); true }.getOrElse {
            android.util.Log.w("DiPlayUsbStart", "USB auto-connect launch failed: ${it.javaClass.simpleName}")
            true // the cable still owns priority, even when the firmware rejects the launch
        }
    }

    fun register(context: Context) {
        runCatching { ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                if (device.vendorId != IphoneUsbMatcher.APPLE_VENDOR_ID) return
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    if (attemptedDevice == device.deviceName) attemptedDevice = null
                } else if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) request(context, force = true)
            }
        }, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED) }.onFailure {
            android.util.Log.w("DiPlayUsbStart", "USB receiver registration failed: ${it.javaClass.simpleName}")
        }
    }

    internal fun resetForTest() { attemptedDevice = null; generation = 0 }
}
