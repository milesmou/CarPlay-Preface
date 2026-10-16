package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Installs the bundled consent UI only after an explicit action; never grants system permissions. */
internal class AuthorizationComponentInstaller(
    private val context: Context,
    private val fingerprint: (PackageInfo) -> String? = ::digest,
) {
    enum class State { INSTALLABLE, READY, EXISTING, UNAVAILABLE, PERMISSION_MISSING }

    fun state(): State = runCatching {
        val existing = installed()
        decide(Build.VERSION.SDK_INT, fingerprint(packageInfo("android")),
            context.assets.list("authorization")?.contains("authorization.apk") == true,
            existing != null, existing?.let(::isSupplement) == true,
            existing?.let(::ready) == true)
    }.getOrDefault(State.UNAVAILABLE)

    private fun installed(): PackageInfo? = try {
        context.packageManager.getPackageInfo(PACKAGE, FLAGS or PackageManager.MATCH_UNINSTALLED_PACKAGES)
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun packageInfo(name: String): PackageInfo = context.packageManager.getPackageInfo(name, FLAGS)

    private fun isSupplement(info: PackageInfo): Boolean = info.packageName == PACKAGE &&
        info.applicationInfo?.metaData?.getString(MARKER) == "android9-v1" &&
        info.versionName?.matches(Regex("preface-android9-\\d+")) == true && fingerprint(info) == CERTIFICATE &&
        (info.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) == 0

    private fun ready(info: PackageInfo): Boolean {
        val pm = context.packageManager
        if (!isSupplement(info) || info.applicationInfo?.enabled != true ||
            (info.applicationInfo!!.flags and ApplicationInfo.FLAG_INSTALLED) == 0) return false
        if (PERMISSIONS.any { pm.checkPermission(it, PACKAGE) != PackageManager.PERMISSION_GRANTED }) return false
        return listOf("ConfirmDialog", "WriteSettingsActivity").all { name ->
            val component = ComponentName(PACKAGE, "$PACKAGE.$name")
            val activity = pm.getActivityInfo(component, 0)
            activity.enabled && activity.exported && pm.getComponentEnabledSetting(component) !in setOf(
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
        }
    }

    fun prepareInstall(): Intent {
        check(state() == State.INSTALLABLE) { "Component installation is not available" }
        val metadata = context.assets.open("authorization/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        check(metadata.getString("packageName") == PACKAGE && metadata.getString("certificateSha256") == CERTIFICATE)
        val directory = File(context.cacheDir, "update/authorization").apply { mkdirs() }
        val file = File(directory, "Preface-Android9-Authorization.apk")
        try {
            context.assets.open("authorization/authorization.apk").use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            check(file.inputStream().use { sha256(it.readBytes()) } == metadata.getString("sha256"))
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, FLAGS)
                ?: error("Invalid bundled APK")
            check(isSupplement(archive) && archive.versionCode == metadata.getInt("versionCode") &&
                archive.versionName == metadata.getString("versionName") &&
                archive.applicationInfo!!.targetSdkVersion == 28 && archive.applicationInfo!!.minSdkVersion == 28 &&
                (archive.applicationInfo!!.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0)
            // Recheck after extraction: never replace a stock or newly installed package.
            check(state() == State.INSTALLABLE)
            val uri = FileProvider.getUriForFile(context.applicationContext, "${context.packageName}.update-apks", file)
            return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = android.content.ClipData.newRawUri("authorization-component", uri) }
        } catch (failure: Exception) { file.delete(); throw failure }
    }

    fun writeSettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}")).apply {
        if (state() == State.READY) component = ComponentName(PACKAGE, "$PACKAGE.WriteSettingsActivity")
    }

    companion object {
        const val PACKAGE = "com.android.vpndialogs"
        const val CERTIFICATE = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
        private const val MARKER = "preface.authorization.component"
        private const val FLAGS = PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA
        private val PERMISSIONS = listOf("CONTROL_VPN", "CONNECTIVITY_INTERNAL", "MANAGE_APP_OPS_MODES", "GET_APP_OPS_STATS")
            .map { "android.permission.$it" }
        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun digest(info: PackageInfo): String? = info.signatures?.singleOrNull()?.let { sha256(it.toByteArray()) }
        internal fun decide(api: Int, certificate: String?, bundled: Boolean, existing: Boolean, supplement: Boolean, ready: Boolean): State = when {
            existing && !supplement -> State.EXISTING
            api != 28 || certificate != CERTIFICATE -> State.UNAVAILABLE
            existing -> if (ready) State.READY else State.PERMISSION_MISSING
            bundled -> State.INSTALLABLE
            else -> State.UNAVAILABLE
        }
    }
}
