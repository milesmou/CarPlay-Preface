package com.shilapi.xcertplay

/** OEM callbacks have no repeat count. Own one paired press and synthesize 250 ms repeats. */
internal class PrefaceVolumePress(
    private val schedule: (Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val adjust: (Int) -> Boolean,
) {
    private var held: Int? = null
    private var generation = 0L
    private var pending: Runnable? = null

    fun onKey(down: Boolean, key: Int) {
        if (key != 301 && key != 302) return
        if (!down) {
            if (held == key) close()
            return
        }
        if (held == key) return
        close()
        held = key
        val owner = generation
        val delta = if (key == 301) 1 else -1
        fun step() {
            if (generation != owner || held != key) return
            if (!adjust(delta)) { close(); return }
            val next = Runnable { step() }
            pending = next
            schedule(next)
        }
        step()
    }

    fun close() {
        generation++
        held = null
        pending?.let(cancel)
        pending = null
    }
}
