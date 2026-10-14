package com.shilapi.xcertplay

import android.net.wifi.WifiManager
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocalOnlyHotspotLifecycleTest {
    @Test fun closeImmediatelyReleasesAStartedReservationDuringRadioDiscovery() {
        val (manager, attempt, callback) = pendingStart()
        val reservation = mock(WifiManager.LocalOnlyHotspotReservation::class.java)
        callback.onStarted(reservation)
        manager.close()
        verify(reservation).close()
        manager.close()
        cleanup(manager, attempt)
        verify(reservation, times(1)).close()
    }

    @Test fun aCallbackAfterCloseReleasesItsReservationAndCannotReviveTheHotspot() {
        val (manager, attempt, callback) = pendingStart()
        manager.close()
        val reservation = mock(WifiManager.LocalOnlyHotspotReservation::class.java)
        callback.onStarted(reservation)
        cleanup(manager, attempt)
        verify(reservation, times(1)).close()
    }

    @Test fun workerFailureBeforeCloseAlsoReleasesThePendingReservationOnlyOnce() {
        val (manager, attempt, callback) = pendingStart()
        val reservation = mock(WifiManager.LocalOnlyHotspotReservation::class.java)
        callback.onStarted(reservation)
        cleanup(manager, attempt)
        manager.close()
        verify(reservation, times(1)).close()
    }

    private fun pendingStart(): Triple<LocalOnlyHotspotManager, Any, WifiManager.LocalOnlyHotspotCallback> {
        val manager = LocalOnlyHotspotManager(RuntimeEnvironment.getApplication(), interfaceProvider = { emptyList() })
        val type = Class.forName("com.shilapi.xcertplay.network.LocalOnlyHotspotManager\$StartAttempt")
        val attempt = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        manager.javaClass.getDeclaredField("startAttempt").apply { isAccessible = true }.set(manager, attempt)
        val callback = manager.javaClass.getDeclaredMethod("createCallback", type).apply { isAccessible = true }
            .invoke(manager, attempt) as WifiManager.LocalOnlyHotspotCallback
        return Triple(manager, attempt, callback)
    }

    private fun cleanup(manager: LocalOnlyHotspotManager, attempt: Any) {
        manager.javaClass.getDeclaredMethod("cleanupFailedStart", attempt.javaClass, WifiManager.MulticastLock::class.java)
            .apply { isAccessible = true }.invoke(manager, attempt, null)
    }
}
