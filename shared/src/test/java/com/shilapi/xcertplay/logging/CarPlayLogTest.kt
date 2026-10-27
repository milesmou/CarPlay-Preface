package com.shilapi.xcertplay.logging

import android.util.Log
import com.shilapi.xcertplay.shared.BuildConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayLogTest {
    private val tag = "CarPlayLogTest"

    @Before fun clearLogs() { ShadowLog.clear() }

    @Test fun detailedAndroidLogsFollowTheActualBuildVariant() {
        CarPlayLog.v(tag, "packet bytes")
        CarPlayLog.d(tag, "decoder counters")
        CarPlayLog.i(tag, "navigation update")
        val messages = ShadowLog.getLogsForTag(tag).map { it.msg }
        assertEquals(if (BuildConfig.DEBUG) listOf("packet bytes", "decoder counters", "navigation update")
            else emptyList<String>(), messages)
    }

    @Test fun lifecycleWarningsAndErrorsKeepTheirPriorityAndCauseInBothVariants() {
        val failure = IllegalStateException("service disconnected")
        CarPlayLog.event(tag, "Connection state=RunningControl")
        CarPlayLog.w(tag, "audio fallback", failure)
        CarPlayLog.e(tag, "connection failed", failure)
        val logs = ShadowLog.getLogsForTag(tag)
        assertEquals(listOf(Log.INFO, Log.WARN, Log.ERROR), logs.map { it.type })
        assertEquals("Connection state=RunningControl", logs[0].msg)
        assertSame(failure, logs[1].throwable)
        assertSame(failure, logs[2].throwable)
    }

    @Test fun reportLevelsFollowTheBuildAndRetainedLevelsAreNeverDisabled() {
        assertEquals(BuildConfig.DEBUG, LogPolicy.enabled(DiagnosticLevel.DEBUG))
        for (level in listOf(DiagnosticLevel.EVENT, DiagnosticLevel.WARNING, DiagnosticLevel.ERROR)) {
            assertTrue(LogPolicy.enabled(level))
        }
    }
}
