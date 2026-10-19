// carlito | Peer-scoped A2DP sink handoff; restores only an app-disconnected, still-bonded peer.
package com.shilapi.xcertplay.vehicle

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.AudioManager
import java.io.Closeable
import java.util.Locale

@SuppressLint("MissingPermission")
internal class BluetoothAudioHandoff(
    context: Context, address: String, private val report: (String) -> Unit,
    private val onPlaying: ((Boolean) -> Unit)? = null,
    private val nativeOutput: Boolean = false,
) : Closeable {
    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val audio = app.getSystemService(AudioManager::class.java)
    private val peer = address.uppercase(Locale.US)
    private val main = Handler(Looper.getMainLooper())
    private var proxy: BluetoothProfile? = null
    private var registered = false
    private var held = false
    @Volatile private var closed = false
    @Volatile private var suppressed = true
    private var lastDisconnectAt = 0L
    private var unavailable = false
    private var playing = false
    private var playbackQueryUnavailable = false
    private var restoreUnavailable = false
    private var restoreTask: Runnable? = null
    private var lastConnectAt: Long? = null
    private var platformConnector: PlatformBluetoothMusicConnector? = null
    private var platformConnectAttempted = false
    private val playbackPoll = object : Runnable {
        override fun run() = synchronized(this@BluetoothAudioHandoff) {
            if (closed) return@synchronized
            refreshPlaying()
            if (suppressed) disconnectPeer()
            main.postDelayed(this, 1_000L)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action !in setOf(ACTION_CONNECTION, ACTION_PLAYING) || closed) return
            @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (!runCatching { device.address.equals(peer, true) }.getOrDefault(false)) return
            if (intent.action == ACTION_PLAYING) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                // Accept only this session's connected phone and a known platform playback state.
                val connected = runCatching { proxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
                if (connected && state in setOf(STATE_PLAYING, STATE_NOT_PLAYING)) publishPlaying(state == STATE_PLAYING)
            } else when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (suppressed) disconnectPeer() else proxy?.let { restoreIfOwned(it) }
                    refreshPlaying()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    publishPlaying(false)
                    if (!suppressed) proxy?.let { restoreActive(it) }
                }
            }
        }
    }

    @Synchronized fun setSuppressed(value: Boolean) {
        if (closed) return
        suppressed = value
        if (value) cancelRestore()
        val connected = proxy ?: return
        if (value) disconnectPeer() else restoreActive(connected)
    }

    @Synchronized fun start() {
        if (closed || held) return
        if (Build.VERSION.SDK_INT >= 31 && app.checkCallingOrSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            report("Audio: Bluetooth music handoff unavailable: connection permission"); return
        }
        try {
            synchronized(leases) { leases.getOrPut(peer) { Lease() }.owners.add(this) }
            held = true
            val filter = IntentFilter(ACTION_CONNECTION).apply { if (onPlaying != null) addAction(ACTION_PLAYING) }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
            registered = true
            val requested = adapter?.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, connected: BluetoothProfile) = synchronized(this@BluetoothAudioHandoff) {
                    if (profile != A2DP_SINK) return@synchronized
                    if (closed) { restoreThenClose(connected); return@synchronized }
                    proxy = connected
                    playbackQueryUnavailable = false
                    restoreUnavailable = false
                    unavailable = false
                    refreshPlaying()
                    if (suppressed) disconnectPeer() else restoreActive(connected)
                }
                override fun onServiceDisconnected(profile: Int) = synchronized(this@BluetoothAudioHandoff) {
                    if (profile != A2DP_SINK) return@synchronized
                    cancelRestore(); proxy = null; publishPlaying(false)
                }
            }, A2DP_SINK) == true
            if (requested) main.post(playbackPoll)
            if (!requested) { report("Audio: Bluetooth music handoff unavailable: sink profile; current audio mode retained"); close() }
        } catch (error: Exception) {
            report("Audio: Bluetooth music handoff unavailable: ${error.javaClass.simpleName}"); close()
        }
    }

    // carlito | AOSP A2DP_SINK exposes isA2dpPlaying; unsupported queries do not prove playback.
    @Synchronized private fun refreshPlaying() {
        if (closed || onPlaying == null) return
        val profile = proxy ?: run { publishPlaying(false); return }
        try {
            val device = profile.connectedDevices.firstOrNull { it.address.equals(peer, true) }
                ?: run { publishPlaying(false); return }
            if (!playbackQueryUnavailable) {
                val value = profile.javaClass.getMethod("isA2dpPlaying", BluetoothDevice::class.java).invoke(profile, device) as? Boolean
                if (value != null) publishPlaying(value) else publishPlaying(false)
            }
        } catch (error: Exception) {
            // Future authenticated platform playback broadcasts may still establish playback.
            val firstFailure = !playbackQueryUnavailable
            playbackQueryUnavailable = true
            publishPlaying(false)
            if (firstFailure) report("Audio: Bluetooth playback state unavailable: ${error.javaClass.simpleName}; current audio mode retained")
        }
    }

    @Synchronized private fun publishPlaying(value: Boolean) {
        if (onPlaying == null || playing == value) return
        playing = value
        report("Audio: selected Bluetooth music playing=$value nativeOutput=$nativeOutput")
        runCatching { onPlaying?.invoke(value) }
    }

    @Synchronized private fun disconnectPeer() {
        if (closed || !suppressed || unavailable) return
        // A2DP only: never operate HFP/SCO or change the adapter during a call.
        if (audio?.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) return
        val profile = proxy ?: return
        try {
            val devices = profile.connectedDevices
            val device = devices.firstOrNull { it.address.equals(peer, true) && it.bondState == BluetoothDevice.BOND_BONDED } ?: return
            if (devices.any { !it.address.equals(peer, true) }) {
                unavailable = true
                report("Audio: Bluetooth music handoff skipped: another phone is connected; safe restoration is not assured")
                return
            }
            // Some head units expose disconnect but hide connect from this application.
            // Do not strand the phone without its music route when native output is selected later.
            val restore = profile.javaClass.getMethod("connect", BluetoothDevice::class.java)
            check(restore.returnType == Boolean::class.javaPrimitiveType || restore.returnType == Boolean::class.javaObjectType) {
                "Bluetooth music restore has an unsupported result type"
            }
            val now = SystemClock.elapsedRealtime()
            if (lastDisconnectAt != 0L && now - lastDisconnectAt < 500) return
            lastDisconnectAt = now
            synchronized(leases) {
                if (profile.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(profile, device) == true) {
                    leases[peer]?.disconnected = true
                    report("Audio: Bluetooth music handed to CarPlay")
                }
            }
        } catch (error: Exception) {
            unavailable = true
            report("Audio: Bluetooth music handoff fallback: ${error.javaClass.simpleName}; keeping existing Bluetooth route")
        }
    }

    private fun restoreIfOwned(profile: BluetoothProfile): Boolean = synchronized(leases) {
        val lease = leases[peer] ?: return true
        val selectedNative = nativeOutput && !closed && !suppressed
        if ((!lease.disconnected && !selectedNative) || lease.owners.any { it.suppressed && !it.closed }) return true
        try {
            val device = adapter?.bondedDevices?.firstOrNull { it.address.equals(peer, true) }
            if (adapter?.isEnabled != true || device == null) {
                lease.disconnected = false
            } else when (profile.getConnectionState(device)) {
                BluetoothProfile.STATE_DISCONNECTING -> return false
                BluetoothProfile.STATE_CONNECTING -> return false
                BluetoothProfile.STATE_CONNECTED -> {
                    if (lease.disconnected || lastConnectAt != null) report("Audio: original Bluetooth music connection confirmed")
                    lease.disconnected = false
                    lastConnectAt = null
                    platformConnector?.close(); platformConnector = null
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (restoreUnavailable) return true
                    if (audio?.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) return false
                    // A2DP connect may evict another phone on single-peer stacks. Never do that.
                    if (profile.connectedDevices.any { !it.address.equals(peer, true) }) {
                        report("Audio: original Bluetooth music reconnect deferred: another phone owns music; select this iPhone in vehicle Bluetooth settings")
                        return true
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (lastConnectAt?.let { now - it < 1_000 } == true) return false
                    lastConnectAt = now
                    val connect = try {
                        profile.javaClass.getMethod("connect", BluetoothDevice::class.java)
                    } catch (missing: NoSuchMethodException) {
                        // The platform-signed component can access Android 9's hidden sink API.
                        // It only requests native music restoration, never a CarPlay disconnect.
                        if (!selectedNative) throw missing
                        requestPlatformConnect()
                        return false
                    }
                    val accepted = connect.invoke(profile, device) == true
                    report("Audio: original Bluetooth music reconnect accepted=$accepted")
                    // Accepted is asynchronous; retain the restore obligation until CONNECTED.
                    return false
                }
            }
        } catch (error: Exception) {
            restoreUnavailable = true
            report("Audio: original Bluetooth music reconnect unavailable: ${error.javaClass.simpleName}; connect media audio for this iPhone in vehicle Bluetooth settings")
        }
        if (lease.owners.isEmpty() && !lease.disconnected) leases.remove(peer)
        true
    }

    private fun requestPlatformConnect() {
        if (closed || suppressed || !nativeOutput || platformConnectAttempted) return
        platformConnectAttempted = true
        val next = PlatformBluetoothMusicConnector(app, peer) { accepted, reason ->
            synchronized(this@BluetoothAudioHandoff) {
                platformConnector = null
                if (closed || suppressed || !nativeOutput) return@synchronized
                report("Audio: platform Bluetooth music reconnect accepted=$accepted reason=$reason")
                if (!accepted) {
                    restoreUnavailable = true
                    report("Audio: native Bluetooth music is not confirmed; connect this iPhone's media audio in vehicle Bluetooth settings")
                }
            }
        }
        platformConnector = next
        next.start()
    }

    // carlito | A disconnect may still be completing when focus is yielded.
    private fun restoreActive(profile: BluetoothProfile, deadline: Long = SystemClock.elapsedRealtime() + 5_000L) {
        if (closed || suppressed || proxy !== profile || restoreTask != null) return
        platformConnectAttempted = false
        val task = object : Runnable {
            override fun run() = synchronized(this@BluetoothAudioHandoff) {
                if (closed || suppressed || proxy !== profile) { cancelRestore(); return@synchronized }
                val done = restoreIfOwned(profile)
                if (!done && SystemClock.elapsedRealtime() < deadline) main.postDelayed(this, 250L)
                else {
                    restoreTask = null
                    platformConnector?.close(); platformConnector = null
                    if (!done) report("Audio: original Bluetooth music reconnect timed out; media connection not confirmed; check vehicle Bluetooth settings")
                }
            }
        }
        restoreTask = task
        task.run()
    }

    private fun cancelRestore() {
        restoreTask?.let(main::removeCallbacks)
        restoreTask = null
        platformConnector?.close(); platformConnector = null
    }

    private fun restoreThenClose(profile: BluetoothProfile, deadline: Long = SystemClock.elapsedRealtime() + 5_000L) {
        if (!restoreIfOwned(profile) && SystemClock.elapsedRealtime() < deadline) {
            main.postDelayed({ restoreThenClose(profile, deadline) }, 250L)
        } else runCatching { adapter?.closeProfileProxy(A2DP_SINK, profile) }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(playbackPoll)
        cancelRestore()
        publishPlaying(false)
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        registered = false
        if (held) synchronized(leases) {
            leases[peer]?.let { lease ->
                lease.owners.remove(this)
                if (lease.owners.isEmpty() && !lease.disconnected) leases.remove(peer)
            }
        }
        held = false
        proxy?.let(::restoreThenClose); proxy = null
    }

    private class Lease { val owners = mutableSetOf<BluetoothAudioHandoff>(); var disconnected = false }
    private companion object {
        val leases = HashMap<String, Lease>()
        const val A2DP_SINK = 11
        const val STATE_PLAYING = 10
        const val STATE_NOT_PLAYING = 11
        const val ACTION_PLAYING = "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED"
        const val ACTION_CONNECTION = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    }
}
