package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import java.io.Closeable

/** Ask the native Bluetooth player to prepare its own route once, without sending PLAY. */
internal class NativeBluetoothAudioPreparation(
    context: Context,
    private val eligible: () -> Boolean,
    private val report: (String) -> Unit,
    private val available: () -> Boolean = { available(context.applicationContext) },
    private val createBrowser: (Connection) -> Browser = { AndroidBrowser(context.applicationContext, it) },
) : Closeable {
    internal interface Connection {
        fun connected()
        fun failed()
    }
    internal interface Browser : Closeable {
        fun connect()
        fun prepare()
    }

    private val main = Handler(Looper.getMainLooper())
    private var browser: Browser? = null
    private var started = false
    private var closed = false
    private val timeout = Runnable { finish("timed_out") }

    fun start() {
        if (closed || started) return
        started = true
        if (!eligible() || !available()) { finish("unavailable_or_ineligible"); return }
        try {
            browser = createBrowser(object : Connection {
                override fun connected() {
                    if (closed) return
                    if (!eligible() || !available()) { finish("eligibility_changed"); return }
                    try {
                        // Android 9 A2dpMediaBrowserService.onPrepare requests focus inside the
                        // Bluetooth process. This app never owns that focus or sends media keys.
                        browser?.prepare()
                        finish("prepare_sent focusConfirmed=false playbackConfirmed=false")
                    } catch (_: Exception) { finish("prepare_failed") }
                }
                override fun failed() { if (!closed) finish("connection_failed") }
            })
            main.postDelayed(timeout, 4_000L)
            browser?.connect()
        } catch (_: Exception) { finish("connection_failed") }
    }

    private fun finish(result: String) {
        if (closed) return
        close()
        report("Audio: native Bluetooth preparation result=$result")
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        val previous = browser
        browser = null
        runCatching { previous?.close() }
    }

    private class AndroidBrowser(context: Context, callback: Connection) : Browser {
        private val app = context.applicationContext
        private val browser = MediaBrowser(app, COMPONENT, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() = callback.connected()
            override fun onConnectionFailed() = callback.failed()
            override fun onConnectionSuspended() = callback.failed()
        }, null)
        override fun connect() = browser.connect()
        override fun prepare() = MediaController(app, browser.sessionToken).transportControls.prepare()
        override fun close() = browser.disconnect()
    }

    internal companion object {
        val COMPONENT = ComponentName("com.android.bluetooth",
            "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService")

        fun available(context: Context): Boolean = runCatching {
            val pm = context.packageManager
            val service = pm.getServiceInfo(COMPONENT, 0)
            val app = service.applicationInfo
            val state = pm.getComponentEnabledSetting(COMPONENT)
            service.enabled && service.exported && app.enabled &&
                state !in setOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) &&
                app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
                pm.checkSignatures(COMPONENT.packageName, "android") == PackageManager.SIGNATURE_MATCH &&
                (service.permission.isNullOrEmpty() ||
                    context.checkSelfPermission(service.permission) == PackageManager.PERMISSION_GRANTED)
        }.getOrDefault(false)
    }
}
