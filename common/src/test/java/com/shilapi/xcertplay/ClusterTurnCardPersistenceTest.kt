package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ClusterTurnCardPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().apply()
    }

    @Test fun overlayDefaultsToRightOfCentre() {
        assertEquals(ClusterTurnCardOverlay.DEFAULT_X_PERCENT, AirPlayPersistence.loadClusterTurnCardOverlayXPercent(context))
        assertEquals(ClusterTurnCardOverlay.DEFAULT_Y_PERCENT, AirPlayPersistence.loadClusterTurnCardOverlayYPercent(context))
        assertEquals(55, AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(context))
    }

    @Test fun overlayOffsetsRoundTrip() {
        AirPlayPersistence.saveClusterTurnCardOverlayXPercent(context, 90)
        AirPlayPersistence.saveClusterTurnCardOverlayYPercent(context, 16)
        AirPlayPersistence.saveClusterTurnCardOverlaySizePercent(context, 35)
        assertEquals(90, AirPlayPersistence.loadClusterTurnCardOverlayXPercent(context))
        assertEquals(16, AirPlayPersistence.loadClusterTurnCardOverlayYPercent(context))
        assertEquals(35, AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(context))
    }


    @Test fun smallWindowCardKeepsIndependentDefaultsAndPosition() {
        assertEquals(80, AirPlayPersistence.loadClusterSmallWindowCardXPercent(context))
        assertEquals(25, AirPlayPersistence.loadClusterSmallWindowCardYPercent(context))
        assertEquals(40, AirPlayPersistence.loadClusterSmallWindowCardSizePercent(context))

        AirPlayPersistence.saveClusterTurnCardOverlayXPercent(context, 60)
        AirPlayPersistence.saveClusterTurnCardOverlayYPercent(context, 40)
        AirPlayPersistence.saveClusterTurnCardOverlaySizePercent(context, 70)
        assertEquals(80, AirPlayPersistence.loadClusterSmallWindowCardXPercent(context))
        assertEquals(25, AirPlayPersistence.loadClusterSmallWindowCardYPercent(context))
        assertEquals(40, AirPlayPersistence.loadClusterSmallWindowCardSizePercent(context))

        AirPlayPersistence.saveClusterSmallWindowCardXPercent(context, 85)
        AirPlayPersistence.saveClusterSmallWindowCardYPercent(context, 20)
        AirPlayPersistence.saveClusterSmallWindowCardSizePercent(context, 35)
        assertEquals(85, AirPlayPersistence.loadClusterSmallWindowCardXPercent(context))
        assertEquals(20, AirPlayPersistence.loadClusterSmallWindowCardYPercent(context))
        assertEquals(35, AirPlayPersistence.loadClusterSmallWindowCardSizePercent(context))
    }


    @Test fun overlaySaveNotifiesTheHostWithoutReconnecting() {
        var noticed = 0
        val subscription = com.shilapi.xcertplay.settings.OverlaySettingsChanges.subscribe { noticed++ }
        AirPlayPersistence.saveClusterTurnCardOverlayXPercent(context, 20)
        AirPlayPersistence.saveClusterTurnCardOverlayYPercent(context, 50)
        AirPlayPersistence.saveClusterTurnCardOverlaySizePercent(context, 90)
        subscription.close()
        AirPlayPersistence.saveClusterTurnCardOverlayXPercent(context, 30)
        assertEquals(3, noticed)
    }
}
