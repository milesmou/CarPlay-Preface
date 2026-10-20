package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Validates the bundled consent APK for export; never launches an APK installer or grants permissions. */
internal class AuthorizationComponentInstaller(
    private val context: Context,
    private val fingerprint: (PackageInfo) -> String? = ::digest,
) {
    enum class State { INSTALLABLE, UPDATE_AVAILABLE, REINSTALL_REQUIRED, READY, EXISTING, UNAVAILABLE, PERMISSION_MISSING }

    fun state(): State = runCatching {
        val existing = installed()
        val bundled = context.assets.list("authorization")?.contains("authorization.apk") == true
        val supplement = existing?.let(::isSupplement) == true
        val metadata = if (bundled && supplement) bundledMetadata() else null
        val compatibleBundle = metadata?.let {
            it.optString("packageName") == PACKAGE && it.optString("certificateSha256") == CERTIFICATE &&
                it.optString("sharedUserId") == SHARED_USER_ID && it.optInt("versionCode") >= 7
        } == true
        val reinstall = compatibleBundle && !hasSystemIdentity(existing!!)
        decide(Build.VERSION.SDK_INT, fingerprint(packageInfo("android")),
            bundled, existing != null, supplement, !reinstall && existing?.let(::ready) == true,
            compatibleBundle && existing!!.versionCode < metadata!!.getInt("versionCode"),
            reinstall)
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
            (info.applicationInfo!!.flags and ApplicationInfo.FLAG_INSTALLED) == 0 ||
            (info.versionCode >= 7 && !hasSystemIdentity(info))) return false
        val permissions = PERMISSIONS + (if (info.versionCode >= 5)
            listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.MANAGE_USERS") else emptyList()) +
            (if (info.versionCode >= 6) listOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN") else emptyList())
        if (permissions.any { pm.checkPermission(it, PACKAGE) != PackageManager.PERMISSION_GRANTED }) return false
        val activities = listOf("ConfirmDialog", "WriteSettingsActivity") +
            (if (info.versionCode >= 3) listOf("AdditionalAuthorizationActivity") else emptyList()) +
            (if (info.versionCode >= 5) listOf("SpecialAccessActivity") else emptyList())
        val activitiesReady = activities.all { name ->
            val component = ComponentName(PACKAGE, "$PACKAGE.$name")
            val activity = pm.getActivityInfo(component, 0)
            activity.enabled && activity.exported && pm.getComponentEnabledSetting(component) !in setOf(
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
        }
        if (!activitiesReady || info.versionCode < 6) return activitiesReady
        val component = ComponentName(PACKAGE, "$PACKAGE.BluetoothMusicService")
        val service = pm.getServiceInfo(component, 0)
        return service.enabled && service.exported && pm.getComponentEnabledSetting(component) !in setOf(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
    }

    private fun bundledMetadata(): JSONObject = context.assets.open("authorization/manifest.json").bufferedReader().use { JSONObject(it.readText()) }

    private fun hasSystemIdentity(info: PackageInfo): Boolean {
        val uid = info.applicationInfo?.uid ?: return false
        return info.sharedUserId == SHARED_USER_ID && uid >= 0 && uid % PER_USER_RANGE == 1000 &&
            uid / PER_USER_RANGE == Process.myUid() / PER_USER_RANGE
    }

    fun prepareExport(): File {
        check(canExport(state()) && context.assets.list("authorization")?.contains("authorization.apk") == true) {
            "Component export is not available"
        }
        val metadata = bundledMetadata()
        check(metadata.getString("packageName") == PACKAGE && metadata.getString("certificateSha256") == CERTIFICATE &&
            metadata.getString("sharedUserId") == SHARED_USER_ID)
        val directory = File(context.cacheDir, "update/authorization").apply { mkdirs() }
        val file = File(directory, "Preface-Android9-Authorization.apk")
        try {
            context.assets.open("authorization/authorization.apk").use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            check(file.inputStream().use { sha256(it.readBytes()) } == metadata.getString("sha256"))
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, FLAGS)
                ?: error("Invalid bundled APK")
            check(isSupplement(archive) && archive.sharedUserId == SHARED_USER_ID &&
                archive.versionCode == metadata.getInt("versionCode") &&
                archive.versionName == metadata.getString("versionName") &&
                archive.applicationInfo!!.targetSdkVersion == 28 && archive.applicationInfo!!.minSdkVersion == 28 &&
                (archive.applicationInfo!!.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0)
            check(canExport(state()))
            return file
        } catch (failure: Exception) { file.delete(); throw failure }
    }

    fun writeSettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}")).apply {
        val native = nativeSettingsComponent(this)
        if (native != null) component = native
        else if (state() in setOf(State.READY, State.UPDATE_AVAILABLE))
            component = ComponentName(PACKAGE, "$PACKAGE.WriteSettingsActivity")
    }

    fun overlaySettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply {
        val native = nativeSettingsComponent(this)
        if (native != null) component = native
        else {
            val info = installed()
            if (info != null && info.versionCode >= 3 && state() in setOf(State.READY, State.UPDATE_AVAILABLE) && ready(info))
                component = ComponentName(PACKAGE, "$PACKAGE.AdditionalAuthorizationActivity")
        }
    }

    private fun nativeSettingsComponent(intent: Intent): ComponentName? {
        val pm = context.packageManager
        val disabled = setOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
        return runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }
            .getOrDefault(emptyList()).mapNotNull { it.activityInfo }.filter { activity ->
                runCatching {
                    activity.packageName != PACKAGE && activity.exported && activity.enabled &&
                        activity.applicationInfo?.enabled == true &&
                        (activity.packageName == "com.android.settings" ||
                            (activity.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) &&
                        pm.getApplicationEnabledSetting(activity.packageName) !in disabled &&
                        pm.getComponentEnabledSetting(ComponentName(activity.packageName, activity.name)) !in disabled &&
                        (activity.permission.isNullOrEmpty() ||
                            pm.checkPermission(activity.permission, context.packageName) == PackageManager.PERMISSION_GRANTED)
                }.getOrDefault(false)
            }.sortedWith(compareBy({
                when {
                    it.packageName == "com.android.settings" -> 0
                    (it.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 -> 1
                    else -> 2
                }
            }, { it.packageName }, { it.name }))
            .firstOrNull()?.let { ComponentName(it.packageName, it.name) }
    }

    companion object {
        const val PACKAGE = "com.android.vpndialogs"
        const val SHARED_USER_ID = "android.uid.system"
        const val CERTIFICATE = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
        private const val PER_USER_RANGE = 100000
        private const val MARKER = "preface.authorization.component"
        private const val FLAGS = PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA
        private val PERMISSIONS = listOf("CONTROL_VPN", "CONNECTIVITY_INTERNAL", "MANAGE_APP_OPS_MODES", "GET_APP_OPS_STATS")
            .map { "android.permission.$it" }
        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun digest(info: PackageInfo): String? = info.signatures?.singleOrNull()?.let { sha256(it.toByteArray()) }
        internal fun canExport(state: State) = state in setOf(State.INSTALLABLE, State.UPDATE_AVAILABLE, State.REINSTALL_REQUIRED, State.READY)
        internal fun decide(api: Int, certificate: String?, bundled: Boolean, existing: Boolean, supplement: Boolean, ready: Boolean, update: Boolean = false, reinstall: Boolean = false): State = when {
            existing && !supplement -> State.EXISTING
            api != 28 || certificate != CERTIFICATE -> State.UNAVAILABLE
            existing && bundled && reinstall -> State.REINSTALL_REQUIRED
            existing && bundled && update -> State.UPDATE_AVAILABLE
            existing -> if (ready) State.READY else State.PERMISSION_MISSING
            bundled -> State.INSTALLABLE
            else -> State.UNAVAILABLE
        }
    }
}
