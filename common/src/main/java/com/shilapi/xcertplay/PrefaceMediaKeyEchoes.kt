package com.shilapi.xcertplay

import android.os.SystemClock
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/** Match parallel deliveries without merging repeated presses from the same source. */
internal class PrefaceMediaKeyEchoes(private val now: () -> Long = SystemClock::uptimeMillis) {
    enum class Source { INPUT, ANDROID_KEY, MEDIA_CENTER, SESSION_CONTROL }
    private data class Sent(val index: Int, val sources: MutableSet<Source>, val at: Long)
    private val pending = ArrayDeque<Sent>()

    fun dispatch(index: Int, intercepted: Boolean, send: () -> Boolean): Boolean {
        return dispatch(index, if (intercepted) Source.INPUT else Source.ANDROID_KEY, send)
    }

    @Synchronized
    fun dispatch(index: Int, source: Source, send: () -> Boolean): Boolean {
        val time = now()
        pending.removeAll { time - it.at !in 0..ECHO_WINDOW_MILLIS }
        val match = pending.firstOrNull { source !in it.sources && samePress(it.index, index) }
        if (match != null) {
            match.sources.add(source)
            return true
        }
        if (!send()) return false
        pending.addLast(Sent(index, mutableSetOf(source), time))
        if (pending.size > 32) pending.removeFirst()
        return true
    }

    @Synchronized fun clear() = pending.clear()

    // The OEM translates a physical toggle into explicit onPlay/onPause. Sending both would
    // immediately reverse playback; explicit PLAY and PAUSE are still distinct commands.
    private fun samePress(first: Int, second: Int): Boolean = first == second ||
        (first == CarPlayMediaButton.PLAY_PAUSE && second in CarPlayMediaButton.PLAY..CarPlayMediaButton.PAUSE) ||
        (second == CarPlayMediaButton.PLAY_PAUSE && first in CarPlayMediaButton.PLAY..CarPlayMediaButton.PAUSE)

    private companion object { const val ECHO_WINDOW_MILLIS = 750L }
}
