package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.impl.JmDNSImpl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessTeardownTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(1920, 720))
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")

    private fun controller(lines: MutableList<String>): CarPlayController = CarPlayController(
        object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
        },
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
        config, identity, PairingStore(),
        object : AirPlaySessionListener {
            override fun onDebugLog(message: String) { lines.add(message) }
        }, object : AirPlayMediaHandler {}, {},
    )

    private fun bonjour(dns: JmDNS, lines: MutableList<String>): CarPlayBonjour =
        CarPlayBonjour(app, config, identity, useInterfaceMdns = true,
            onCloseDiagnostic = { lines.add(it) }).also {
            ReflectionHelpers.getField<MutableList<JmDNS>>(it, "interfaceMdns").add(dns)
        }

    @Test fun slowDnsFinalizerCannotBlockHotspotReleaseAndControllerCompletionAfterSocketCloses() {
        val lines = CopyOnWriteArrayList<String>()
        val controller = controller(lines)
        val socket = MulticastSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val oldAddress = socket.localSocketAddress
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cancelling = AtomicBoolean()
        val dns = mock(JmDNSImpl::class.java)
        `when`(dns.socket).thenReturn(socket)
        `when`(dns.isCanceling).thenAnswer { cancelling.get() }
        doAnswer {
            cancelling.set(true)
            entered.countDown()
            try { release.await() } finally { finished.countDown() }
            null
        }.`when`(dns).close()
        val hotspotClosed = AtomicBoolean()
        ReflectionHelpers.setField(controller, "bonjour", bonjour(dns, lines))
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(controller, "hotspotLease"), "currentManager", object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("not started")
            override fun close() { hotspotClosed.set(true) }
        })
        try {
            controller.close()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue("DNS finalizer must not prevent controller retirement", controller.awaitClosed(4_000))
            assertTrue(socket.isClosed)
            DatagramSocket(null).use { replacement -> replacement.bind(oldAddress) }
            assertTrue(hotspotClosed.get())
            assertEquals("Finalizer is deliberately still blocked", 1L, finished.count)
            assertTrue(lines.any { it.contains("transportReleased=true finalizerCompleted=false") })
            assertTrue(lines.any { it.contains("teardown resource=Bonjour state=begin") })
            controller.close()
            verify(dns, times(1)).close()
        } finally {
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            socket.close()
            controller.close()
            controller.awaitClosed(4_000)
        }
    }

    @Test fun unconfirmedDnsTransportRetainsTheRestartBarrierUntilCloseActuallyFinishes() {
        val lines = CopyOnWriteArrayList<String>()
        val controller = controller(lines)
        val socket = MulticastSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val release = CountDownLatch(1)
        val dns = mock(JmDNSImpl::class.java)
        `when`(dns.socket).thenReturn(socket)
        doAnswer { release.await(); socket.close(); null }.`when`(dns).close()
        ReflectionHelpers.setField(controller, "bonjour", bonjour(dns, lines))
        try {
            controller.close()
            assertFalse(controller.awaitClosed(1_500))
            assertFalse("No cancellation observed: do not close a live registry underneath recovery", socket.isClosed)
            assertTrue(lines.any { it.contains("transportReleased=false waitingForFinalizer=true") })
            release.countDown()
            assertTrue(controller.awaitClosed(4_000))
            assertTrue(socket.isClosed)
        } finally {
            release.countDown()
            socket.close()
            controller.close()
            controller.awaitClosed(4_000)
        }
    }

    @Test fun failedFinalizerStillReleasesItsCancelledTransportAndReportsTheFailure() {
        val lines = CopyOnWriteArrayList<String>()
        val socket = MulticastSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val cancelling = AtomicBoolean()
        val dns = mock(JmDNSImpl::class.java)
        `when`(dns.socket).thenReturn(socket)
        `when`(dns.isCanceling).thenAnswer { cancelling.get() }
        doAnswer { cancelling.set(true); throw IOException("test finalizer failure") }.`when`(dns).close()
        val bonjour = bonjour(dns, lines)
        try {
            bonjour.close()
            assertTrue(socket.isClosed)
            assertTrue(lines.any { it.contains("finalizerFailure=IOException") })
            bonjour.close()
            verify(dns, times(1)).close()
        } finally {
            socket.close()
        }
    }
}
