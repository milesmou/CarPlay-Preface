package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.view.Surface
import android.widget.Button
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeTransportSwitchTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var screen: ActivityController<DiPlayActivity>? = null
    private var stops = 0
    private var release: (() -> Unit)? = null

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        DiPlayPreferences.savePhone(app, "12:34:56:78:90:AB", "iPhone")
    }

    @After fun cleanup() { screen?.destroy(); CarPlayBackgroundSession.clear() }

    private fun retain(mode: CarPlayTransport, active: Boolean = false, closed: Boolean = false): CarPlayController {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.connectionTransport()).thenReturn(mode)
        `when`(controller.isClosed()).thenReturn(closed)
        `when`(controller.matchesWirelessPhone("12:34:56:78:90:AB")).thenReturn(mode == CarPlayTransport.WIRELESS)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) { done ->
            stops++
            release = { CarPlayBackgroundSession.clear(controller); done() }
        }
        CarPlayBackgroundSession.active = active
        return controller
    }

    private fun home(): DiPlayActivity {
        screen = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).create()
        val activity = screen!!.get()
        ReflectionHelpers.setField(activity, "setupError", null)
        ReflectionHelpers.setField(activity, "connectedPhoneState", ConnectedPhoneState(true, true,
            listOf(ConnectedPhone("12:34:56:78:90:AB", "iPhone"))))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
        return activity
    }
    private fun wireless(activity: DiPlayActivity) =
        (ReflectionHelpers.getField<android.widget.LinearLayout>(activity, "homePhoneList").getChildAt(0) as Button)
    private fun assertProjection(activity: DiPlayActivity) {
        val launch = shadowOf(activity).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, launch?.component?.className)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
    }

    @Test fun unpluggedUsbStillWaitingCannotBeResumedByTheWirelessCard() {
        retain(CarPlayTransport.WIRED)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val activity = home()
        assertEquals("iPhone · ${activity.getString(R.string.home_bluetooth_connected)}", wireless(activity).text.toString())
        wireless(activity).performClick()
        assertEquals(1, stops)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(AirPlayPersistence.loadWirelessEnabled(app))
        release!!(); shadowOf(Looper.getMainLooper()).idle()
        assertProjection(activity)
    }

    @Test fun evenAnActiveUsbSnapshotMustStopBeforeWirelessStarts() {
        retain(CarPlayTransport.WIRED, active = true)
        val activity = home()
        wireless(activity).performClick()
        assertEquals(1, stops)
        assertNull(shadowOf(activity).nextStartedActivity)
        release!!()
        assertProjection(activity)
    }

    @Test fun savedWirelessPreferenceCannotMisidentifyAnActualUsbController() {
        retain(CarPlayTransport.WIRED)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val activity = home()
        wireless(activity).performClick()
        assertEquals(1, stops)
        assertNull(shadowOf(activity).nextStartedActivity)
        release!!()
        assertProjection(activity)
    }

    @Test fun matchingWirelessConnectionIsResumedWithoutRecreatingItsHotspot() {
        retain(CarPlayTransport.WIRELESS, active = true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val activity = home()
        assertEquals("iPhone · ${activity.getString(R.string.home_bluetooth_connected)}", wireless(activity).text.toString())
        wireless(activity).performClick()
        assertEquals(0, stops)
        assertProjection(activity)
    }

    @Test fun closedWirelessControllerIsStoppedInsteadOfReused() {
        retain(CarPlayTransport.WIRELESS, closed = true)
        val activity = home()
        wireless(activity).performClick()
        assertEquals(1, stops)
        release!!()
        assertProjection(activity)
    }

    @Test fun aRetiringUsbOwnerWithoutASnapshotStillWaitsForRelease() {
        retain(CarPlayTransport.WIRED)
        CarPlayBackgroundSession.clear(keepOwner = true)
        val activity = home()
        wireless(activity).performClick()
        assertEquals(1, stops)
        assertNull(shadowOf(activity).nextStartedActivity)
        release!!()
        assertProjection(activity)
    }
}
