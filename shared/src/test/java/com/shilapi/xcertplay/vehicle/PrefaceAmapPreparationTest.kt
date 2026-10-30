package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.preface.carplay.instrument.Preface95PreparationProtocol as Wire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrefaceAmapPreparationTest {
    private class BindingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection
        lateinit var intent: Intent
        var binds = 0
        var unbinds = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            intent = service; connection = conn; binds++
            assertEquals(Context.BIND_AUTO_CREATE, flags)
            return true
        }
        override fun unbindService(conn: ServiceConnection) { assertSame(connection, conn); unbinds++ }
        fun connected(binder: IBinder) = connection.onServiceConnected(intent.component!!, binder)
    }

    private class Host : Binder() {
        val opened = CountDownLatch(1)
        val polled = CountDownLatch(1)
        val released = CountDownLatch(1)
        val sequences = CopyOnWriteArrayList<Long>()
        val sessions = CopyOnWriteArrayList<Long>()
        @Volatile var state = Wire.WAITING
        @Volatile var version = Wire.VERSION
        var opens = 0
        init { attachInterface(null, Wire.DESCRIPTOR) }
        override fun onTransact(code: Int, request: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, request, reply, flags)
            request.enforceInterface(Wire.DESCRIPTOR)
            assertEquals(Wire.VERSION, request.readInt())
            assertNotNull(request.readStrongBinder())
            sessions += request.readLong(); sequences += request.readLong()
            assertEquals(0, request.dataAvail())
            when (code) {
                Wire.OPEN -> { opens++; opened.countDown() }
                Wire.POLL -> polled.countDown()
                Wire.CLOSE -> released.countDown()
                else -> fail("Unexpected command")
            }
            reply!!.writeNoException(); reply.writeInt(version); reply.writeInt(state)
            return true
        }
    }

    private fun await(latch: CountDownLatch) = assertTrue("background transaction timed out", latch.await(3, TimeUnit.SECONDS))

    @Test fun bindingAndEngineStartupCannotSubstituteForActualBufferConfirmation() {
        val context = BindingContext()
        val confirmed = CountDownLatch(1)
        val client = PrefaceAmapPreparation(context, { true }, {
            if (it.contains("idle buffer confirmed")) confirmed.countDown()
        }, { true })
        val host = Host()
        try {
            client.start(); assertFalse(client.ready())
            assertEquals(Wire.ACTION, context.intent.action)
            assertEquals(ComponentName(Wire.HOST_PACKAGE, Wire.HOST_SERVICE), context.intent.component)
            context.connected(host); await(host.polled)
            assertFalse(client.ready())
            host.state = Wire.BUFFER_CONFIRMED; await(confirmed)
            assertTrue(client.ready())
            assertEquals(1, host.opens)
            assertTrue(host.sessions.all { it > 0 && it == host.sessions.first() })
            assertEquals(host.sequences.sorted().distinct(), host.sequences.toList())
        } finally { client.close(); await(host.released); shadowOf(Looper.getMainLooper()).idle() }
        assertFalse(client.ready()); assertEquals(1, context.unbinds)
    }

    @Test fun originalMapBinderUsesExistingPreparationWithoutSendingCustomCommands() {
        val context = BindingContext()
        val fallback = CountDownLatch(1)
        val client = PrefaceAmapPreparation(context, { true }, {
            if (it.contains("original-map-protocol")) fallback.countDown()
        }, { true })
        val ordinary = object : Binder() {
            init { attachInterface(null, "original.map.json") }
            override fun onTransact(code: Int, request: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != INTERFACE_TRANSACTION) fail("Custom transaction reached OEM Binder")
                return super.onTransact(code, request, reply, flags)
            }
        }
        try {
            client.start(); context.connected(ordinary); await(fallback)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(client.ready()); assertEquals(1, context.unbinds)
        } finally { client.close(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun unknownMapIdentityIsPreservedWithoutBinding() {
        val context = BindingContext()
        val client = PrefaceAmapPreparation(context, { true }, {}, { false })
        try { client.start(); assertEquals(0, context.binds); assertTrue(client.ready()) }
        finally { client.close() }
    }

    @Test fun cancellationBeforeConnectionRejectsLateCallbacks() {
        val context = BindingContext()
        val client = PrefaceAmapPreparation(context, { true }, {}, { true })
        val host = Host()
        client.start(); client.close(); context.connected(host)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(host.opened.await(100, TimeUnit.MILLISECONDS))
        assertFalse(client.ready()); assertEquals(1, context.unbinds)
    }

    @Test fun sessionInvalidationReleasesTheRemoteLeaseAndBinding() {
        val context = BindingContext()
        val current = AtomicBoolean(true)
        val client = PrefaceAmapPreparation(context, current::get, {}, { true })
        val host = Host()
        try {
            client.start(); context.connected(host); await(host.polled)
            current.set(false); await(host.released)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(client.ready()); assertEquals(1, context.unbinds)
        } finally { client.close() }
    }

    @Test fun disconnectFailsThisSessionAndDoesNotRebindAutomatically() {
        val context = BindingContext()
        val client = PrefaceAmapPreparation(context, { true }, {}, { true })
        val host = Host()
        try {
            client.start(); context.connected(host); await(host.polled)
            context.connection.onServiceDisconnected(context.intent.component!!)
            assertThrows(IllegalStateException::class.java) { client.ready() }
            await(host.released); client.start()
            shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, context.binds)
        } finally { client.close() }
    }

    @Test fun incompatibleProtocolFailsWithoutTreatingTheServiceAsReady() {
        val context = BindingContext()
        val failed = CountDownLatch(1)
        val client = PrefaceAmapPreparation(context, { true }, {
            if (it.contains("handshake-failed")) failed.countDown()
        }, { true })
        val host = Host().apply { version = 2 }
        try {
            client.start(); context.connected(host); await(failed)
            assertThrows(IllegalStateException::class.java) { client.ready() }
            await(host.released)
        } finally { client.close(); shadowOf(Looper.getMainLooper()).idle() }
    }
}
