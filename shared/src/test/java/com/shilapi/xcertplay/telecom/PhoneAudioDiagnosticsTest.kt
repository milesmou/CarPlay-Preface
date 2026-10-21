package com.shilapi.xcertplay.telecom

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowTelecomManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [PhoneAudioDiagnosticsTest.ProbeTelecom::class])
class PhoneAudioDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        ProbeTelecom.resetProbe()
        PhoneAudioCall.end()
        shadowOf(context).grantPermissions(Manifest.permission.MANAGE_OWN_CALLS)
    }

    @After fun release() { PhoneAudioCall.end() }

    @Test fun deniedAdmissionQueryRemainsDiagnosticAndCallbackFailureIsReportedSeparately() {
        assertFalse(TelecomPhoneAudioBackend(context).start())
        assertEquals(1, ProbeTelecom.registrations)
        assertEquals(1, ProbeTelecom.queries)
        assertEquals(1, ProbeTelecom.placements)
        val history = latestRequest()
        assertTrue(history.any { it.contains("event=REQUEST manageOwnCallsGranted=true") })
        assertTrue(history.any { it.contains("event=REGISTERED") })
        assertTrue(history.any { it.contains("event=ADMISSION_QUERY outgoingPermitted=false") })
        assertTrue(history.any { it.contains("event=CREATE_FAILED active=false") })
        assertTrue(history.last().contains("result=CALLBACK_FAILED"))
    }

    @Test fun admissionQueryExceptionDoesNotBlockOriginalRequestOrLeakItsMessage() {
        ProbeTelecom.failQuery = true
        ProbeTelecom.accept = true
        assertTrue(TelecomPhoneAudioBackend(context).start())
        assertEquals(1, ProbeTelecom.placements)
        val history = latestRequest()
        assertTrue(history.any { it.contains("event=ADMISSION_QUERY outgoingPermitted=unknown error=SecurityException") })
        assertTrue(history.last().contains("result=ACTIVE"))
        assertFalse(history.joinToString().contains("PRIVATE_CALL_ID"))
        PhoneAudioCall.end()
        assertTrue(PhoneAudioDiagnostics.snapshot().last().contains("event=RELEASED active=false"))
    }

    @Test fun securityFailureIsDistinguishedFromConnectionCallbackFailure() {
        ProbeTelecom.failRegistration = true
        assertFalse(TelecomPhoneAudioBackend(context).start())
        assertEquals(0, ProbeTelecom.placements)
        val result = latestRequest().last()
        assertTrue(result.contains("result=SECURITY_ERROR error=SecurityException"))
        assertFalse(result.contains("PRIVATE_CALL_ID"))
    }

    @Test fun historyIsBoundedAndReportExportDoesNotStartOrRegisterCalls() {
        repeat(60) { PhoneAudioDiagnostics.record(PhoneAudioDiagnostics.Event.SETTINGS_CHANGED,
            carplay = it % 2 == 0, otherApps = false) }
        val history = PhoneAudioDiagnostics.snapshot()
        assertEquals(48, history.size)
        assertTrue(history.all { it.length < 700 && '\n' !in it && it.contains("otherAppsSetting=false") })
        PhoneAudioSettings.setPhoneAudioRoute(context, true)
        val report = PhoneAudioRoute.diagnosticReport(context)
        assertTrue(report.contains("carplay=true"))
        assertTrue(report.contains("current process only"))
        assertTrue(report.contains(history.last()))
        assertEquals(0, ProbeTelecom.registrations)
        assertEquals(0, ProbeTelecom.placements)
        assertFalse(PhoneAudioCall.isActive())
    }

    private fun latestRequest(): List<String> = PhoneAudioDiagnostics.snapshot().let { lines ->
        lines.drop(lines.indexOfLast { it.contains("event=REQUEST ") })
    }

    @Implements(TelecomManager::class)
    class ProbeTelecom : ShadowTelecomManager() {
        @Implementation override fun registerPhoneAccount(account: PhoneAccount) {
            registrations++
            if (failRegistration) throw SecurityException("PRIVATE_CALL_ID")
        }
        @Implementation override fun isOutgoingCallPermitted(handle: PhoneAccountHandle): Boolean {
            queries++
            if (failQuery) throw SecurityException("PRIVATE_CALL_ID")
            return false
        }
        @Implementation override fun placeCall(address: Uri, extras: Bundle) {
            placements++
            if (accept) PhoneAudioCall.create() else PhoneAudioCall.failed()
        }
        companion object {
            var registrations = 0
            var queries = 0
            var placements = 0
            var failQuery = false
            var failRegistration = false
            var accept = false
            @Resetter @JvmStatic fun resetProbe() {
                registrations = 0; queries = 0; placements = 0
                failQuery = false; failRegistration = false; accept = false
            }
        }
    }
}
