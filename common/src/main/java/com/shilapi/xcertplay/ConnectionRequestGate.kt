package com.shilapi.xcertplay

/** Main-thread request identity. Resuming permits new requests without reviving cancelled ones. */
internal class ConnectionRequestGate {
    var generation = 0L
        private set
    var paused = false
        private set

    fun cancel() { generation++; paused = true }
    fun resume() { paused = false }
    fun accepts(request: Long): Boolean = !paused && request == generation
}
