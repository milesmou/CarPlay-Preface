package com.shilapi.xcertplay

import android.content.Intent

/** Main-thread, process-local cancellation barrier for automatic and queued connection launches. */
internal object ConnectionAutoStart {
    private const val EXTRA_GENERATION = "com.shihab.diplay.CONNECTION_LAUNCH_GENERATION"
    private var requests = ConnectionRequestGate()
    val generation get() = requests.generation
    val paused get() = requests.paused

    fun cancel() = requests.cancel()
    fun resume() = requests.resume()
    fun accepts(request: Long): Boolean = requests.accepts(request)
    fun accepts(intent: Intent): Boolean = accepts(intent.getLongExtra(EXTRA_GENERATION, generation))
    fun stamp(intent: Intent): Intent = intent.putExtra(EXTRA_GENERATION, generation)

    internal fun resetForTest() { requests = ConnectionRequestGate() }
}
