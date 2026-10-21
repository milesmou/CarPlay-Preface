package com.shilapi.xcertplay

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
class SettingsProjectionReturnTest {
    private lateinit var lifecycle: ActivityController<DiPlayActivity>
    private val activity get() = lifecycle.get()
    private var stops = 0

    @Before fun prepare() {
        ConnectionAutoStart.resetForTest()
        CarPlayBackgroundSession.clear()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
    }

    @After fun cleanup() {
        lifecycle.destroy()
        CarPlayBackgroundSession.clear()
        ConnectionAutoStart.resetForTest()
        PendingReconnect.clear()
    }

    private fun settings(fromProjection: Boolean = true) = Intent().putExtra("page", "settings")
        .putExtra(DiPlayActivity.EXTRA_SETTINGS_RETURN_TO_CARPLAY, fromProjection)

    private fun open(fromProjection: Boolean = true, state: Bundle? = null) {
        lifecycle = Robolectric.buildActivity(DiPlayActivity::class.java, settings(fromProjection)).create(state).start()
    }

    private fun back() = activity.onBackPressedDispatcher.onBackPressed()

    private fun assertProjectionResumed() {
        val launch = shadowOf(activity).nextStartedActivity ?: error("Projection was not resumed")
        assertEquals(CarPlayHostActivity::class.java.name, launch.component?.className)
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertNull(launch.action)
        assertFalse(launch.hasExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE))
        assertEquals(0, stops)
        assertTrue(CarPlayBackgroundSession.hasSession())
    }

    @Test fun systemBackFromProjectionSettingsResumesWithoutStoppingOrApplyingPendingChanges() {
        open()
        val session = Any()
        PendingReconnect.mark(session)
        back()
        assertProjectionResumed()
        assertTrue(PendingReconnect.isPending(session))
    }

    @Test fun headerBackAlsoResumesTheProjection() {
        open()
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        descendants(activity.window.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == activity.getString(R.string.back) }.performClick()
        assertProjectionResumed()
    }

    @Test fun settingsOpenedFromHomeStillReturnHomeWithASessionInBackground() {
        open(fromProjection = false)
        back()
        assertEquals("home", ReflectionHelpers.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, stops)
    }

    @Test fun recreationKeepsTheProjectionReturnDestination() {
        open()
        val state = Bundle()
        lifecycle.saveInstanceState(state).destroy()
        open(fromProjection = false, state = state)
        back()
        assertProjectionResumed()
    }

    @Test fun reusedActivityTakesTheReturnDestinationFromTheNewIntent() {
        open(fromProjection = false)
        lifecycle.newIntent(settings())
        back()
        assertProjectionResumed()
        lifecycle.newIntent(settings(fromProjection = false))
        back()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("home", ReflectionHelpers.getField<String>(activity, "page"))
    }

    @Test fun noSessionOrCancelledLaunchFallsBackHomeWithoutStartingAConnection() {
        open()
        CarPlayBackgroundSession.clear()
        back()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("home", ReflectionHelpers.getField<String>(activity, "page"))
        lifecycle.newIntent(settings())
        ConnectionAutoStart.cancel()
        back()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun backupPageReturnsToSettingsBeforeReturningToProjection() {
        open()
        ReflectionHelpers.setField(activity, "page", "backup")
        back()
        assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
        back()
        assertProjectionResumed()
    }
}
