package com.shilapi.xcertplay.vehicle

import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
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

/** Android 9 fallback for a hidden A2DP-sink connect method, scoped to one pending request. */
internal class PlatformBluetoothMusicConnector(
    context: Context,
    private val peer: String,
    private val completed: (Boolean, String) -> Unit,
) : Closeable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val nonce = UUID.randomUUID().toString()
    private var bound = false
    private var remote: Messenger? = null
    private var closed = false
    private val timeout = Runnable { finish(false, "platform music request timed out") }
    private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
        val expectedUid = runCatching { app.packageManager.getApplicationInfo(PACKAGE, 0).uid }.getOrNull()
        if (!closed && message.what == REPLY && message.sendingUid == expectedUid && message.data.getString("nonce") == nonce) {
            finish(message.data.getBoolean("accepted"), message.data.getString("reason") ?: "platform reply")
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) return
            if (name != COMPONENT || !available()) { finish(false, "platform music component changed"); return }
            remote = Messenger(service)
            try {
                remote?.send(Message.obtain(null, CONNECT).apply {
                    replyTo = reply
                    data = Bundle().apply { putString("nonce", nonce); putString("address", peer) }
                })
            } catch (_: Exception) { finish(false, "platform music service unavailable") }
        }
        override fun onServiceDisconnected(name: ComponentName) { finish(false, "platform music service disconnected") }
        override fun onBindingDied(name: ComponentName) { finish(false, "platform music binding ended") }
        override fun onNullBinding(name: ComponentName) { finish(false, "platform music binding rejected") }
    }

    fun start() {
        if (closed || bound) return
        if (!BluetoothAdapter.checkBluetoothAddress(peer) || !available()) {
            finish(false, "compatible authorization component 6 is required")
            return
        }
        try {
            bound = app.bindService(Intent(ACTION).setComponent(COMPONENT), connection, Context.BIND_AUTO_CREATE)
            if (bound) main.postDelayed(timeout, 5_000L)
            else finish(false, "platform music service could not bind")
        } catch (_: Exception) { finish(false, "platform music service could not bind") }
    }

    @Suppress("DEPRECATION")
    private fun available(): Boolean = try {
        val pm = app.packageManager
        val component = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES)
        val platform = pm.getPackageInfo("android", PackageManager.GET_SIGNATURES)
        val application = pm.getApplicationInfo(PACKAGE, PackageManager.GET_META_DATA)
        val service = pm.getServiceInfo(COMPONENT, 0)
        Build.VERSION.SDK_INT == 28 && component.longVersionCode >= 6 &&
            component.versionName == "preface-android9-${component.longVersionCode}" &&
            application.metaData?.getString("preface.authorization.component") == "android9-v1" &&
            (application.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP or ApplicationInfo.FLAG_DEBUGGABLE)) == 0 &&
            certificate(component) == PLATFORM_CERT && certificate(platform) == PLATFORM_CERT &&
            service.enabled && service.exported && service.applicationInfo.enabled &&
            pm.checkSignatures(PACKAGE, "android") == PackageManager.SIGNATURE_MATCH
    } catch (_: Exception) { false }

    private fun finish(accepted: Boolean, reason: String) {
        if (closed) return
        close()
        completed(accepted, reason)
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        runCatching {
            remote?.send(Message.obtain(null, CANCEL).apply {
                replyTo = reply
                data = Bundle().apply { putString("nonce", nonce) }
            })
        }
        remote = null
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
    }

    private companion object {
        const val PACKAGE = "com.android.vpndialogs"
        const val ACTION = "com.android.vpndialogs.action.CONNECT_BLUETOOTH_MUSIC"
        val COMPONENT = ComponentName(PACKAGE, "$PACKAGE.BluetoothMusicService")
        const val PLATFORM_CERT = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
        const val CONNECT = 1
        const val CANCEL = 2
        const val REPLY = 3
        @Suppress("DEPRECATION")
        fun certificate(info: PackageInfo): String? = info.signatures?.singleOrNull()?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }
    }
}
