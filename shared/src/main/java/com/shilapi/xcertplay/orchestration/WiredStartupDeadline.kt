package com.shilapi.xcertplay.orchestration

/** Bounds initial AirPlay admission after StartSession; an established session has no deadline. */
internal class WiredStartupDeadline(private val clockNanos: () -> Long = System::nanoTime) {
    private var started: Long? = null
    private var established = false
    private var expired = false

    @Synchronized fun startSessionSent() {
        if (started == null) started = clockNanos()
    }

    @Synchronized fun sessionEstablished(): Boolean {
        if (timedOut()) return false
        established = true
        return true
    }

    @Synchronized fun timedOut(): Boolean {
        val start = started ?: return false
        if (!established && clockNanos() - start >= TIMEOUT_MILLIS * 1_000_000L) expired = true
        return expired
    }

    companion object { const val TIMEOUT_MILLIS = 30_000L }
}
