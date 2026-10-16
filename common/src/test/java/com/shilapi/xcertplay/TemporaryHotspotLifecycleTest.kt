package com.shilapi.xcertplay

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test fun interfaceMustStillBeReadyAfterRadioObservation() {
        var fixture: Fixture? = null
        val value = Fixture(channel = 0) { message ->
            if (message.contains("radio observation timed out")) fixture!!.interfaces = emptyList()
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
