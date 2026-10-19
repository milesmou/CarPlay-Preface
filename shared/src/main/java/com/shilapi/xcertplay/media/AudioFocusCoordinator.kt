package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat

/** AudioTrack's attributes getter is only available from Android 10. */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    private val context: Context?,
    private val enabled: Boolean,
    private val muteMediaOnTransientLoss: Boolean = true,
    private val report: (String) -> Unit = {},
    private val onOwnershipChanged: (Boolean) -> Unit = {},
    private val mediaFocusEnabled: Boolean = true,
) {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequestCompat? = null
    private var requestedChannel: AudioChannel? = null
    private var mediaVolume = FULL_VOLUME
    private var closed = false
    private var focusHeld = false
    private var nativeBluetoothPlaying = false
    private var ownership = false
    private var focusGeneration = 0L
    private val mixer = CarPlayAudioMixer(report)
    private var mediaPlaying: Boolean? = null
    private val main = Handler(Looper.getMainLooper())
    private var callBlocked = false
    private val callCheck = object : Runnable {
        override fun run() = synchronized(this@AudioFocusCoordinator) { if (!closed) refreshRequest() }
    }
    private var currentListener = listenerFor(focusGeneration)
    internal val listener: AudioManager.OnAudioFocusChangeListener get() = currentListener

    fun registerOutput(track: AudioTrack, channel: AudioChannel, legacyStreamType: Int?) {
        CarPlayAudioOutputs.register(track, channel, legacyStreamType)
        mixer.register(track, channel)
    }

    fun onPlayback(track: AudioTrack, bufferedMillis: Long) = mixer.played(track, bufferedMillis)

    private fun listenerFor(generation: Long) = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            // Android may have queued callbacks before a request was abandoned or replaced.
            if (generation != focusGeneration || request == null || active.isEmpty()) {
                // Dropping this silently hides the one window that matters: between a telephony
                // track closing and media reopening, a head unit that keeps focus looks like
                // nothing at all. Say the callback arrived and why it was not acted on.
                runCatching {
                    report("Audio: focus change=$change dropped" +
                        " stale=${generation != focusGeneration} noRequest=${request == null}" +
                        " activeTracks=${active.size}")
                }
                return@synchronized
            }
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setMediaVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS -> {
                    if (muteMediaOnTransientLoss) {
                        // A focus change alone does not identify a phone call.
                        Log.i(TAG, "Audio: muting media during focus loss")
                        setMediaVolume(0f)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> setMediaVolume(FULL_VOLUME)
            }
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> focusHeld = true
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusHeld = false
            }
            publishOwnership(focusHeld)
            if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && manager?.mode in CALL_MODES) refreshRequest()
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed || !enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        mixer.register(track, channel)
        refreshRequest()
        mixer.focus(mediaVolume)
    }

    @Synchronized
    fun release(track: AudioTrack) {
        mixer.unregister(track)
        if (active.remove(track) != null) refreshRequest()
    }

    fun onExternalFocusChange(change: Int) {
        val current = synchronized(this) { currentListener }
        current.onAudioFocusChange(change)
    }

    /** A connected profile alone never suppresses CarPlay music. */
    @Synchronized fun setNativeBluetoothPlaying(playing: Boolean) {
        if (closed || nativeBluetoothPlaying == playing) return
        nativeBluetoothPlaying = playing
        mixer.nativeMusic(playing)
        refreshRequest()
        runCatching { report("Audio: native Bluetooth music playing=$playing") }
    }

    @Synchronized fun onMediaPlaying(playing: Boolean) {
        if (closed) return
        val previous = mediaPlaying
        mediaPlaying = playing
        // A fresh play/resume can reclaim focus; repeated position updates cannot steal a source.
        if (enabled && playing && previous != true && !focusHeld && requestedChannel == AudioChannel.MEDIA) {
            if (manager?.mode in CALL_MODES) { refreshRequest(); return }
            val result = manager?.let { audio -> request?.request(audio) }
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (focusHeld) setMediaVolume(FULL_VOLUME)
            publishOwnership(focusHeld)
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(callCheck)
        mixer.close()
        active.clear()
        refreshRequest()
    }

    private fun refreshRequest() {
        main.removeCallbacks(callCheck)
        val primary = active.values.filter { it.channel != AudioChannel.MEDIA || mediaFocusEnabled && !nativeBluetoothPlaying }
            .maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            focusGeneration += 1
            val abandoned = request
            request = null
            requestedChannel = null
            focusHeld = false
            publishOwnership(false)
            setMediaVolume(FULL_VOLUME)
            manager?.let { abandoned?.abandon(it) }
            return
        }
        if (primary.channel == AudioChannel.MEDIA && manager?.mode in CALL_MODES) {
            focusGeneration += 1
            manager?.let { request?.abandon(it) }
            request = null
            requestedChannel = null
            focusHeld = false
            publishOwnership(false)
            setMediaVolume(0f)
            if (!callBlocked) runCatching { report("Audio: media focus deferred while call owns audio") }
            callBlocked = true
            main.postDelayed(callCheck, 250L)
            return
        }
        if (callBlocked) {
            callBlocked = false
            runCatching { report("Audio: call audio ended; rechecking current playback focus") }
        }
        if (request != null && requestedChannel == primary.channel) return
        focusGeneration += 1
        manager?.let { request?.abandon(it) }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.NAVIGATION -> return
        }
        currentListener = listenerFor(focusGeneration)
        val next = AudioFocusRequestCompat(gain, primary.attributes, currentListener, Handler(Looper.getMainLooper()))
        request = next
        requestedChannel = primary.channel
        val result = manager?.let(next::request)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        publishOwnership(focusHeld)
        // A transient voice grant does not restore music muted by an earlier owner.
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED && primary.channel == AudioChannel.MEDIA) setMediaVolume(FULL_VOLUME)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setMediaVolume(volume: Float) {
        mediaVolume = volume
        mixer.focus(mediaVolume)
    }

    private fun publishOwnership(value: Boolean) {
        if (ownership == value) return
        ownership = value
        runCatching { onOwnershipChanged(value) }
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 1
        AudioChannel.PHONE -> 3
        AudioChannel.ASSISTANT -> 2
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.3f
        val CALL_MODES = setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)
    }
}
