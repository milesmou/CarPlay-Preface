package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PrefaceInstrumentNavigationTest {
    private class Backend : PrefaceInstrumentBackend {
        val sent = CountDownLatch(1)
        val released = CountDownLatch(1)
        val publishes = AtomicInteger()
        val clears = AtomicInteger()
        override fun connect() {}
        override fun ready() = true
        override fun available() = true
        override fun publish(guidance: PrefaceInstrumentGuidance) { publishes.incrementAndGet(); sent.countDown() }
        override fun clear() { clears.incrementAndGet() }
        override fun close() { released.countDown() }
    }
    private fun route(output: PrefaceInstrumentNavigation, token: Any) {
        output.accept(Iap2Messages.buildRaw(0x5202) { u16(1, 0); u8(3, 1) }, token)
        output.accept(Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16(0x0d, 0); u32(0x0a, 100) }, token)
    }

    @Test fun replacingAControllerClearsOldOutputAndOldCloseCannotClearTheNewOne() {
        val oldBackend = Backend()
        val newBackend = Backend()
        val old = PrefaceInstrumentNavigation({ oldBackend }, {})
        val new = PrefaceInstrumentNavigation({ newBackend }, {})
        val oldToken = Any()
        val newToken = Any()
        try {
            old.start(oldToken); route(old, oldToken)
            assertTrue(oldBackend.sent.await(3, TimeUnit.SECONDS))
            new.start(newToken)
            old.close()
            route(old, oldToken)
            route(new, oldToken) // wrong controller/session token
            route(new, newToken)
            assertTrue(newBackend.sent.await(3, TimeUnit.SECONDS))
            assertEquals(1, oldBackend.clears.get())
            assertEquals(0, newBackend.clears.get())
            assertEquals(1, oldBackend.publishes.get())
            new.end(oldToken) // a delayed teardown cannot invalidate the active replacement
            assertEquals(0, newBackend.clears.get())
        } finally {
            old.close(); new.close()
            assertTrue(newBackend.released.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun anEndedSessionCannotPublishQueuedLateFrames() {
        val backend = Backend()
        val started = CountDownLatch(1)
        val output = PrefaceInstrumentNavigation({ backend }, { if ("enabled for current" in it) started.countDown() })
        val token = Any()
        try {
            output.start(token)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            output.end(token)
            route(output, token)
            assertTrue(backend.released.await(3, TimeUnit.SECONDS))
            assertEquals(0, backend.publishes.get())
            assertEquals(0, backend.clears.get())
        } finally { output.close() }
    }
}
