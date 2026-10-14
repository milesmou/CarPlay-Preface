package com.shilapi.xcertplay.media

import android.media.AudioTrack

/** Tracks actual routes even between announcements; user volume belongs to the head unit. */
data class CarPlayOutputRoute(val legacyStreamType: Int?)

object CarPlayAudioOutputs {
    private data class Output(val navigation: Boolean, val legacyStreamType: Int?)
    private val outputs = LinkedHashMap<Any, Output>()

    internal fun register(track: AudioTrack, channel: AudioChannel, legacyStreamType: Int?) {
        if (channel != AudioChannel.MEDIA && channel != AudioChannel.NAVIGATION) return
        track.setStereoVolume(1f, 1f)
        register(track, channel == AudioChannel.NAVIGATION, legacyStreamType)
    }

    @Synchronized
    internal fun register(token: Any, navigation: Boolean, legacyStreamType: Int?) {
        outputs[token] = Output(navigation, legacyStreamType)
    }

    @Synchronized
    internal fun unregister(token: Any) { outputs.remove(token) }

    /** Null means no output; a null stream means automatic or fallback routing. */
    @Synchronized
    fun route(navigation: Boolean): CarPlayOutputRoute? {
        val matching = outputs.values.filter { it.navigation == navigation }
        if (matching.isEmpty()) return null
        return CarPlayOutputRoute(matching.map { it.legacyStreamType }.distinct().singleOrNull())
    }
}
