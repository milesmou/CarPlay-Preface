package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.AssetManager
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FileProviderPathTestShadow::class])
class AuthorizationComponentInstallerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val pm = mock(PackageManager::class.java)
    private val assets = mock(AssetManager::class.java)
    private val bytes = byteArrayOf(1, 2, 3)
    private var certificate = AuthorizationComponentInstaller.CERTIFICATE
    private val context: android.content.Context by lazy { object : ContextWrapper(app) {
        override fun getPackageManager(): PackageManager = this@AuthorizationComponentInstallerTest.pm
        override fun getAssets(): AssetManager = this@AuthorizationComponentInstallerTest.assets
    } }
    private val subject by lazy { AuthorizationComponentInstaller(context) { certificate } }
    private fun archive() = PackageInfo().apply {
        packageName = AuthorizationComponentInstaller.PACKAGE
        versionCode = 7
        versionName = "preface-android9-7"
        sharedUserId = AuthorizationComponentInstaller.SHARED_USER_ID
        applicationInfo = ApplicationInfo().apply {
            uid = android.os.Process.myUid() / 100000 * 100000 + 1000
            targetSdkVersion = 28; minSdkVersion = 28
            metaData = Bundle().apply { putString("preface.authorization.component", "android9-v1") }
        }
    }
    @Before fun setup() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
        `when`(pm.getPackageInfo(eq("android"), anyInt())).thenReturn(PackageInfo())
        `when`(pm.getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt()))
            .thenThrow(PackageManager.NameNotFoundException())
        `when`(assets.list("authorization")).thenReturn(arrayOf("authorization.apk", "manifest.json"))
        `when`(assets.open("authorization/authorization.apk")).thenAnswer { ByteArrayInputStream(bytes) }
        metadata(AuthorizationComponentInstaller.sha256(bytes))
        `when`(pm.getPackageArchiveInfo(anyString(), anyInt())).thenReturn(archive())
    }
    private fun metadata(hash: String) {
        val value = JSONObject().put("packageName", AuthorizationComponentInstaller.PACKAGE)
            .put("certificateSha256", AuthorizationComponentInstaller.CERTIFICATE)
            .put("sharedUserId", AuthorizationComponentInstaller.SHARED_USER_ID)
            .put("versionCode", 7).put("versionName", "preface-android9-7").put("sha256", hash).toString()
        `when`(assets.open("authorization/manifest.json")).thenAnswer { ByteArrayInputStream(value.toByteArray()) }
    }
    @Test fun matchingFirmwareAndMissingComponentCanPrepareVerifiedApkForExport() {
        assertEquals(AuthorizationComponentInstaller.State.INSTALLABLE, subject.state())
        val file = subject.prepareExport()
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("Preface-Android9-Authorization.apk", file.name)
        verify(pm, never()).checkPermission(anyString(), anyString())
    }
    @Test fun inspectingStateDoesNotExtractApkOrLaunchAnything() {
        repeat(3) { subject.state() }
        verify(assets, never()).open(anyString())
        verify(pm, never()).getPackageArchiveInfo(anyString(), anyInt())
    }
    @Test fun hashMismatchCannotReachTheInstaller() {
        metadata("0".repeat(64))
        assertThrows(IllegalStateException::class.java) { subject.prepareExport() }
        verify(pm, never()).getPackageArchiveInfo(anyString(), anyInt())
    }
    @Test fun archiveIdentityAndDebugFlagsMustMatch() {
        for (invalid in listOf(archive().apply { packageName = "other" },
            archive().apply { versionCode = 2 }, archive().apply { applicationInfo!!.targetSdkVersion = 29 },
            archive().apply { sharedUserId = null }, archive().apply { sharedUserId = "other.shared.user" },
            archive().apply { applicationInfo!!.flags = ApplicationInfo.FLAG_DEBUGGABLE })) {
            `when`(pm.getPackageArchiveInfo(anyString(), anyInt())).thenReturn(invalid)
            assertThrows(IllegalStateException::class.java) { subject.prepareExport() }
        }
    }
    @Test fun archiveIdentityIsCheckedWithoutAssumingAnInstalledRuntimeUid() {
        `when`(pm.getPackageArchiveInfo(anyString(), anyInt())).thenReturn(archive().apply { applicationInfo!!.uid = -1 })
        assertArrayEquals(bytes, subject.prepareExport().readBytes())
    }
    @Test fun bundleMetadataMustDeclareTheSystemSharedIdentity() {
        val value = JSONObject().put("packageName", AuthorizationComponentInstaller.PACKAGE)
            .put("certificateSha256", AuthorizationComponentInstaller.CERTIFICATE)
            .put("versionCode", 7).put("versionName", "preface-android9-7")
            .put("sha256", AuthorizationComponentInstaller.sha256(bytes)).toString()
        `when`(assets.open("authorization/manifest.json")).thenAnswer { ByteArrayInputStream(value.toByteArray()) }
        assertThrows(org.json.JSONException::class.java) { subject.prepareExport() }
        verify(pm, never()).getPackageArchiveInfo(anyString(), anyInt())
    }
    @Test fun mismatchedPlatformCertificateDoesNotExtract() {
        certificate = "other-platform"
        assertEquals(AuthorizationComponentInstaller.State.UNAVAILABLE, subject.state())
        assertThrows(IllegalStateException::class.java) { subject.prepareExport() }
        verify(assets, never()).open(anyString())
    }
    @Test fun existingUnknownOrStockPackageNeverGetsReplaced() {
        doReturn(archive().apply { applicationInfo!!.metaData = null })
            .`when`(pm).getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
        assertEquals(AuthorizationComponentInstaller.State.EXISTING, subject.state())
        assertThrows(IllegalStateException::class.java) { subject.prepareExport() }
    }
    @Test fun packageInstalledDuringExtractionStopsInstallation() {
        `when`(pm.getPackageArchiveInfo(anyString(), anyInt())).thenAnswer {
            doReturn(archive().apply { applicationInfo!!.metaData = null })
                .`when`(pm).getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
            archive()
        }
        assertThrows(IllegalStateException::class.java) { subject.prepareExport() }
    }
    @Test fun missingBundleDoesNotExposeAnInstallAction() {
        `when`(assets.list("authorization")).thenReturn(emptyArray())
        assertEquals(AuthorizationComponentInstaller.State.UNAVAILABLE, subject.state())
    }
    @Test fun ownOlderComponentCanBeExportedForAManualUpdate() {
        doReturn(archive().apply { versionCode = 6; versionName = "preface-android9-6" }).`when`(pm)
            .getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
        assertEquals(AuthorizationComponentInstaller.State.UPDATE_AVAILABLE, subject.state())
        assertArrayEquals(bytes, subject.prepareExport().readBytes())
    }
    @Test fun oldOwnIndependentUidComponentCanBeSavedOnlyForManualReinstallation() {
        val info = archive().apply {
            versionCode = 6; versionName = "preface-android9-6"
            sharedUserId = null
            applicationInfo!!.uid = 10042
            applicationInfo!!.enabled = true
            applicationInfo!!.flags = ApplicationInfo.FLAG_INSTALLED
        }
        doReturn(info).`when`(pm).getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
        assertEquals(AuthorizationComponentInstaller.State.REINSTALL_REQUIRED, subject.state())
        assertTrue(AuthorizationComponentInstaller.canExport(subject.state()))
        assertArrayEquals(bytes, subject.prepareExport().readBytes())
        assertNull(subject.writeSettingsIntent().component)
        assertNull(subject.overlaySettingsIntent().component)
        verify(pm, never()).checkPermission(anyString(), anyString())
    }
    @Test fun currentVersionWithWrongInstalledIdentityStillRequiresReinstallation() {
        val info = archive()
        doReturn(info).`when`(pm).getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
        val currentSystemUid = android.os.Process.myUid() / 100000 * 100000 + 1000
        for (uid in listOf(currentSystemUid + 9042, currentSystemUid + 100000)) {
            info.applicationInfo!!.uid = uid
            assertEquals(AuthorizationComponentInstaller.State.REINSTALL_REQUIRED, subject.state())
        }
        info.applicationInfo!!.uid = currentSystemUid
        info.sharedUserId = null
        assertEquals(AuthorizationComponentInstaller.State.REINSTALL_REQUIRED, subject.state())
    }
    @Test fun missingCompatibleBundleDoesNotOfferLegacyIdentityMigration() {
        val info = archive().apply { sharedUserId = null; applicationInfo!!.uid = 10042 }
        doReturn(info).`when`(pm).getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
        `when`(assets.list("authorization")).thenReturn(emptyArray())
        assertEquals(AuthorizationComponentInstaller.State.PERMISSION_MISSING, subject.state())
        assertFalse(AuthorizationComponentInstaller.canExport(subject.state()))
    }
    @Test fun decisionKeepsExistingComponentsAndDistinguishesMissingPermissions() {
        val cert = AuthorizationComponentInstaller.CERTIFICATE
        assertEquals(AuthorizationComponentInstaller.State.UNAVAILABLE,
            AuthorizationComponentInstaller.decide(34, cert, true, false, false, false))
        assertEquals(AuthorizationComponentInstaller.State.EXISTING,
            AuthorizationComponentInstaller.decide(34, "other", true, true, false, false))
        assertEquals(AuthorizationComponentInstaller.State.PERMISSION_MISSING,
            AuthorizationComponentInstaller.decide(28, cert, true, true, true, false))
        assertEquals(AuthorizationComponentInstaller.State.READY,
            AuthorizationComponentInstaller.decide(28, cert, false, true, true, true))
        assertEquals(AuthorizationComponentInstaller.State.UPDATE_AVAILABLE,
            AuthorizationComponentInstaller.decide(28, cert, true, true, true, true, true))
        assertEquals(AuthorizationComponentInstaller.State.EXISTING,
            AuthorizationComponentInstaller.decide(28, cert, true, true, false, false, true))
        assertEquals(AuthorizationComponentInstaller.State.REINSTALL_REQUIRED,
            AuthorizationComponentInstaller.decide(28, cert, true, true, true, false, true, true))
        assertEquals(AuthorizationComponentInstaller.State.EXISTING,
            AuthorizationComponentInstaller.decide(28, cert, true, true, false, false, true, true))
        assertEquals(AuthorizationComponentInstaller.State.UNAVAILABLE,
            AuthorizationComponentInstaller.decide(28, "other", true, true, true, false, true, true))
    }
    @Test fun onlyComponentThreeWithAllPermissionsCanReceiveAdditionalSettingsExplicitly() {
        `when`(assets.list("authorization")).thenReturn(emptyArray())
        val info = archive().apply {
            versionCode = 3; versionName = "preface-android9-3"
            sharedUserId = null
            applicationInfo!!.uid = 10042
            applicationInfo!!.enabled = true
            applicationInfo!!.flags = ApplicationInfo.FLAG_INSTALLED
        }
        doReturn(info).`when`(pm).getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
        `when`(pm.getActivityInfo(any(android.content.ComponentName::class.java), anyInt())).thenReturn(
            android.content.pm.ActivityInfo().apply { enabled = true; exported = true })
                assertEquals("com.android.vpndialogs.AdditionalAuthorizationActivity", subject.overlaySettingsIntent().component!!.className)
        info.versionCode = 2
        assertNull(subject.overlaySettingsIntent().component)
        info.versionCode = 3
        `when`(pm.checkPermission("android.permission.GET_APP_OPS_STATS", AuthorizationComponentInstaller.PACKAGE)).thenReturn(PackageManager.PERMISSION_DENIED)
        assertNull(subject.overlaySettingsIntent().component)
        assertEquals(AuthorizationComponentInstaller.State.PERMISSION_MISSING, subject.state())
    }

    private fun installReadySupplement() {
        val info = archive().apply {
            applicationInfo!!.enabled = true
            applicationInfo!!.flags = ApplicationInfo.FLAG_INSTALLED
        }
        doReturn(info).`when`(pm).getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
        `when`(pm.getActivityInfo(any(ComponentName::class.java), anyInt())).thenReturn(
            ActivityInfo().apply { enabled = true; exported = true })
        `when`(pm.getServiceInfo(any(ComponentName::class.java), anyInt())).thenReturn(
            android.content.pm.ServiceInfo().apply { enabled = true; exported = true })
    }

    private fun settingsHandler(name: String, system: Boolean = false) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = name
            this.name = "$name.SpecialAccessActivity"
            enabled = true
            exported = true
            applicationInfo = ApplicationInfo().apply {
                enabled = true
                flags = ApplicationInfo.FLAG_INSTALLED or (if (system) ApplicationInfo.FLAG_SYSTEM else 0)
            }
        }
    }

    @Test fun nativeSettingsTakesPriorityWhenSupplementAlsoInstalled() {
        installReadySupplement()
        val native = settingsHandler("com.android.settings", system = true)
        `when`(pm.queryIntentActivities(any(Intent::class.java), eq(PackageManager.MATCH_DEFAULT_ONLY)))
            .thenReturn(listOf(settingsHandler(AuthorizationComponentInstaller.PACKAGE),
                settingsHandler("vendor.settings", system = true), native))

        val write = subject.writeSettingsIntent()
        val overlay = subject.overlaySettingsIntent()
        for (intent in listOf(write, overlay)) {
            assertEquals(ComponentName(native.activityInfo.packageName, native.activityInfo.name), intent.component)
            assertEquals("package:${context.packageName}", intent.dataString)
        }
        assertEquals(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, write.action)
        assertEquals(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay.action)
        verify(pm, never()).getActivityInfo(any(ComponentName::class.java), anyInt())
    }

    @Test fun oemSystemSettingsHandlerTakesPriorityOverOtherStandardHandlers() {
        val native = settingsHandler("vendor.settings", system = true)
        `when`(pm.queryIntentActivities(any(Intent::class.java), eq(PackageManager.MATCH_DEFAULT_ONLY)))
            .thenReturn(listOf(settingsHandler("another.settings"), native))

        for (intent in listOf(subject.writeSettingsIntent(), subject.overlaySettingsIntent()))
            assertEquals(ComponentName(native.activityInfo.packageName, native.activityInfo.name), intent.component)
    }

    @Test fun absentNativeSettingsUsesValidatedSupplementForBothPermissions() {
        installReadySupplement()
        `when`(pm.queryIntentActivities(any(Intent::class.java), eq(PackageManager.MATCH_DEFAULT_ONLY)))
            .thenReturn(listOf(settingsHandler(AuthorizationComponentInstaller.PACKAGE)))

        assertEquals(ComponentName(AuthorizationComponentInstaller.PACKAGE,
            "${AuthorizationComponentInstaller.PACKAGE}.WriteSettingsActivity"), subject.writeSettingsIntent().component)
        assertEquals(ComponentName(AuthorizationComponentInstaller.PACKAGE,
            "${AuthorizationComponentInstaller.PACKAGE}.AdditionalAuthorizationActivity"), subject.overlaySettingsIntent().component)
    }

    @Test fun disabledUnexportedOrProtectedNativeHandlersCannotBypassSupplementValidation() {
        installReadySupplement()
        val disabledActivity = settingsHandler("disabled.activity", system = true).apply { activityInfo.enabled = false }
        val disabledApp = settingsHandler("disabled.application", system = true).apply { activityInfo.applicationInfo.enabled = false }
        val privateActivity = settingsHandler("private.activity", system = true).apply { activityInfo.exported = false }
        val disabledComponent = settingsHandler("disabled.component", system = true)
        `when`(pm.getComponentEnabledSetting(ComponentName(disabledComponent.activityInfo.packageName,
            disabledComponent.activityInfo.name))).thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        val disabledPackage = settingsHandler("disabled.package", system = true)
        `when`(pm.getApplicationEnabledSetting(disabledPackage.activityInfo.packageName))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        val protectedActivity = settingsHandler("protected.activity", system = true).apply {
            activityInfo.permission = "vendor.permission.SETTINGS"
        }
        `when`(pm.checkPermission(protectedActivity.activityInfo.permission, context.packageName))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        `when`(pm.queryIntentActivities(any(Intent::class.java), eq(PackageManager.MATCH_DEFAULT_ONLY)))
            .thenReturn(listOf(disabledActivity, disabledApp, privateActivity, disabledComponent,
                disabledPackage, protectedActivity))

        assertEquals(AuthorizationComponentInstaller.PACKAGE, subject.writeSettingsIntent().component!!.packageName)
        assertEquals(AuthorizationComponentInstaller.PACKAGE, subject.overlaySettingsIntent().component!!.packageName)
        `when`(pm.checkPermission("android.permission.GET_APP_OPS_STATS", AuthorizationComponentInstaller.PACKAGE))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        assertNull(subject.writeSettingsIntent().component)
        assertNull(subject.overlaySettingsIntent().component)
    }

    @Test fun componentSixNeedsBluetoothPermissionsAndAnEnabledMusicService() {
        `when`(assets.list("authorization")).thenReturn(emptyArray())
        val info = archive().apply {
            versionCode = 6; versionName = "preface-android9-6"
            sharedUserId = null
            applicationInfo!!.uid = 10042
            applicationInfo!!.enabled = true
            applicationInfo!!.flags = ApplicationInfo.FLAG_INSTALLED
        }
        doReturn(info).`when`(pm).getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
        `when`(pm.getActivityInfo(any(android.content.ComponentName::class.java), anyInt())).thenReturn(
            android.content.pm.ActivityInfo().apply { enabled = true; exported = true })
        val service = android.content.pm.ServiceInfo().apply { enabled = true; exported = true }
        `when`(pm.getServiceInfo(any(android.content.ComponentName::class.java), anyInt())).thenReturn(service)
        assertEquals(AuthorizationComponentInstaller.State.READY, subject.state())
        service.enabled = false
        assertEquals(AuthorizationComponentInstaller.State.PERMISSION_MISSING, subject.state())
        service.enabled = true
        `when`(pm.checkPermission("android.permission.BLUETOOTH_ADMIN", AuthorizationComponentInstaller.PACKAGE))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        assertEquals(AuthorizationComponentInstaller.State.PERMISSION_MISSING, subject.state())
    }

    @Test fun componentSevenRequiresNativeAppOpsReadPermissionWithoutCrossUidQueryPermission() {
        val info = archive().apply {
            versionCode = 7; versionName = "preface-android9-7"
            applicationInfo!!.enabled = true
            applicationInfo!!.flags = ApplicationInfo.FLAG_INSTALLED
        }
        doReturn(info).`when`(pm).getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
        `when`(pm.getActivityInfo(any(ComponentName::class.java), anyInt())).thenReturn(
            ActivityInfo().apply { enabled = true; exported = true })
        `when`(pm.getServiceInfo(any(ComponentName::class.java), anyInt())).thenReturn(
            android.content.pm.ServiceInfo().apply { enabled = true; exported = true })
        assertEquals(AuthorizationComponentInstaller.State.READY, subject.state())
        assertArrayEquals(bytes, subject.prepareExport().readBytes())

        `when`(assets.list("authorization")).thenReturn(emptyArray())
        assertEquals(AuthorizationComponentInstaller.State.READY, subject.state())
        assertThrows(IllegalStateException::class.java) { subject.prepareExport() }
        `when`(assets.list("authorization")).thenReturn(arrayOf("authorization.apk", "manifest.json"))

        `when`(pm.checkPermission("android.permission.UPDATE_APP_OPS_STATS", AuthorizationComponentInstaller.PACKAGE))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        assertEquals(AuthorizationComponentInstaller.State.READY, subject.state())
        verify(pm, never()).checkPermission("android.permission.UPDATE_APP_OPS_STATS", AuthorizationComponentInstaller.PACKAGE)

        `when`(pm.checkPermission("android.permission.GET_APP_OPS_STATS", AuthorizationComponentInstaller.PACKAGE))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        assertEquals(AuthorizationComponentInstaller.State.PERMISSION_MISSING, subject.state())
        assertNull(subject.writeSettingsIntent().component)
        assertNull(subject.overlaySettingsIntent().component)
    }

    @Test fun unrelatedStandardHandlerCannotDisplaceValidatedSupplement() {
        installReadySupplement()
        `when`(pm.queryIntentActivities(any(Intent::class.java), eq(PackageManager.MATCH_DEFAULT_ONLY)))
            .thenReturn(listOf(settingsHandler("another.settings")))
        assertEquals(AuthorizationComponentInstaller.PACKAGE, subject.writeSettingsIntent().component!!.packageName)
        assertEquals(AuthorizationComponentInstaller.PACKAGE, subject.overlaySettingsIntent().component!!.packageName)
    }
}
