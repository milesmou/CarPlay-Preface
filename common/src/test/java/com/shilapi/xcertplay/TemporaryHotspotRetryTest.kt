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
import org.junit.After
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TemporaryHotspotRetryTest {
    @After fun releaseIdleReservation() { TemporaryHotspotReservationStore.release() }

    private fun info() = WirelessHotspotInfo("temporary-test", "test12345", Iap2WirelessSecurity.WPA_WPA2,
        0, null, null, "ap0", InetAddress.getByName("192.0.2.1"), "2.4 GHz", WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT)

    private fun controller(transport: CarPlayTransport = CarPlayTransport.WIRELESS,
        mode: WirelessHotspotMode = WirelessHotspotMode.LOCAL_ONLY_HOTSPOT): CarPlayController = CarPlayController(RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = transport, wirelessHotspotMode = mode,
            manualHotspotSsid = "manual-test", manualHotspotPassphrase = "test12345",
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

    @Test fun manualDisconnectAcrossControllersReusesOneReservationUntilNormalClose() {
        val owners = mutableListOf<CarPlayController>()
        val snapshot = info()
        try {
            mockConstruction(LocalOnlyHotspotManager::class.java) { manager, _ ->
                `when`(manager.start(anyLong())).thenReturn(snapshot)
            }.use { created ->
                repeat(3) {
                    val owner = controller().also(owners::add)
                    assertSame(snapshot, start(owner))
                    owner.prepareTemporaryHotspotHandoff()
                    owner.close()
                    assertTrue(owner.awaitClosed(2_000))
                    verify(created.constructed().single(), never()).close()
                }
                val last = controller().also(owners::add)
                assertSame(snapshot, start(last))
                // Retired controllers cannot close a reservation now owned by the new connection.
                owners.dropLast(1).forEach { it.close() }
                val manager = created.constructed().single()
                verify(manager, times(1)).start(anyLong())
                verify(manager, never()).close()
                assertNull(TemporaryHotspotReservationStore.take())
                last.close()
                assertTrue(last.awaitClosed(2_000))
                verify(manager, times(1)).close()
            }
        } finally { owners.forEach { it.close(); it.awaitClosed(2_000) } }
    }

    @Test fun aSystemStoppedIdleReservationIsClosedAndRecreatedBeforeReconnect() {
        val firstOwner = controller()
        val nextOwner = controller()
        try {
            mockConstruction(LocalOnlyHotspotManager::class.java) { manager, _ ->
                `when`(manager.start(anyLong())).thenReturn(info())
            }.use { created ->
                val firstInfo = start(firstOwner)
                val first = created.constructed().single()
                firstOwner.prepareTemporaryHotspotHandoff()
                firstOwner.close()
                assertTrue(firstOwner.awaitClosed(2_000))
                doAnswer { throw IOException("system stopped idle hotspot") }.`when`(first).validateReady()
                assertNotSame(firstInfo, start(nextOwner))
                assertEquals(2, created.constructed().size)
                verify(first, times(1)).close()
                nextOwner.close()
                assertTrue(nextOwner.awaitClosed(2_000))
                verify(created.constructed()[1], times(1)).close()
            }
        } finally {
            firstOwner.close(); firstOwner.awaitClosed(2_000)
            nextOwner.close(); nextOwner.awaitClosed(2_000)
        }
    }

    @Test fun switchingToUsbOrManualHotspotReleasesTheIdleApplicationReservation() {
        for ((transport, mode) in listOf(CarPlayTransport.WIRED to WirelessHotspotMode.LOCAL_ONLY_HOTSPOT,
            CarPlayTransport.WIRELESS to WirelessHotspotMode.MANUAL)) {
            val manager = mock(LocalOnlyHotspotManager::class.java)
            assertTrue(TemporaryHotspotReservationStore.retain(manager, info()))
            val nextOwner = controller(transport, mode)
            try {
                nextOwner.start()
                verify(manager, times(1)).close()
                assertNull(TemporaryHotspotReservationStore.take())
            } finally { nextOwner.close(); assertTrue(nextOwner.awaitClosed(2_000)) }
        }
    }

    @Test fun unfinishedStartupCannotHandOffAReservationWithoutItsLiveConfiguration() {
        val owner = controller()
        val manager = mock(LocalOnlyHotspotManager::class.java)
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(owner, "hotspotLease"), "currentManager", manager)
        owner.prepareTemporaryHotspotHandoff()
        owner.close()
        assertTrue(owner.awaitClosed(2_000))
        assertNull(TemporaryHotspotReservationStore.take())
        verify(manager, times(1)).close()
    }

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

    @Test fun retryKeepsControllerMfiAndHotspotAndRejectsDuplicateOldCleanup() = retainedRetry(false)

    @Test fun lostEstablishedSessionRetriesWithTheSameHotspotInsteadOfCyclingItsPower() = retainedRetry(true)

    private fun retainedRetry(established: Boolean) {
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
                ReflectionHelpers.getField<AtomicBoolean>(controller, "wirelessFailureReported").set(!established)
                ReflectionHelpers.setField(controller, "wirelessSessionEstablished", established)
                assertTrue(controller.canRetryWirelessStartup())
                assertTrue(controller.retryWirelessStartup())
                assertTrue(scheduled.await(2, TimeUnit.SECONDS))
                assertFalse(controller.retryWirelessStartup())
                cleanupAttempt(controller, 0)
                assertSame(mfi, ReflectionHelpers.getField(controller, "mfiSession"))
                assertSame(created.constructed().single(), ReflectionHelpers.getField(ReflectionHelpers.getField<Any>(controller, "hotspotLease"), "currentManager"))
                verify(created.constructed().single(), never()).close()
                verify(worker, times(1)).execute(any(Runnable::class.java))
                controller.close()
                assertTrue(controller.awaitClosed(2_000))
                verify(created.constructed().single(), times(1)).close()
            }
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun liveSessionCannotUseStartupRetry() {
        val controller = controller()
        try {
            mockConstruction(LocalOnlyHotspotManager::class.java) { manager, _ ->
                `when`(manager.start(anyLong())).thenReturn(info())
            }.use { created ->
                start(controller)
                ReflectionHelpers.setField(controller, "mfiSession", MfiSession(mock(MfiAuthenticator::class.java), null))
                ReflectionHelpers.getField<AtomicBoolean>(controller, "wirelessFailureReported").set(true)
                ReflectionHelpers.setField(controller, "wirelessSessionEstablished", true)
                ReflectionHelpers.setField(controller, "activeSession", mock(AirPlaySession::class.java))
                assertFalse(controller.canRetryWirelessStartup())
                assertFalse(controller.retryWirelessStartup())
                verify(created.constructed().single(), never()).close()
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
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(controller, "hotspotLease"), "currentManager", manager)
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(controller, "hotspotLease"), "retainedInfo", info())
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
