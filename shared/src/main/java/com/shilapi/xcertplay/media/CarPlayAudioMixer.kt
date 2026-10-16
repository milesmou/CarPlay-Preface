package com.shilapi.xcertplay.media

import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Linkco playback policy: guidance ducks music; calls/Siri mute it; user stream volumes stay intact. */
internal class CarPlayAudioMixer(private val report: (String) -> Unit = {}) {
    private data class Output(val channel: AudioChannel, var gain: Float = 1f)
    private val outputs = LinkedHashMap<AudioTrack, Output>()
    private val speech = LinkedHashMap<AudioTrack, Long>()
    private val main = Handler(Looper.getMainLooper())
    private var focusGain = 1f
    private var nativeMusic = false
    private var closed = false
    private var speechGain = 1f
    private val drain = object : Runnable {
        override fun run() = synchronized(this@CarPlayAudioMixer) {
            if (closed) return@synchronized
            refresh()
        }
    }

    @Synchronized fun register(track: AudioTrack, channel: AudioChannel) {
        if (closed) return
        outputs.putIfAbsent(track, Output(channel))
        apply()
    }

    /** Called only after PCM was accepted and playback actually began, never just on SETUP. */
    @Synchronized fun played(track: AudioTrack, bufferedMillis: Long) {
        if (closed) return
        val role = outputs[track]?.channel ?: return
        if (role == AudioChannel.MEDIA) return
        speech[track] = SystemClock.elapsedRealtime() + bufferedMillis.coerceAtLeast(0L) + QUIET_TAIL_MS
        refresh()
    }

    @Synchronized fun unregister(track: AudioTrack) {
        outputs.remove(track)
        speech.remove(track)
        if (!closed) refresh()
    }

    @Synchronized fun focus(gain: Float) { if (!closed) { focusGain = gain; apply() } }
    @Synchronized fun nativeMusic(playing: Boolean) { if (!closed) { nativeMusic = playing; apply() } }

    @Synchronized fun close() {
        closed = true
        main.removeCallbacks(drain)
        outputs.clear()
        speech.clear()
    }

    private fun refresh() {
        val now = SystemClock.elapsedRealtime()
        speech.entries.removeAll { it.value <= now }
        val roles = speech.keys.mapNotNull { outputs[it]?.channel }
        val gain = when {
            roles.any { it == AudioChannel.PHONE || it == AudioChannel.ASSISTANT } -> 0f
            AudioChannel.NAVIGATION in roles -> GUIDANCE_GAIN
            else -> 1f
        }
        if (speechGain != gain) {
            speechGain = gain
            runCatching { report("Audio: Linkco playback mediaGain=$gain activeSpeech=${roles.size}") }
        }
        apply()
        main.removeCallbacks(drain)
        speech.values.minOrNull()?.let { deadline -> main.postDelayed(drain, (deadline - now).coerceAtLeast(1L)) }
    }

    private fun apply() {
        outputs.forEach { (track, output) ->
            if (output.channel != AudioChannel.MEDIA) return@forEach
            val gain = if (nativeMusic) 0f else minOf(focusGain, speechGain)
            if (output.gain == gain) return@forEach
            if (runCatching { track.setStereoVolume(gain, gain) == AudioTrack.SUCCESS }.getOrDefault(false)) output.gain = gain
        }
    }

    private companion object {
        const val GUIDANCE_GAIN = 0.3f // WPAudioTrack duckFactor default 3 * 0.1.
        const val QUIET_TAIL_MS = 900L // Last queued speech PCM must drain without parking music forever.
    }
}
