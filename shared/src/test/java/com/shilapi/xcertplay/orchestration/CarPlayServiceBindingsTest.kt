package com.shilapi.xcertplay.orchestration

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import com.shilapi.xcertplay.network.CarPlaySocketService
import com.shilapi.xcertplay.network.CarPlayVpnService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayServiceBindingsTest {
    private class BindingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val requested = CountDownLatch(1)
        lateinit var connection: ServiceConnection
        var unbinds = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            this.connection = connection
            requested.countDown()
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }

    @Test fun wirelessCancellationWakesWaiterAndDoesNotAdoptLateService() {
        val context = BindingContext()
        val bindings = CarPlayServiceBindings(context, { false }, {}, {}, {})
        val waiting = FutureTask { bindings.awaitSocketService() }
        val worker = Thread(waiting, "test-wireless-binding")
        worker.start()
        try {
            assertTrue(context.requested.await(2, TimeUnit.SECONDS))
            bindings.unbindSocketService()
            assertNull(waiting.get(2, TimeUnit.SECONDS))
            val service = Robolectric.buildService(CarPlaySocketService::class.java).get()
            context.connection.onServiceConnected(
                ComponentName(context, CarPlaySocketService::class.java), service.onBind(Intent()),
            )
            assertNull(bindings.socketService)
            bindings.unbindSocketService()
            assertEquals(1, context.unbinds)
        } finally {
            bindings.unbindSocketService()
            worker.interrupt()
            worker.join(2_000)
        }
    }

    @Test fun closedControllerNeverBindsEitherTransport() {
        val context = BindingContext()
        val bindings = CarPlayServiceBindings(context, { true }, {}, {}, {})
        assertNull(bindings.awaitVpnService())
        assertNull(bindings.awaitSocketService())
        assertEquals(1L, context.requested.count)
    }

    @Test fun wiredCancellationAlsoRejectsLateServiceWithoutTakingNcmOwnership() {
        val context = BindingContext()
        val bindings = CarPlayServiceBindings(context, { false }, {}, {}, {})
        val waiting = FutureTask { bindings.awaitVpnService() }
        val worker = Thread(waiting, "test-wired-binding")
        worker.start()
        try {
            assertTrue(context.requested.await(2, TimeUnit.SECONDS))
            bindings.unbindVpnService()
            assertNull(waiting.get(2, TimeUnit.SECONDS))
            val service = Robolectric.buildService(CarPlayVpnService::class.java).get()
            context.connection.onServiceConnected(
                ComponentName(context, CarPlayVpnService::class.java), service.onBind(Intent())!!,
            )
            assertNull(bindings.vpnService)
            var attached = false
            assertThrows(java.io.IOException::class.java) {
                bindings.withVpnService(service) { attached = true }
            }
            assertFalse(attached)
        } finally {
            bindings.unbindVpnService()
            worker.interrupt()
            worker.join(2_000)
        }
    }
}
