package com.shilapi.xcertplay

import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWifiManager
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE,
    shadows = [LocalOnlyHotspotManagerTest.Radio::class, LocalOnlyHotspotManagerTest.Reservation::class])
class LocalOnlyHotspotManagerTest {
    @Test fun cancelledStartupStillClosesALateSystemReservation() { lateReservation(cancel = true) }
    @Test fun timedOutStartupStillClosesALateSystemReservation() { lateReservation(cancel = false) }

    @Test @Config(sdk = [28])
    fun android9UsesThePublicHotspotApi() {
        val radio = lateReservation(cancel = true)
        assertEquals(1, radio.standardRequests)
    }

    @Test @Config(sdk = [33])
    fun android13AlsoUsesThePublicApiWithoutCustomConfiguration() {
        val radio = lateReservation(cancel = true)
        assertEquals(1, radio.standardRequests)
    }

    @Test @Config(sdk = [28])
    fun generatedTwoPointFourHotspotHasKnownChannelAndClosesExactlyOnce() = activeHotspot(6)

    @Test @Config(sdk = [28])
    fun unknownChannelIsNotReplacedWithAnInventedFiveGhzChannel() = activeHotspot(0)

    @Test @Config(sdk = [28])
    fun systemStopInvalidatesReadinessAndReservationStillClosesOnce() = activeHotspot(6, stop = true)

    @Test @Config(sdk = [28])
    fun incompatibleHotspotModeFailsWithoutWaitingForTheStartupTimeout() {
        val context = RuntimeEnvironment.getApplication()
        val manager = LocalOnlyHotspotManager(context)
        val radio = radio(manager)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Throwable?> { runCatching { manager.start(5000) }.exceptionOrNull() }
            assertTrue(radio.requested.await(2, TimeUnit.SECONDS))
            radio.handler.post { radio.callback.onFailed(WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE) }
            shadowOf(Looper.getMainLooper()).idle()
            val failure = result.get(2, TimeUnit.SECONDS)
            assertTrue(failure is java.io.IOException)
            assertTrue(failure!!.message!!.contains("incompatible Wi-Fi mode"))
            assertThrows(java.io.IOException::class.java) { manager.validateReady() }
        } finally { manager.close(); worker.shutdownNow() }
    }

    private fun activeHotspot(channel: Int, stop: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        val ap = org.mockito.Mockito.mock(java.net.NetworkInterface::class.java)
        val address = java.net.InetAddress.getByName("192.168.43.1")
        org.mockito.Mockito.`when`(ap.name).thenReturn("ap0")
        org.mockito.Mockito.`when`(ap.isUp).thenReturn(true)
        org.mockito.Mockito.`when`(ap.isLoopback).thenReturn(false)
        org.mockito.Mockito.`when`(ap.hardwareAddress).thenReturn(byteArrayOf(2, 1, 2, 3, 4, 5))
        org.mockito.Mockito.`when`(ap.inetAddresses).thenAnswer { java.util.Collections.enumeration(listOf(address)) }
        val appeared = java.util.concurrent.atomic.AtomicBoolean(false)
        val manager = LocalOnlyHotspotManager(context, interfaceProvider = { if (appeared.get()) listOf(ap) else emptyList() })
        val radio = radio(manager)
        val worker = Executors.newSingleThreadExecutor()
        val reservation = Shadow.newInstanceOf(WifiManager.LocalOnlyHotspotReservation::class.java)
        val state = Shadow.extract<Reservation>(reservation)
        state.configuration = android.net.wifi.WifiConfiguration().apply {
            SSID = "Temporary-test"; preSharedKey = "test-password"
            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
            javaClass.getField("apChannel").setInt(this, channel)
            javaClass.getField("apBand").setInt(this, 0)
        }
        try {
            val result = worker.submit<com.shilapi.xcertplay.network.WirelessHotspotInfo> { manager.start(5000) }
            assertTrue(radio.requested.await(2, TimeUnit.SECONDS))
            appeared.set(true)
            radio.handler.post { radio.callback.onStarted(reservation) }
            shadowOf(Looper.getMainLooper()).idle()
            val info = result.get(3, TimeUnit.SECONDS)
            assertEquals("Temporary-test", info.ssid)
            assertEquals("test-password", info.passphrase)
            assertEquals("2.4 GHz", info.bandLabel)
            assertEquals(channel, info.channel)
            assertEquals(address, info.hostAddress)
            manager.validateReady()
            assertEquals(0, state.closes)
            if (stop) {
                radio.handler.post { radio.callback.onStopped() }
                shadowOf(Looper.getMainLooper()).idle()
            } else appeared.set(false)
            assertThrows(java.io.IOException::class.java) { manager.validateReady() }
            manager.close(); manager.close()
            assertEquals(1, state.closes)
            assertThrows(java.io.IOException::class.java) { manager.validateReady() }
        } finally { manager.close(); worker.shutdownNow() }
    }

    private fun radio(manager: LocalOnlyHotspotManager): Radio =
        shadowOf(ReflectionHelpers.getField<WifiManager>(manager, "wifiManager")) as Radio

    private fun lateReservation(cancel: Boolean): Radio {
        val context = RuntimeEnvironment.getApplication()
        val manager = LocalOnlyHotspotManager(context)
        val radio = radio(manager)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { runCatching { manager.start(if (cancel) 5000 else 100) }.isFailure }
            assertTrue(radio.requested.await(2, TimeUnit.SECONDS))
            if (cancel) manager.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            // The framework completes after cancellation/timeout. Callback delivery must not
            // have died with a private HandlerThread, leaving the system AP running forever.
            assertSame(Looper.getMainLooper(), radio.handler.looper)
            val reservation = Shadow.newInstanceOf(WifiManager.LocalOnlyHotspotReservation::class.java)
            radio.handler.post { radio.callback.onStarted(reservation) }
            shadowOf(Looper.getMainLooper()).idle()
            val state = Shadow.extract<Reservation>(reservation)
            assertEquals(1, state.closes)
        } finally { manager.close(); worker.shutdownNow() }
        return radio
    }

    @Implements(WifiManager::class)
    class Radio : ShadowWifiManager() {
        val requested = CountDownLatch(1)
        lateinit var callback: WifiManager.LocalOnlyHotspotCallback
        lateinit var handler: Handler
        var standardRequests = 0
        @Implementation fun startLocalOnlyHotspot(value: WifiManager.LocalOnlyHotspotCallback, delivery: Handler) {
            standardRequests++
            callback = value; handler = delivery; requested.countDown()
        }

    }

    @Implements(WifiManager.LocalOnlyHotspotReservation::class)
    class Reservation {
        var closes = 0
        var configuration: android.net.wifi.WifiConfiguration? = null
        @Implementation fun getWifiConfiguration(): android.net.wifi.WifiConfiguration? = configuration
        @Implementation fun close() { closes++ }
    }
}
