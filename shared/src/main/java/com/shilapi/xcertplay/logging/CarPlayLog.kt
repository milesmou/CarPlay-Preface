package com.shilapi.xcertplay.logging

import android.util.Log as AndroidLog
import com.shilapi.xcertplay.shared.BuildConfig

enum class DiagnosticLevel { DEBUG, EVENT, WARNING, ERROR }

/** Selected by the shared library variant, independently of saved UI preferences. */
object LogPolicy {
    val detailedEnabled: Boolean get() = BuildConfig.DEBUG

    fun enabled(level: DiagnosticLevel): Boolean = detailedEnabled || level != DiagnosticLevel.DEBUG
}

/** Ordinary verbose/debug/info calls are detailed logs; retained events must be explicit. */
object CarPlayLog {
    fun v(tag: String?, message: String?, error: Throwable? = null): Int =
        detail(AndroidLog.VERBOSE, tag, message, error)

    fun d(tag: String?, message: String?, error: Throwable? = null): Int =
        detail(AndroidLog.DEBUG, tag, message, error)

    fun i(tag: String?, message: String?, error: Throwable? = null): Int =
        detail(AndroidLog.INFO, tag, message, error)

    fun event(tag: String?, message: String?): Int = AndroidLog.i(tag, message.orEmpty())

    fun w(tag: String?, message: String?, error: Throwable? = null): Int =
        if (error == null) AndroidLog.w(tag, message.orEmpty()) else AndroidLog.w(tag, message, error)

    fun e(tag: String?, message: String?, error: Throwable? = null): Int =
        if (error == null) AndroidLog.e(tag, message.orEmpty()) else AndroidLog.e(tag, message, error)

    private fun detail(priority: Int, tag: String?, message: String?, error: Throwable?): Int {
        if (!LogPolicy.detailedEnabled) return 0
        return when (priority) {
            AndroidLog.VERBOSE -> if (error == null) AndroidLog.v(tag, message.orEmpty()) else AndroidLog.v(tag, message, error)
            AndroidLog.DEBUG -> if (error == null) AndroidLog.d(tag, message.orEmpty()) else AndroidLog.d(tag, message, error)
            else -> if (error == null) AndroidLog.i(tag, message.orEmpty()) else AndroidLog.i(tag, message, error)
        }
    }
}
