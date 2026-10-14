package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TemporaryHotspotRetryTest {
    private fun info() = WirelessHotspotInfo("temporary-test", "test12345", Iap2WirelessSecurity.WPA_WPA2,
        0, null, null, "ap0", InetAddress.getByName("192.0.2.1"), "2.4 GHz", WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT)

    private fun controller(): CarPlayController = CarPlayController(RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
        AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 720)),
        AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {}).also {
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(adapter.isEnabled).thenReturn(true)
        ReflectionHelpers.setField(it, "bluetoothAdapter", adapter)
        val phase = it.javaClass.getDeclaredField("phase").apply { isAccessible = true }
        phase.set(it, phase.type.enumConstants!!.first { value -> (value as Enum<*>).name == "WIRELESS" })
    }

    private fun start(controller: CarPlayController): WirelessHotspotInfo =
        controller.javaClass.getDeclaredMethod("startWirelessHotspot", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, generation(controller).get()) as WirelessHotspotInfo

    private fun cleanupAttempt(controller: CarPlayController, generation: Int = generation(controller).get()) {
        controller.javaClass.getDeclaredMethod("closeFailedWirelessAttempt", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, generation)
    }

    private fun generation(controller: CarPlayController): AtomicInteger = ReflectionHelpers.getField(controller, "wirelessGeneration")

    @Test fun repeatedStartupFailuresKeepOneReservationAndTheExactCredentialsUntilDisconnect() {
        val controller = controller()
        val snapshot = info()
        try {
            mockConstruction(LocalOnlyHotspotManager::class.java) { manager, _ ->
                `when`(manager.start(anyLong())).thenReturn(snapshot)
            }.use { created ->
                repeat(4) {
                    assertSame(snapshot, start(controller))
                    cleanupAttempt(controller)
                }
                val manager = created.constructed().single()
                verify(manager, times(1)).start(anyLong())
                verify(manager, times(3)).validateReady()
                verify(manager, never()).close()
                controller.close()
                assertTrue(controller.awaitClosed(2_000))
                verify(manager, times(1)).close()
            }
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun onlyAnInvalidRetainedReservationIsReplaced() {
        val controller = controller()
        try {
            mockConstruction(LocalOnlyHotspotManager::class.java) { manager, _ ->
                `when`(manager.start(anyLong())).thenReturn(info())
            }.use { created ->
                val firstInfo = start(controller)
                val first = created.constructed().single()
                cleanupAttempt(controller)
                doAnswer { throw IOException("hotspot stopped") }.`when`(first).validateReady()
                val replacement = start(controller)
                assertNotSame(firstInfo, replacement)
                assertEquals(2, created.constructed().size)
                verify(first, times(1)).close()
                controller.close()
                assertTrue(controller.awaitClosed(2_000))
                verify(first, times(1)).close()
                verify(created.constructed()[1], times(1)).close()
            }
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun retryKeepsControllerMfiAndHotspotAndRejectsDuplicateOldCleanup() {
        val controller = controller()
        val mfi = MfiSession(mock(MfiAuthenticator::class.java), null)
        val scheduled = CountDownLatch(1)
        val worker = mock(ExecutorService::class.java)
        doAnswer { scheduled.countDown(); null }.`when`(worker).execute(any(Runnable::class.java))
        ReflectionHelpers.getField<ExecutorService>(controller, "executor").shutdownNow()
        ReflectionHelpers.setField(controller, "executor", worker)
        try {
            mockConstruction(LocalOnlyHotspotManager::class.java) { manager, _ ->
                `when`(manager.start(anyLong())).thenReturn(info())
            }.use { created ->
                start(controller)
                ReflectionHelpers.setField(controller, "mfiSession", mfi)
                ReflectionHelpers.getField<AtomicBoolean>(controller, "wirelessFailureReported").set(true)
                assertTrue(controller.canRetryWirelessStartup())
                assertTrue(controller.retryWirelessStartup())
                assertTrue(scheduled.await(2, TimeUnit.SECONDS))
                assertFalse(controller.retryWirelessStartup())
                cleanupAttempt(controller, 0)
                assertSame(mfi, ReflectionHelpers.getField(controller, "mfiSession"))
                assertSame(created.constructed().single(), ReflectionHelpers.getField(controller, "hotspot"))
                verify(created.constructed().single(), never()).close()
                verify(worker, times(1)).execute(any(Runnable::class.java))
                controller.close()
                assertTrue(controller.awaitClosed(2_000))
                verify(created.constructed().single(), times(1)).close()
            }
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun establishedSessionCannotUseStartupRetryOrRetainItsHotspotInFailureCleanup() {
        val controller = controller()
        try {
            mockConstruction(LocalOnlyHotspotManager::class.java) { manager, _ ->
                `when`(manager.start(anyLong())).thenReturn(info())
            }.use { created ->
                start(controller)
                ReflectionHelpers.setField(controller, "mfiSession", MfiSession(mock(MfiAuthenticator::class.java), null))
                ReflectionHelpers.getField<AtomicBoolean>(controller, "wirelessFailureReported").set(true)
                ReflectionHelpers.setField(controller, "wirelessSessionEstablished", true)
                assertFalse(controller.canRetryWirelessStartup())
                assertFalse(controller.retryWirelessStartup())
                cleanupAttempt(controller)
                verify(created.constructed().single(), times(1)).close()
                controller.close()
                assertTrue(controller.awaitClosed(2_000))
                verify(created.constructed().single(), times(1)).close()
            }
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun slowCleanupCannotBlockRetryAdmissionAndQueuedDuplicateDoesNotStartAnotherAttempt() {
        val controller = controller()
        val scheduled = CountDownLatch(1)
        val resourceLockHeld = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(2)
        val worker = mock(ExecutorService::class.java)
        doAnswer { scheduled.countDown(); null }.`when`(worker).execute(any(Runnable::class.java))
        ReflectionHelpers.getField<ExecutorService>(controller, "executor").shutdownNow()
        ReflectionHelpers.setField(controller, "executor", worker)
        val manager = mock(WirelessHotspotManager::class.java)
        ReflectionHelpers.setField(controller, "hotspot", manager)
        ReflectionHelpers.setField(controller, "retainedHotspotInfo", info())
        ReflectionHelpers.setField(controller, "mfiSession", MfiSession(mock(MfiAuthenticator::class.java), null))
        ReflectionHelpers.getField<AtomicBoolean>(controller, "wirelessFailureReported").set(true)
        val lock = ReflectionHelpers.getField<Any>(controller, "wirelessResourceLock")
        try {
            callers.submit {
                synchronized(lock) {
                    resourceLockHeld.countDown()
                    releaseCleanup.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(resourceLockHeld.await(2, TimeUnit.SECONDS))
            val admission = callers.submit<Boolean> { controller.retryWirelessStartup() }
            assertTrue(admission.get(1, TimeUnit.SECONDS))
            assertFalse(controller.retryWirelessStartup())
            releaseCleanup.countDown()
            assertTrue(scheduled.await(2, TimeUnit.SECONDS))
            verify(manager, never()).close()
        } finally {
            releaseCleanup.countDown()
            callers.shutdownNow()
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
        verify(manager, times(1)).close()
    }
}
