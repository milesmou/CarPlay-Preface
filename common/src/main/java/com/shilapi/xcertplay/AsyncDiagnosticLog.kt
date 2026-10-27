package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.shilapi.xcertplay.logging.DiagnosticLevel
import com.shilapi.xcertplay.logging.LogPolicy

/** Queued entries retain only their exact file target and a redacted, bounded metadata string. */
internal object AsyncDiagnosticLog {
    private data class Entry(val target: SessionLogFile, val line: String, val level: DiagnosticLevel)
    private val writer = BoundedDiagnosticWriter<Entry> { it.target.append(it.line, it.level) }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis(),
        level: DiagnosticLevel = DiagnosticLevel.EVENT) {
        if (target == null || !LogPolicy.enabled(level)) return
        val safe = DiagnosticRedactor.redact(message) ?: return
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))
        runCatching { writer.enqueue(Entry(target, "$timestamp  $safe", level)) }
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
}
