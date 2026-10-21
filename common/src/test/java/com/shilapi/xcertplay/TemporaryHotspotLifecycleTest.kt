package com.shilapi.xcertplay

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TemporaryHotspotLifecycleTest {
    private class Fixture(autoCallback: Boolean = true, channel: Int = 6, log: (String) -> Unit = {}) {
        val requested = CountDownLatch(1)
        val wifi = mock(WifiManager::class.java)
        val reservation = mock(WifiManager.LocalOnlyHotspotReservation::class.java)
        val network = mock(NetworkInterface::class.java)
        @Volatile var interfaces = emptyList<NetworkInterface>()
        lateinit var callback: WifiManager.LocalOnlyHotspotCallback
        val manager = LocalOnlyHotspotManager(RuntimeEnvironment.getApplication(), log, { interfaces })
        val worker = Executors.newSingleThreadExecutor()

        init {
            `when`(network.name).thenReturn("ap0")
            `when`(network.index).thenReturn(7)
            `when`(network.isUp).thenReturn(true)
            `when`(network.hardwareAddress).thenReturn(byteArrayOf(2, 17, 34, 51, 68, 85))
            `when`(network.inetAddresses).thenAnswer { Collections.enumeration(listOf(InetAddress.getByName("192.168.43.1"))) }
            val config = WifiConfiguration().apply {
                SSID = "temporary-test"; preSharedKey = "test12345"
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            ReflectionHelpers.setField(config, "apChannel", channel)
            `when`(reservation.wifiConfiguration).thenReturn(config)
            `when`(wifi.createMulticastLock(anyString())).thenReturn(mock(WifiManager.MulticastLock::class.java))
            doAnswer {
                callback = it.getArgument(0)
                requested.countDown()
                if (autoCallback) { interfaces = listOf(network); callback.onStarted(reservation) }
                null
            }.`when`(wifi).startLocalOnlyHotspot(any(WifiManager.LocalOnlyHotspotCallback::class.java), any(Handler::class.java))
            ReflectionHelpers.setField(manager, "wifiManager", wifi)
        }

        fun start() = worker.submit<com.shilapi.xcertplay.network.WirelessHotspotInfo> { manager.start(6_000) }
        fun close() { manager.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test fun interfaceMustStillBeReadyBeforePublishingHotspot() {
        var fixture: Fixture? = null
        val value = Fixture(channel = 0) { message ->
            if (message.contains("advertising automatic channel 0")) fixture!!.interfaces = emptyList()
        }
        fixture = value
        try {
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { value.start().get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause!!.message!!.contains("interface disappeared"))
            verify(value.reservation, times(1)).close()
            val reused = assertThrows(java.util.concurrent.ExecutionException::class.java) { value.start().get(1, TimeUnit.SECONDS) }
            assertTrue(reused.cause is IllegalStateException)
        } finally { value.close() }
    }

    @Test fun unknownChannelDoesNotWaitForBackgroundRadioObservation() {
        val observing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val value = Fixture(channel = 0) { message ->
            if (message.contains("background radio observation starting")) {
                observing.countDown()
                release.await()
            }
        }
        try {
            val started = value.start()
            assertTrue(observing.await(2, TimeUnit.SECONDS))
            // The diagnostic reader is still blocked, but the connection already has its
            // system credentials and validated interface. It must not await radio readback.
            val info = started.get(1, TimeUnit.SECONDS)
            assertEquals("temporary-test", info.ssid)
            assertEquals("test12345", info.passphrase)
            assertEquals(0, info.channel)
            assertEquals("2.4 GHz", info.bandLabel)
            assertNull(info.frequencyMHz)
            value.manager.validateReady()
            verify(value.reservation, never()).close()
        } finally { release.countDown(); value.close() }
    }

    @Test fun backgroundRadioFailureDoesNotInvalidateTheReadyHotspot() {
        val failed = CountDownLatch(1)
        val value = Fixture(channel = 0) { message ->
            if (message.contains("background radio observation starting")) throw IOException("test reader unavailable")
            if (message.contains("background radio observation failed")) failed.countDown()
        }
        try {
            val info = value.start().get(2, TimeUnit.SECONDS)
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertEquals(0, info.channel)
            assertNull(info.frequencyMHz)
            value.manager.validateReady()
            verify(value.reservation, never()).close()
        } finally { value.close() }
    }

    @Test fun closingHotspotCancelsBackgroundObservation() = cancelledBackgroundObservation(systemStop = false)

    @Test fun systemStopCancelsBackgroundObservation() = cancelledBackgroundObservation(systemStop = true)

    private fun cancelledBackgroundObservation(systemStop: Boolean) {
        val observing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val radioThread = AtomicReference<Thread>()
        val diagnostics = CopyOnWriteArrayList<String>()
        val value = Fixture(channel = 0) { message ->
            diagnostics.add(message)
            if (message.contains("background radio observation starting")) {
                radioThread.set(Thread.currentThread())
                observing.countDown()
                release.await()
            }
        }
        try {
            val started = value.start()
            assertTrue(observing.await(2, TimeUnit.SECONDS))
            assertEquals(0, started.get(1, TimeUnit.SECONDS).channel)
            if (systemStop) value.callback.onStopped() else value.manager.close()
            radioThread.get().join(2_000)
            assertFalse("radio observation must stop with its reservation", radioThread.get().isAlive)
            assertFalse(diagnostics.any { it.contains("observation failed") || it.contains("observation timed out") || it.contains("observed radio frequency=") })
            assertThrows(IOException::class.java) { value.manager.validateReady() }
        } finally { release.countDown(); value.close() }
        verify(value.reservation, times(1)).close()
    }

    @Test fun stoppedCallbackAndReplacementInterfaceInvalidateTheReservation() {
        val value = Fixture()
        try {
            assertEquals("ap0", value.start().get(3, TimeUnit.SECONDS).interfaceName)
            value.manager.validateReady()
            `when`(value.network.index).thenReturn(8)
            assertThrows(IOException::class.java) { value.manager.validateReady() }
            `when`(value.network.index).thenReturn(7)
            value.callback.onStopped()
            val failure = assertThrows(IOException::class.java) { value.manager.validateReady() }
            assertTrue(failure.message!!.contains("stopped by the system"))
        } finally { value.close() }
        verify(value.reservation, times(1)).close()
    }

    @Test fun replacementWaitsUntilLateStartedReservationHasBeenReleased() {
        val old = Fixture(autoCallback = false)
        val next = Fixture()
        try {
            val oldStart = old.start()
            assertTrue(old.requested.await(2, TimeUnit.SECONDS))
            old.manager.close()
            assertThrows(java.util.concurrent.ExecutionException::class.java) { oldStart.get(2, TimeUnit.SECONDS) }
            val nextStart = next.start()
            assertFalse(next.requested.await(100, TimeUnit.MILLISECONDS))
            old.callback.onStarted(old.reservation)
            assertEquals("ap0", nextStart.get(3, TimeUnit.SECONDS).interfaceName)
            verify(old.reservation, times(1)).close()
            next.manager.validateReady()
            old.callback.onStopped() // a late old callback cannot invalidate the new reservation
            next.manager.validateReady()
        } finally { old.close(); next.close() }
    }

    @Test fun failedPlatformRequestReleasesTheGateForTheNextManager() {
        val failed = Fixture()
        val next = Fixture()
        try {
            doThrow(SecurityException("permission denied")).`when`(failed.wifi)
                .startLocalOnlyHotspot(any(WifiManager.LocalOnlyHotspotCallback::class.java), any(Handler::class.java))
            val error = assertThrows(java.util.concurrent.ExecutionException::class.java) { failed.start().get(2, TimeUnit.SECONDS) }
            assertTrue(error.cause is SecurityException)
            assertEquals("ap0", next.start().get(3, TimeUnit.SECONDS).interfaceName)
        } finally { failed.close(); next.close() }
    }
}
