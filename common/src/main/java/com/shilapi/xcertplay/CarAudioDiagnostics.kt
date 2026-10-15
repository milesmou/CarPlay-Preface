package com.shilapi.xcertplay

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** Observes the head-unit receiver roles; never connects, disconnects or sends AVRCP commands. */
internal class CarAudioDiagnostics(private val context: Context, private val emit: (String) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime) : AutoCloseable {
    private var registered = false
    private var closed = false
    private var lastSample = Long.MIN_VALUE
    private var samples = 0
    private var events = 0
    private val lastStates = mutableMapOf<String, Int>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching { intent?.let(::receive) }.onFailure {
                emit("Car audio diagnostic event=unavailable failureClass=${it.javaClass.simpleName}")
            }
        }
    }

    fun start() {
        try {
            val filter = IntentFilter().apply { ACTIONS.keys.forEach(::addAction) }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
            emit("Car audio diagnostic observer registered=true role=head_unit eventDeliveryNotGuaranteed=true")
        } catch (e: Exception) { emit("Car audio diagnostic observer unavailable failureClass=${e.javaClass.simpleName}") }
        sample("attach")
    }

    internal fun receive(intent: Intent) {
        if (closed) return
        val label = ACTIONS[intent.action] ?: return
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        if (lastStates[label] == state) return
        lastStates[label] = state
        if (events++ >= 64) return
        @Suppress("DEPRECATION")
        val match = runCatching {
            val address = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.address
            val selected = DiPlayPreferences.phoneAddress(context)
            if (address == null || selected == null) "unknown" else address.equals(selected, true).toString()
        }.getOrDefault("unavailable")
        emit("Car audio diagnostic event=$label state=$state previous=${intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)} " +
            "selectedPhoneMatch=$match observationOnly=true")
        sample("bluetooth_event")
    }

    fun sample(reason: String) {
        if (closed || samples >= 48) return
        val now = clock()
        if (lastSample != Long.MIN_VALUE && now - lastSample < 500) return
        lastSample = now; samples++
        fun value(block: () -> Any?): String = try { block()?.toString() ?: "unavailable" }
            catch (e: Exception) { "unavailable_${e.javaClass.simpleName}" }
        val audio = runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull()
        emit("Car audio diagnostic sample=$reason mode=${value { audio?.mode }} " +
            "musicActive=${value { audio?.isMusicActive }} mediaVolume=${value { audio?.getStreamVolume(3) }} " +
            "navigationVolume=${value { audio?.getStreamVolume(12) }} " +
            "scoOn=${value { @Suppress("DEPRECATION") audio?.isBluetoothScoOn }} oemSelectedSource=unavailable")
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        emit("Car audio diagnostic profileAggregate a2dpSink=${value { adapter?.getProfileConnectionState(11) }} " +
            "avrcpController=${value { adapter?.getProfileConnectionState(12) }} " +
            "headsetClient=${value { adapter?.getProfileConnectionState(16) }} " +
            "playingState=event_only profileAvailability=unverified absenceOfEventsNotProof=true")
    }

    override fun close() {
        closed = true
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    companion object {
        // AOSP Android 9 receiver-side broadcasts; availability/permissions remain runtime checks.
        internal val ACTIONS = mapOf(
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED" to "a2dp_sink_connection",
            "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED" to "a2dp_sink_playing",
            "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED" to "avrcp_controller_connection",
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED" to "headset_client_connection",
            "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED" to "headset_client_audio")
    }
}
