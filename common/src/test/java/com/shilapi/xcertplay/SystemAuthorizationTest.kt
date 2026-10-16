package com.shilapi.xcertplay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], shadows = [AuthorizationVpnShadow::class, AuthorizationWriteShadow::class])
class SystemAuthorizationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val openedSettings = mutableListOf<Intent>()
    private val openedVpn = mutableListOf<Intent>()
    private fun authorization(
        settings: (Intent) -> Unit = { openedSettings.add(it) },
        vpn: (Intent) -> Unit = { openedVpn.add(it) },
    ) = SystemAuthorization(context, settings, vpn)

    @Before fun reset() {
        AuthorizationVpnShadow.resetState()
        AuthorizationWriteShadow.allowed = false
    }

    @Test fun readingWritePermissionDoesNotPrepareOrLaunchVpn() {
        val subject = authorization()
        assertFalse(subject.canWriteSettings())
        AuthorizationWriteShadow.allowed = true
        assertTrue(subject.canWriteSettings())
        assertEquals(0, AuthorizationVpnShadow.calls)
        assertTrue(openedVpn.isEmpty())
    }

    @Test fun writePermissionOpensOnlyThisAppsSettings() {
        assertEquals(SystemAuthorization.Result.OPENED, authorization().requestWriteSettings())
        assertEquals(Settings.ACTION_MANAGE_WRITE_SETTINGS, openedSettings.single().action)
        assertEquals("package:${context.packageName}", openedSettings.single().dataString)
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    @Test fun missingWriteSettingsDoesNotReportGranted() {
        assertEquals(SystemAuthorization.Result.UNAVAILABLE,
            authorization(settings = { throw ActivityNotFoundException() }).requestWriteSettings())
    }

    @Test fun manualVpnRequestLaunchesSystemConsent() {
        assertEquals(SystemAuthorization.Result.OPENED, authorization().requestVpn())
        assertSame(AuthorizationVpnShadow.consent, openedVpn.single())
        assertEquals(1, AuthorizationVpnShadow.calls)
    }

    @Test fun alreadyPreparedVpnDoesNotLaunchConsent() {
        AuthorizationVpnShadow.consent = null
        assertEquals(SystemAuthorization.Result.ALLOWED, authorization().requestVpn())
        assertTrue(openedVpn.isEmpty())
    }

    @Test fun cancellationDoesNotCheckOrClaimPermission() {
        assertEquals(SystemAuthorization.Result.CANCELLED, authorization().finishVpnConsent(false))
        assertEquals(0, AuthorizationVpnShadow.calls)
    }

    @Test fun acceptedResultChecksActualPreparation() {
        val subject = authorization()
        assertEquals(SystemAuthorization.Result.NOT_ALLOWED, subject.finishVpnConsent(true))
        AuthorizationVpnShadow.consent = null
        assertEquals(SystemAuthorization.Result.ALLOWED, subject.finishVpnConsent(true))
        assertTrue(openedVpn.isEmpty())
    }

    @Test fun missingVpnDialogDoesNotReportGranted() {
        assertEquals(SystemAuthorization.Result.UNAVAILABLE,
            authorization(vpn = { throw ActivityNotFoundException() }).requestVpn())
    }

    @Test fun blockedVpnPreparationAndResultAreUnavailable() {
        AuthorizationVpnShadow.failure = SecurityException("Unavailable system component")
        assertEquals(SystemAuthorization.Result.UNAVAILABLE, authorization().requestVpn())
        assertEquals(SystemAuthorization.Result.UNAVAILABLE, authorization().finishVpnConsent(true))
        assertTrue(openedVpn.isEmpty())
    }
}

@Implements(VpnService::class)
class AuthorizationVpnShadow {
    companion object {
        var consent: Intent? = null
        var failure: RuntimeException? = null
        var calls = 0
        fun resetState() {
            consent = Intent().setClassName("com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog")
            failure = null
            calls = 0
        }
        @JvmStatic @Implementation fun prepare(context: Context): Intent? {
            calls++
            failure?.let { throw it }
            return consent
        }
    }
}

@Implements(Settings.System::class)
class AuthorizationWriteShadow : ShadowSettings.ShadowSystem() {
    companion object {
        var allowed = false
        @JvmStatic @Implementation fun canWrite(context: Context): Boolean = allowed
    }
}
