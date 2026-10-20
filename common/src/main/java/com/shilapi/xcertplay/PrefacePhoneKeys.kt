package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import com.shilapi.xcertplay.hud.CarPlayCallCard
import com.shilapi.xcertplay.hud.CarPlayCallControl
import com.shilapi.xcertplay.orchestration.CarPlayPhoneCommand

internal enum class PhoneKeyRoute { CARPLAY, VEHICLE, UNKNOWN }

/** Query only. HFP and uncertain states remain native-owned; no Bluetooth connection writes. */
internal fun prefacePhoneKeyRoute(context: Context): PhoneKeyRoute = try {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) PhoneKeyRoute.CARPLAY
    else {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> PhoneKeyRoute.UNKNOWN
            !adapter.isEnabled -> PhoneKeyRoute.CARPLAY
            else -> when (adapter.getProfileConnectionState(16)) {
                BluetoothProfile.STATE_DISCONNECTED -> PhoneKeyRoute.CARPLAY
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING -> PhoneKeyRoute.VEHICLE
                else -> PhoneKeyRoute.UNKNOWN
            }
        }
    }
} catch (_: Exception) { PhoneKeyRoute.UNKNOWN }
catch (_: LinkageError) { PhoneKeyRoute.UNKNOWN }

/** Session-scoped phone input, deliberately independent of music focus and media-key eligibility. */
internal class PrefacePhoneKeys(
    private val current: () -> CarPlayCallControl?,
    private val eligible: () -> Boolean,
    private val route: () -> PhoneKeyRoute,
    private val send: (CarPlayPhoneCommand, () -> Boolean) -> Boolean,
    private val diagnostic: (String) -> Unit = {},
    private val clock: () -> Long = SystemClock::uptimeMillis,
) : AutoCloseable {
    private val held = mutableMapOf<Triple<Int, Int, Int>, CarPlayCallControl>()
    private val longPresses = mutableSetOf<Triple<Int, Int, Int>>()
    @Volatile private var closed = false
    private var lastToken: Any? = null
    private var lastAt = 0L

    fun window(event: KeyEvent): Boolean? {
        if (event.keyCode !in KEYS) return null
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val key = Triple(event.deviceId, event.keyCode, event.scanCode)
        if (event.action == KeyEvent.ACTION_UP) {
            val call = held.remove(key) ?: return false
            val longPress = longPresses.remove(key)
            if (!longPress && !event.isCanceled &&
                event.eventTime - event.downTime in 0 until ViewConfiguration.getLongPressTimeout().toLong())
                dispatch(event.keyCode, call, "window")
            // A consumed DOWN keeps its UP even if the call or ownership changed meanwhile.
            return true
        }
        if (held.containsKey(key)) {
            if (event.repeatCount != 0 || event.isLongPress) longPresses.add(key)
            return true
        }
        if (event.repeatCount != 0 || event.isLongPress || event.isCanceled) return false
        val call = candidate("window") ?: return false
        if (command(event.keyCode, call) == null) return false
        held[key] = call
        return true
    }

    fun shortPress(): Boolean {
        val call = candidate("preface-phone") ?: return false
        return dispatch(PHYSICAL_KEY, call, "preface-phone")
    }

    fun longPress() { longPresses.addAll(held.keys) }

    private fun candidate(source: String): CarPlayCallControl? {
        if (closed || !eligible()) return null
        val call = current() ?: return null
        val owner = route()
        if (owner != PhoneKeyRoute.CARPLAY) {
            diagnostic("Phone key source=$source retainedByVehicle=true reason=$owner")
            return null
        }
        return call
    }

    private fun command(code: Int, call: CarPlayCallControl): CarPlayPhoneCommand? = when (code) {
        KeyEvent.KEYCODE_ENDCALL -> CarPlayPhoneCommand.END
        KeyEvent.KEYCODE_CALL -> CarPlayPhoneCommand.ANSWER.takeIf { it.accepts(call) }
        PHYSICAL_KEY -> if (call.phase == CarPlayCallCard.Phase.RINGING) CarPlayPhoneCommand.ANSWER else CarPlayPhoneCommand.END
        else -> null
    }

    private fun dispatch(code: Int, call: CarPlayCallControl, source: String): Boolean {
        val valid = { !closed && eligible() && current() == call && route() == PhoneKeyRoute.CARPLAY }
        if (!valid()) return false
        val now = clock()
        // A late broadcast after ANSWER must not turn into END when the same call becomes ACTIVE.
        if (lastToken === call.token && now - lastAt in 0 until ECHO_MILLIS) {
            diagnostic("Phone key source=$source suppressed=paired-phone-echo")
            return true
        }
        val command = command(code, call) ?: return false
        val accepted = send(command, valid)
        if (accepted) { lastToken = call.token; lastAt = now }
        diagnostic("Phone key source=$source command=$command queued=$accepted handsetResult=unconfirmed")
        return accepted
    }

    override fun close() { closed = true; held.clear(); longPresses.clear(); lastToken = null }

    companion object {
        const val PHYSICAL_KEY = 299
        private const val ECHO_MILLIS = 800L
        private val KEYS = setOf(PHYSICAL_KEY, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL)
    }
}

/** Firmware's ordinary phone broadcast cannot cancel the OEM phone receiver. */
internal class PrefacePhoneButtonReceiver(private val shortPress: () -> Boolean,
    private val longPress: () -> Unit = {}) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION || intent.getIntExtra(KEY_TYPE, -1) != 200005) return
        when (intent.getIntExtra(KEY_ACTION, -1)) {
            0 -> shortPress()
            1 -> longPress()
        }
    }

    companion object {
        const val ACTION = "ecarx.intent.action.ECARX_KEY_RCALL_EVENT"
        const val KEY_TYPE = "ecarx.extra.ECARX_KEY_EVENT_TYPE"
        const val KEY_ACTION = "ecarx.extra.ECARX_KEY_ACTION_TYPE"
    }
}
