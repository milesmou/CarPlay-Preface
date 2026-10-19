package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/** Automatic connection uses the phone from the connected event; receiver profiles supplement ACL. */
class PhoneBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val source = ACTIONS[intent.action] ?: return
        if (!DiPlayPreferences.autoConnect(context)) return
        fun skip(reason: String) { PhoneWakeDiagnostics.observe(context, source, reason) }
        val disconnected = intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED
        if (!disconnected && intent.action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) {
            skip("not-connected"); return
        }
        val selected = DiPlayPreferences.phoneAddress(context)
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            skip("permission-missing"); return
        }
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
        if (device == null) { skip("device-missing"); return }
        val address = runCatching { device.address }.getOrNull()
        if (address == null) { skip("address-unavailable"); return }
        if (!disconnected) {
            val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
            if (major != null && major !in setOf(android.bluetooth.BluetoothClass.Device.Major.PHONE,
                    android.bluetooth.BluetoothClass.Device.Major.UNCATEGORIZED, android.bluetooth.BluetoothClass.Device.Major.MISC)) {
                skip("not-phone"); return
            }
        }
        if (!disconnected && ConnectionAutoStart.paused) { skip("cancelled-on-home"); return }
        if (!disconnected && UsbAutoConnect.priorityDevice(context) != null) {
            skip("usb-priority")
            UsbAutoConnect.request(context)
            return
        }

        val prefs = context.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE)
        if (disconnected) {
            if (address.equals(selected, true)) prefs.edit().remove("last_launch_elapsed").apply()
            skip("disconnected"); return
        }

        // A retained failed controller is not a connected CarPlay session. Let its owner resume
        // bounded recovery without replacing USB, a live session, or the hotspot reservation.
        CarPlayBackgroundSession.phoneBluetoothConnected(address)?.let { outcome ->
            skip(outcome)
            PhoneWakeDiagnostics.record(context, outcome)
            return
        }

        // The car can send several ACL events during one wake-up. Commit before launching so
        // another receiver instance cannot open a second connection from the same event burst.
        val now = SystemClock.elapsedRealtime()
        val previous = prefs.getLong("last_launch_elapsed", 0)
        val boot = android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1)
        if (prefs.getString("phone", null).equals(address, ignoreCase = true) &&
            prefs.getInt("boot", -2) == boot && prefs.contains("last_launch_elapsed") &&
            now >= previous && now - previous < 15_000) {
            skip("debounced"); return
        }
        DiPlayPreferences.savePhone(context, address, runCatching { device.name }.getOrNull() ?: "iPhone")
        prefs.edit().putLong("last_launch_elapsed", now).putString("phone", address).putInt("boot", boot).commit()
        PhoneWakeDiagnostics.observe(context, source, "matched")
        PhoneWakeDiagnostics.record(context, "matched")
        val launch = ConnectionAutoStart.stamp(Intent(context, DiPlayActivity::class.java)).apply {
            putExtra(EXTRA_AUTO_CONNECT, true)
            putExtra(EXTRA_PHONE_ADDRESS, address)
            putExtra(EXTRA_WAKE_ELAPSED, now)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(launch) }
            .onSuccess { PhoneWakeDiagnostics.record(context, "startActivity-returned") }
            .onFailure { error ->
                // A rejected launch must not suppress the next usable profile event.
                prefs.edit().remove("last_launch_elapsed").commit()
                PhoneWakeDiagnostics.record(context, "failed", error)
                Log.w("DiPlayPhoneWake", "Bluetooth wake launch failed: ${error.javaClass.simpleName}")
            }
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "com.shihab.diplay.AUTO_CONNECT_SELECTED_PHONE"
        internal const val EXTRA_PHONE_ADDRESS = "com.shihab.diplay.AUTO_CONNECT_PHONE_ADDRESS"
        internal const val EXTRA_WAKE_ELAPSED = "com.shihab.diplay.AUTO_CONNECT_WAKE_ELAPSED"
        internal val ACTIONS = mapOf(
            BluetoothDevice.ACTION_ACL_CONNECTED to "acl",
            BluetoothDevice.ACTION_ACL_DISCONNECTED to "acl-disconnected",
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED" to "a2dp-sink",
            "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED" to "avrcp-controller",
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED" to "headset-client")

        /** ACL uses the manifest receiver; receiver-side profiles supplement it while this process lives. */
        fun register(context: Context) {
            runCatching {
                ContextCompat.registerReceiver(context, PhoneBluetoothReceiver(),
                    IntentFilter().apply { ACTIONS.keys.filter { it != BluetoothDevice.ACTION_ACL_CONNECTED &&
                        it != BluetoothDevice.ACTION_ACL_DISCONNECTED }.forEach(::addAction) }, ContextCompat.RECEIVER_EXPORTED)
            }.onFailure { PhoneWakeDiagnostics.record(context, "receiver-registration-failed", it) }
        }
    }
}

/** Only a timestamp and fixed outcome; the Bluetooth address and broadcast extras stay out of reports. */
internal object PhoneWakeDiagnostics {
    private const val PREFS = "diplay_phone_wake_diagnostics"

    fun observe(context: Context, source: String, decision: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("eventSource", source).putString("eventDecision", decision)
            .putLong("eventAt", System.currentTimeMillis()).apply()
    }

    fun record(context: Context, result: String, error: Throwable? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("at", System.currentTimeMillis())
            .putString("result", result)
            .putString("failureClass", error?.javaClass?.simpleName ?: "none")
            .apply()
    }

    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong("at", 0)
        val state = "enabled=${DiPlayPreferences.autoConnect(context)} " +
            "selectedPhone=${DiPlayPreferences.phoneAddress(context) != null} " +
            "lastEventSource=${prefs.getString("eventSource", "none")} " +
            "lastEventDecision=${prefs.getString("eventDecision", "none")} " +
            "lastEventAtEpochMs=${prefs.getLong("eventAt", 0)}"
        return (if (at == 0L) "Phone Bluetooth wake: no matching event recorded"
        else "Phone Bluetooth wake: matchedAtEpochMs=$at result=${prefs.getString("result", "unknown")} " +
            "failureClass=${prefs.getString("failureClass", "none")}; " +
            "startActivity-returned does not prove the car displayed DiPlay.") + "; $state"
    }
}
