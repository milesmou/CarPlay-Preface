package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings

/** Manual system-consent actions only. Never starts a VPN service or creates a TUN interface. */
internal class SystemAuthorization(
    private val context: Context,
    private val openSettings: (Intent) -> Unit,
    private val openVpnConsent: (Intent) -> Unit,
) {
    enum class Result { UNKNOWN, OPENED, ALLOWED, NOT_ALLOWED, CANCELLED, UNAVAILABLE }

    fun canWriteSettings(): Boolean = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    fun requestWriteSettings(): Result = try {
        openSettings(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
        Result.OPENED
    } catch (_: RuntimeException) { Result.UNAVAILABLE }

    // prepare() may change the prepared VPN owner; do not invoke it during rendering or onResume.
    fun requestVpn(): Result = try {
        val intent = VpnService.prepare(context)
        if (intent == null) Result.ALLOWED else { openVpnConsent(intent); Result.OPENED }
    } catch (_: RuntimeException) { Result.UNAVAILABLE }

    fun finishVpnConsent(accepted: Boolean): Result {
        if (!accepted) return Result.CANCELLED
        return try {
            if (VpnService.prepare(context) == null) Result.ALLOWED else Result.NOT_ALLOWED
        } catch (_: RuntimeException) { Result.UNAVAILABLE }
    }
}
