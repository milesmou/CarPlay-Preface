package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
        versionCode = 1
        versionName = "preface-android9-1"
        applicationInfo = ApplicationInfo().apply {
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
            .put("versionCode", 1).put("versionName", "preface-android9-1").put("sha256", hash).toString()
        `when`(assets.open("authorization/manifest.json")).thenAnswer { ByteArrayInputStream(value.toByteArray()) }
    }
    @Test fun matchingFirmwareAndMissingComponentCanPrepareReadOnlyInstallerUri() {
        assertEquals(AuthorizationComponentInstaller.State.INSTALLABLE, subject.state())
        val launch = subject.prepareInstall()
        assertEquals(Intent.ACTION_VIEW, launch.action)
        assertEquals("content", launch.data!!.scheme)
        assertEquals("application/vnd.android.package-archive", launch.type)
        assertTrue(launch.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(launch.data, launch.clipData!!.getItemAt(0).uri)
        verify(pm, never()).checkPermission(anyString(), anyString())
    }
    @Test fun inspectingStateDoesNotExtractApkOrLaunchAnything() {
        repeat(3) { subject.state() }
        verify(assets, never()).open(anyString())
        verify(pm, never()).getPackageArchiveInfo(anyString(), anyInt())
    }
    @Test fun hashMismatchCannotReachTheInstaller() {
        metadata("0".repeat(64))
        assertThrows(IllegalStateException::class.java) { subject.prepareInstall() }
        verify(pm, never()).getPackageArchiveInfo(anyString(), anyInt())
    }
    @Test fun archiveIdentityAndDebugFlagsMustMatch() {
        for (invalid in listOf(archive().apply { packageName = "other" },
            archive().apply { versionCode = 2 }, archive().apply { applicationInfo!!.targetSdkVersion = 29 },
            archive().apply { applicationInfo!!.flags = ApplicationInfo.FLAG_DEBUGGABLE })) {
            `when`(pm.getPackageArchiveInfo(anyString(), anyInt())).thenReturn(invalid)
            assertThrows(IllegalStateException::class.java) { subject.prepareInstall() }
        }
    }
    @Test fun mismatchedPlatformCertificateDoesNotExtract() {
        certificate = "other-platform"
        assertEquals(AuthorizationComponentInstaller.State.UNAVAILABLE, subject.state())
        assertThrows(IllegalStateException::class.java) { subject.prepareInstall() }
        verify(assets, never()).open(anyString())
    }
    @Test fun existingUnknownOrStockPackageNeverGetsReplaced() {
        doReturn(archive().apply { applicationInfo!!.metaData = null })
            .`when`(pm).getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
        assertEquals(AuthorizationComponentInstaller.State.EXISTING, subject.state())
        assertThrows(IllegalStateException::class.java) { subject.prepareInstall() }
    }
    @Test fun packageInstalledDuringExtractionStopsInstallation() {
        `when`(pm.getPackageArchiveInfo(anyString(), anyInt())).thenAnswer {
            doReturn(archive().apply { applicationInfo!!.metaData = null })
                .`when`(pm).getPackageInfo(eq(AuthorizationComponentInstaller.PACKAGE), anyInt())
            archive()
        }
        assertThrows(IllegalStateException::class.java) { subject.prepareInstall() }
    }
    @Test fun missingBundleDoesNotExposeAnInstallAction() {
        `when`(assets.list("authorization")).thenReturn(emptyArray())
        assertEquals(AuthorizationComponentInstaller.State.UNAVAILABLE, subject.state())
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
    }
}
