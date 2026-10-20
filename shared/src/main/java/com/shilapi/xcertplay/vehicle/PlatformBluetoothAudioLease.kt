package com.shilapi.xcertplay.vehicle

import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.Closeable
import java.security.MessageDigest
import java.util.UUID

internal interface BluetoothAudioLeaseClient : Closeable { fun start() }

/** Holds the platform service binding until the session releases its selected A2DP lease. */
internal class PlatformBluetoothAudioLease(context: Context, private val peer: String,
    private val changed: (Boolean, String) -> Unit) : BluetoothAudioLeaseClient {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val nonce = UUID.randomUUID().toString()
    private var bound = false
    private var closed = false
    private var remote: Messenger? = null
    private val timeout = Runnable { fail("platform session handoff timed out") }
    private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
        val expectedUid = runCatching { app.packageManager.getApplicationInfo(PACKAGE, 0).uid }.getOrNull()
        if (!closed && message.what == 3 && message.sendingUid == expectedUid && message.data.getString("nonce") == nonce) {
            main.removeCallbacks(timeout)
            val accepted = message.data.getBoolean("accepted")
            val reason = message.data.getString("reason") ?: "platform session reply"
            if (accepted && available()) changed(true, reason) else fail(reason)
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            if (name != COMPONENT || !available()) { fail("platform handoff component changed"); return }
            remote = Messenger(binder)
            try {
                remote?.send(Message.obtain(null, 4).apply {
                    replyTo = reply
                    data = Bundle().apply { putString("nonce", nonce); putString("address", peer) }
                })
            } catch (_: Exception) { fail("platform handoff service unavailable") }
        }
        override fun onServiceDisconnected(name: ComponentName) = fail("platform handoff service disconnected")
        override fun onBindingDied(name: ComponentName) = fail("platform handoff binding ended")
        override fun onNullBinding(name: ComponentName) = fail("platform handoff binding rejected")
    }

    override fun start() {
        if (closed || bound) return
        if (!BluetoothAdapter.checkBluetoothAddress(peer) || !available()) {
            fail("compatible authorization component 10 is required for session A2DP handoff"); return
        }
        try {
            // Set before binding: a synchronous local callback must also be able to unbind.
            bound = true
            if (!app.bindService(Intent(ACTION).setComponent(COMPONENT), connection, Context.BIND_AUTO_CREATE)) {
                bound = false; fail("platform handoff service could not bind")
            } else if (!closed) main.postDelayed(timeout, 6_000L)
        } catch (_: Exception) { fail("platform handoff service could not bind") }
    }

    @Suppress("DEPRECATION")
    private fun available(): Boolean = try {
        val pm = app.packageManager
        val component = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES)
        val platform = pm.getPackageInfo("android", PackageManager.GET_SIGNATURES)
        val application = pm.getApplicationInfo(PACKAGE, PackageManager.GET_META_DATA)
        val service = pm.getServiceInfo(COMPONENT, 0)
        val disabled = setOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
        fun certificate(info: android.content.pm.PackageInfo) = info.signatures?.singleOrNull()?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }
        Build.VERSION.SDK_INT == 28 && component.longVersionCode >= 10 &&
            component.sharedUserId == "android.uid.system" && application.uid % 100000 == 1000 &&
            application.uid / 100000 == android.os.Process.myUid() / 100000 &&
            component.versionName == "preface-android9-${component.longVersionCode}" &&
            application.metaData?.getString("preface.authorization.component") == "android9-v1" &&
            (application.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP or ApplicationInfo.FLAG_DEBUGGABLE)) == 0 &&
            certificate(component) == PLATFORM && certificate(platform) == PLATFORM &&
            service.enabled && service.exported && service.applicationInfo.enabled &&
            pm.getApplicationEnabledSetting(PACKAGE) !in disabled && pm.getComponentEnabledSetting(COMPONENT) !in disabled &&
            pm.checkPermission(android.Manifest.permission.BLUETOOTH, PACKAGE) == PackageManager.PERMISSION_GRANTED &&
            pm.checkPermission(android.Manifest.permission.BLUETOOTH_ADMIN, PACKAGE) == PackageManager.PERMISSION_GRANTED &&
            pm.checkSignatures(PACKAGE, "android") == PackageManager.SIGNATURE_MATCH
    } catch (_: Exception) { false }

    private fun fail(reason: String) {
        if (closed) return
        close()
        changed(false, reason)
    }
    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        runCatching { remote?.send(Message.obtain(null, 2).apply {
            replyTo = reply; data = Bundle().apply { putString("nonce", nonce) }
        }) }
        remote = null
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
    }
    private companion object {
        const val PACKAGE = "com.android.vpndialogs"
        const val ACTION = "com.android.vpndialogs.action.CONNECT_BLUETOOTH_MUSIC"
        const val PLATFORM = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
        val COMPONENT = ComponentName(PACKAGE, "$PACKAGE.BluetoothMusicService")
    }
}
