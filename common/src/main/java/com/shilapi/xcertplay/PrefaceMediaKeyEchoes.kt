package com.shilapi.xcertplay

import android.os.SystemClock

/** Pair the OEM's physical callback with its later synthetic media broadcast, in either order. */
internal class PrefaceMediaKeyEchoes(private val now: () -> Long = SystemClock::uptimeMillis) {
    private data class Sent(val index: Int, val intercepted: Boolean, val at: Long)
    private val pending = ArrayDeque<Sent>()

    fun dispatch(index: Int, intercepted: Boolean, send: () -> Boolean): Boolean {
        val time = now()
        pending.removeAll { time - it.at !in 0..ECHO_WINDOW_MILLIS }
        val match = pending.firstOrNull { it.index == index && it.intercepted != intercepted }
        if (match != null) {
            pending.remove(match)
            return true
        }
        if (!send()) return false
        pending.addLast(Sent(index, intercepted, time))
        if (pending.size > 32) pending.removeFirst()
        return true
    }

    fun clear() = pending.clear()

    private companion object { const val ECHO_WINDOW_MILLIS = 750L }
}
