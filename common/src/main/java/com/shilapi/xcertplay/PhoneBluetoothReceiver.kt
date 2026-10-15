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

/** Optional wake-up path for the selected iPhone; receiver-side profiles supplement ACL. */
class PhoneBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val source = ACTIONS[intent.action] ?: return
        if (!DiPlayPreferences.connectOnPhoneBluetooth(context)) return
        fun skip(reason: String) { PhoneWakeDiagnostics.observe(context, source, reason) }
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) {
            skip("not-connected"); return
        }
        if (CarPlayBackgroundSession.hasSession()) { skip("session-present"); return }
        val selected = DiPlayPreferences.phoneAddress(context)
        if (selected == null) { skip("no-selected-phone"); return }
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
        if (!address.equals(selected, ignoreCase = true)) { skip("other-phone"); return }

        // The car can send several ACL events during one wake-up. Commit before launching so
        // another receiver instance cannot open a second connection from the same event burst.
        val prefs = context.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val previous = prefs.getLong("last_launch_elapsed", 0)
        if (prefs.contains("last_launch_elapsed") && now >= previous && now - previous < 15_000) {
            skip("debounced"); return
        }
        prefs.edit().putLong("last_launch_elapsed", now).commit()
        PhoneWakeDiagnostics.observe(context, source, "matched")
        PhoneWakeDiagnostics.record(context, "matched")
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            putExtra(EXTRA_AUTO_CONNECT, true)
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
        internal val ACTIONS = mapOf(
            BluetoothDevice.ACTION_ACL_CONNECTED to "acl",
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED" to "a2dp-sink",
            "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED" to "avrcp-controller",
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED" to "headset-client")

        /** Application lifetime: receives profile broadcasts while the launcher is in the background. */
        fun register(context: Context) {
            runCatching {
                ContextCompat.registerReceiver(context, PhoneBluetoothReceiver(),
                    IntentFilter().apply { ACTIONS.keys.forEach(::addAction) }, ContextCompat.RECEIVER_EXPORTED)
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
        val state = "enabled=${DiPlayPreferences.connectOnPhoneBluetooth(context)} " +
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
