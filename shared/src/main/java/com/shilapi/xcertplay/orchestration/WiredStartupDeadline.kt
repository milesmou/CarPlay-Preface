package com.shilapi.xcertplay.orchestration

/** Bounds initial AirPlay admission after StartSession; an established session has no deadline. */
internal class WiredStartupDeadline(private val clockNanos: () -> Long = System::nanoTime) {
    private var started: Long? = null
    private var established = false
    private var expired = false
    private var held = false
    private var repeated = false

    @Synchronized fun startSessionSent() {
        if (started == null) started = clockNanos()
    }

    /** Only our one successful late-link retry gets a fresh admission window. */
    @Synchronized fun startSessionSentAgain() {
        if (started == null || established || expired || repeated) return
        repeated = true
        started = clockNanos()
    }

    @Synchronized fun startAgainDecision(linkOpenedAtNanos: Long?, connectionAttemptSinceLinkOpened: Boolean):
        com.shilapi.xcertplay.transport.Iap2WiredControlClient.StartAgain {
        val start = started
        if (start == null || expired) return com.shilapi.xcertplay.transport.Iap2WiredControlClient.StartAgain.DONE
        return WiredStartAgain.decide(clockNanos(), start, linkOpenedAtNanos, established,
            connectionAttemptSinceLinkOpened)
    }

    @Synchronized fun sessionEstablished(secondRequestCanStillGoOut: Boolean = false): Boolean {
        if (timedOut(secondRequestCanStillGoOut)) return false
        established = true
        return true
    }

    @Synchronized fun timedOut(secondRequestCanStillGoOut: Boolean = false): Boolean {
        val start = started ?: return false
        if (!established && clockNanos() - start >= TIMEOUT_MILLIS * 1_000_000L) {
            if (!repeated && secondRequestCanStillGoOut) held = true
            val limit = if (held && !repeated) HELD_TIMEOUT_MILLIS else TIMEOUT_MILLIS
            if (clockNanos() - start >= limit * 1_000_000L) expired = true
        }
        return expired
    }

    companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val HELD_TIMEOUT_MILLIS = 45_000L
    }
}
