package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaReadinessDiagnosticsTest {
    @Test fun serviceAuthorizationAndNotReadyRemainDifferentAndMessagesNeverEscape() {
        val error = InvocationTargetException(SecurityException("No Authorization to operate [mediacenter] for [secret-password]"))
        assertTrue(MediaReadinessDiagnostics.failure(error).contains("reason=caller_not_authorized"))
        assertFalse(MediaReadinessDiagnostics.failure(error).contains("secret"))
        assertEquals("target_service_not_ready", MediaReadinessDiagnostics.category("Service[mediacenter] is not ready yet!"))
        assertTrue(MediaReadinessDiagnostics.failure(IllegalStateException("unrelated private text")).contains("reason=unknown"))
    }

    @Test fun ownSdkLogEvidenceIsCategoricalAndCannotProveTheCauseOrExportVendorText() {
        val result = MediaReadinessDiagnostics.sdkLogSummary(
            "E/MediaCenterAPI: No Authorization to operate [mediacenter] for [private] 11:22:33:44:55:66\n" +
                "W/ECarXApiClient: Service[mediacenter] is not ready yet!\n" +
                "I/DiPlay: OEM media diagnostic permission denied\n" +
                "E/Unrelated: permission denied")
        assertTrue(result.contains("caller_not_authorized,target_service_not_ready"))
        assertTrue(result.contains("vendorProcess=unavailable")); assertTrue(result.contains("causeProven=false"))
        assertFalse(result.contains("private")); assertFalse(result.contains("11:22"))
        assertFalse(result.contains("permission_denied"))
        assertNotNull(DiagnosticRedactor.redact("OEM media diagnostic sdkLog $result"))
    }

    @Test fun missingPackagesAndServiceVisibilityAreNotReportedAsAProvenBindingFailure() {
        val lines = mutableListOf<String>()
        MediaReadinessDiagnostics.environment(RuntimeEnvironment.getApplication(), lines::add)
        assertTrue(lines.any { it.contains("package=openapi unavailable failureClass=NameNotFoundException") })
        assertTrue(lines.any { it.contains("service=openapi resolved=false queryOnly=true absenceNotProven=true") })
        assertTrue(lines.all { DiagnosticRedactor.redact(it) != null && it.length < 700 })
    }

    @Test fun diagnosticFailuresDoNotEscapeOrExposePrivateExceptionText() {
        val context = mock(Context::class.java)
        `when`(context.packageManager).thenThrow(SecurityException("password=private"))
        val lines = mutableListOf<String>()
        MediaReadinessDiagnostics.environment(context, lines::add)
        assertEquals(6, lines.size)
        assertTrue(lines.all { it.contains("failureClass=SecurityException") && !it.contains("private") })
    }
}
