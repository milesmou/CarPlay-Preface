package com.shilapi.xcertplay.orchestration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.shilapi.xcertplay.network.CarPlaySocketService
import com.shilapi.xcertplay.network.CarPlayVpnService
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owns per-controller Android service bindings; transport teardown still owns detach/stop order. */
internal class CarPlayServiceBindings(
    private val appContext: Context,
    private val isClosed: () -> Boolean,
    private val connectionDiagnostic: (String) -> Unit,
    private val onVpnDisconnected: () -> Unit,
    private val onSocketDisconnected: () -> Unit,
) {
    private val closed get() = isClosed()
    @Volatile var socketService: CarPlaySocketService? = null
        private set
    @Volatile private var socketServiceBound = false
    @Volatile var vpnService: CarPlayVpnService? = null
        private set
    private var vpnServiceBound = false
    private val vpnServiceLatch = CountDownLatch(1)
    private val vpnServiceLock = Any()
    private val vpnServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(vpnServiceLock) {
                if (closed || !vpnServiceBound) return
                vpnService = (binder as CarPlayVpnService.LocalBinder).service
                vpnServiceLatch.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(vpnServiceLock) { vpnService = null }
            onVpnDisconnected()
        }
    }
    private val socketServiceLatch = CountDownLatch(1)
    private val socketServiceLock = Any()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(socketServiceLock) {
                if (closed || !socketServiceBound) return
                socketService = (binder as CarPlaySocketService.LocalBinder).service
                socketServiceLatch.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(socketServiceLock) { socketService = null }
            onSocketDisconnected()
        }
    }

    fun awaitVpnService(): CarPlayVpnService? {
        synchronized(vpnServiceLock) {
            if (closed) return null
            vpnService?.let { return it }
            if (!vpnServiceBound) {
                vpnServiceBound = true
                try {
                    if (!appContext.bindService(Intent(appContext, CarPlayVpnService::class.java),
                            vpnServiceConnection, Context.BIND_AUTO_CREATE)) {
                        vpnServiceBound = false
                        vpnServiceLatch.countDown()
                    }
                } catch (error: RuntimeException) {
                    vpnServiceBound = false
                    vpnServiceLatch.countDown()
                    connectionDiagnostic("VPN service bind failed failureClass=${error.javaClass.simpleName}")
                }
            }
        }
        return try {
            if (vpnServiceLatch.await(SOCKET_SERVICE_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) && !closed)
                vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    fun unbindVpnService(): CarPlayVpnService? = synchronized(vpnServiceLock) {
        val service = vpnService
        vpnService = null
        if (vpnServiceBound) {
            vpnServiceBound = false
            runCatching { appContext.unbindService(vpnServiceConnection) }
        }
        vpnServiceLatch.countDown() // cancellation also wakes a worker waiting for onServiceConnected
        service
    }

    fun awaitSocketService(): CarPlaySocketService? {
        if (closed) return null
        socketService?.let { return it }
        bindSocketService()
        return try {
            if (socketServiceLatch.await(SOCKET_SERVICE_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) && !closed) socketService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindSocketService() = synchronized(socketServiceLock) {
        if (closed || socketServiceBound) return@synchronized
        socketServiceBound = true
        try {
            val intent = Intent(appContext, CarPlaySocketService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                socketServiceBound = false
                socketServiceLatch.countDown()
            }
        } catch (_: Throwable) {
            socketServiceBound = false
            socketServiceLatch.countDown()
        }
    }

    fun unbindSocketService() = synchronized(socketServiceLock) {
        if (socketServiceBound) {
            socketServiceBound = false
            runCatching { appContext.unbindService(serviceConnection) }
        }
        socketService = null
        socketServiceLatch.countDown()
    }

    /** Attach and unbind share the lock so an obsolete service cannot acquire NCM resources. */
    fun <T> withVpnService(service: CarPlayVpnService, attach: () -> T): T = synchronized(vpnServiceLock) {
        if (closed || vpnService !== service) throw IOException("CarPlay connection closed")
        attach()
    }

    private companion object {
        const val SOCKET_SERVICE_CONNECT_TIMEOUT_MILLIS = 10_000L
    }
}
