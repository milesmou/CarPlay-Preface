package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioTrack

/** Per-output gain keeps guidance adjustable even when firmware puts it on the media bus. */
object CarPlayAudioVolume {
    private const val PREFS = "carplay_audio_volume"
    private const val DEFAULT_PERCENT = 50
    private data class Output(val navigation: Boolean, val apply: (Float) -> Boolean, var focusGain: Float = 1f)
    private val outputs = LinkedHashMap<Any, Output>()
    private var mediaPercent = DEFAULT_PERCENT
    private var navigationPercent = DEFAULT_PERCENT

    fun percent(context: Context, navigation: Boolean): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(if (navigation) "navigation" else "media", DEFAULT_PERCENT).coerceIn(0, 100)

    @Synchronized
    fun save(context: Context, navigation: Boolean, percent: Int) {
        val value = percent.coerceIn(0, 100)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(if (navigation) "navigation" else "media", value).apply()
        if (navigation) navigationPercent = value else mediaPercent = value
        outputs.values.filter { it.navigation == navigation }.forEach(::apply)
    }

    @Synchronized
    fun adjustNavigation(context: Context, delta: Int): Boolean {
        if (delta != -1 && delta != 1 || outputs.values.none { it.navigation }) return false
        val previous = navigationPercent
        navigationPercent = (percent(context, true) + delta * 5).coerceIn(0, 100)
        if (!outputs.values.filter { it.navigation }.map(::apply).all { it }) {
            navigationPercent = previous
            outputs.values.filter { it.navigation }.forEach(::apply)
            return false
        }
        save(context, true, navigationPercent)
        return true
    }

    internal fun register(context: Context?, track: AudioTrack, channel: AudioChannel) {
        if (channel != AudioChannel.MEDIA && channel != AudioChannel.NAVIGATION) return
        register(context, track, channel == AudioChannel.NAVIGATION) { gain ->
            track.setStereoVolume(gain, gain) == AudioTrack.SUCCESS
        }
    }

    @Synchronized
    internal fun register(context: Context?, token: Any, navigation: Boolean, applyGain: (Float) -> Boolean) {
        mediaPercent = context?.let { percent(it, false) } ?: DEFAULT_PERCENT
        navigationPercent = context?.let { percent(it, true) } ?: DEFAULT_PERCENT
        val output = Output(navigation, applyGain)
        outputs[token] = output
        apply(output)
    }

    @Synchronized
    internal fun setFocusGain(track: Any, gain: Float): Boolean {
        val output = outputs[track] ?: return false
        output.focusGain = gain.coerceIn(0f, 1f)
        return apply(output)
    }

    @Synchronized
    internal fun unregister(track: Any) { outputs.remove(track) }

    private fun apply(output: Output): Boolean = runCatching {
        output.apply((if (output.navigation) navigationPercent else mediaPercent) / 100f * output.focusGain)
    }.getOrDefault(false)
}
