package com.shilapi.xcertplay.vehicle

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Serializes all controllers' instrument writes and invalidates old sessions before queued work. */
internal class PrefaceInstrumentNavigation(
    private val backend: (() -> Boolean) -> PrefaceInstrumentBackend,
    private val diagnostic: (String) -> Unit,
) : AutoCloseable {
    @Volatile private var token: Any? = null
    @Volatile private var closed = false
    private var state: PrefaceInstrumentGuidanceSession? = null
    private var timer: ScheduledFuture<*>? = null

    fun start(session: Any) {
        if (closed || token === session) return
        token = session
        worker.execute {
            if (closed || token !== session) return@execute
            owner?.release()
            owner = this
            state = PrefaceInstrumentGuidanceSession(backend { !closed && token === session }, diagnostic = diagnostic)
            timer = worker.scheduleWithFixedDelay({
                if (!closed && token === session && owner === this) state?.tick()
            }, 0, 500, TimeUnit.MILLISECONDS)
            diagnostic("instrument navigation enabled for current session; experimental=true")
        }
    }

    fun accept(frame: Iap2Frame, session: Any?) {
        if (closed || session == null || token !== session || frame.messageId !in 0x5201..0x5202) return
        worker.execute {
            if (!closed && token === session && owner === this) state?.accept(frame)
        }
    }

    fun end(session: Any) {
        if (token !== session) return
        token = null
        worker.execute { if (token == null && owner === this) { release(); owner = null } }
    }

    private fun release() {
        timer?.cancel(false)
        timer = null
        try { state?.close() }
        catch (error: Exception) { diagnostic("instrument navigation release failed type=${error.javaClass.simpleName}") }
        catch (error: LinkageError) { diagnostic("instrument navigation release failed type=${error.javaClass.simpleName}") }
        state = null
    }

    override fun close() {
        closed = true
        token = null
        worker.execute { if (owner === this) { release(); owner = null } }
    }

    companion object {
        private val worker = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "preface-instrument-navigation").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private var owner: PrefaceInstrumentNavigation? = null // worker only

        fun create(context: Context, diagnostic: (String) -> Unit): PrefaceInstrumentNavigation? {
            if (!PrefaceInstrumentSettings.enabled(context)) return null
            val app = context.applicationContext
            return PrefaceInstrumentNavigation({ current ->
                PrefaceInstrumentCarBackend(app, diagnostic = diagnostic, currentSession = current)
            }, diagnostic)
        }
    }
}
