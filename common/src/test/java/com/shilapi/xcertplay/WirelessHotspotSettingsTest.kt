package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessHotspotSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var screen: DiPlayActivity? = null

    @Before fun clear() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        PendingReconnect.clear()
    }

    @After fun finish() {
        CarPlayBackgroundSession.clear()
        screen?.finish()
        AppAppearanceRuntime.resetForTest()
    }

    private fun open(): DiPlayActivity = Robolectric.buildActivity(DiPlayActivity::class.java,
        Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"))
        .setup().get().also { screen = it }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    private fun texts(activity: DiPlayActivity) = views(activity.window.decorView).filterIsInstance<TextView>()
    private fun render(activity: DiPlayActivity) = ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")

    @Test fun bothModesRoundTripWithoutRewritingOrLosingCredentials() {
        val prefs = app.getSharedPreferences("xcertplay_airplay", 0)
        val before = prefs.all
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertEquals(before, prefs.all)
        AirPlayPersistence.saveManualHotspotSsid(app, " Xingrui-CarPlay ")
        AirPlayPersistence.saveManualHotspotPassphrase(app, " password-123 ")
        for (mode in listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)) {
            AirPlayPersistence.saveWirelessHotspotMode(app, mode)
            assertEquals(mode, AirPlayPersistence.loadWirelessHotspotMode(app))
            assertEquals(" Xingrui-CarPlay ", AirPlayPersistence.loadManualHotspotSsid(app))
            assertEquals(" password-123 ", AirPlayPersistence.loadManualHotspotPassphrase(app))
        }
    }

    @Test fun savedCredentialsStayOutOfExportedSettings() {
        AirPlayPersistence.saveManualHotspotSsid(app, "private-test-ssid")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "private-test-password")
        val exported = com.shilapi.xcertplay.backup.SettingsBackupCodec.export(app, "0.1.1").toString(Charsets.UTF_8)
        assertFalse(exported.contains("private-test-ssid"))
        assertFalse(exported.contains("private-test-password"))
        assertFalse(exported.contains("manual_hotspot_passphrase"))
    }

    @Test fun changingModeLeavesTheActiveSessionRunningAndMarksReconnect() {
        val activity = open()
        var hotspotStarts = 0
        activity.manualHotspotWorker = { hotspotStarts++ }
        val controller = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, false, false, 1920, 720)) { stops++ }
        CarPlayBackgroundSession.active = true
        render(activity)
        texts(activity).single { it.text.startsWith(activity.getString(R.string.settings_hotspot_mode) + " · ") }.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(activity.getString(R.string.save), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        shadowOf(dialog).clickOnItem(1)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertTrue(PendingReconnect.isPending(controller))
        assertEquals(0, stops)
        assertEquals(0, hotspotStarts)
        assertTrue(texts(activity).any { it.text == activity.getString(R.string.settings_manual_hotspot_module) })
    }

    @Test fun selectingManualStartsSavedHotspotAndReportsMissingPermission() {
        val activity = open()
        var starts = 0
        activity.manualHotspotWorker = { it.run() }
        activity.manualHotspotEnable = { _, cancelled ->
            assertFalse(cancelled())
            starts++
            com.shilapi.xcertplay.network.CarHotspotTethering.Result.PERMISSION_REQUIRED
        }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "applyWirelessLink",
            ReflectionHelpers.ClassParameter.from(WirelessHotspotMode::class.java, WirelessHotspotMode.MANUAL))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, starts)
        assertTrue(texts(activity).any { it.text == activity.getString(R.string.settings_hotspot_start_permission) })
        assertTrue(texts(activity).any { it.text == activity.getString(R.string.settings_hotspot_allow_settings) })
        assertFalse(texts(activity).any { it.text == activity.getString(R.string.settings_hotspot_start_ready) })
    }

    @Test fun switchingToTemporaryCancelsPendingManualStartup() {
        val activity = open()
        var pending: Runnable? = null
        activity.manualHotspotWorker = { pending = it }
        activity.manualHotspotEnable = { _, cancelled ->
            assertTrue(cancelled())
            com.shilapi.xcertplay.network.CarHotspotTethering.Result.CANCELLED
        }
        for (mode in listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)) {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "applyWirelessLink",
                ReflectionHelpers.ClassParameter.from(WirelessHotspotMode::class.java, mode))
        }
        pending!!.run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertFalse(texts(activity).any { it.text == activity.getString(R.string.settings_hotspot_start_cancelled) })
    }

    @Test fun credentialsAreValidatedAndSavedExactlyWithoutExposingPasswordInTheCard() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        val activity = open()
        texts(activity).single { it.text.startsWith(activity.getString(R.string.settings_manual_hotspot_details) + " · ") }.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(Looper.getMainLooper()).idle()
        val fields = views(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
        assertEquals(2, fields.size)
        fields[0].setText(" Xingrui ")
        fields[1].setText("short")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.isShowing)
        assertEquals("", AirPlayPersistence.loadManualHotspotSsid(app))
        fields[1].setText(" password123 ")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(" Xingrui ", AirPlayPersistence.loadManualHotspotSsid(app))
        assertEquals(" password123 ", AirPlayPersistence.loadManualHotspotPassphrase(app))
        assertFalse(texts(activity).any { it.text.contains("password123") })
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.DISPLAY)
        render(activity)
        assertFalse(texts(activity).any { it.text == activity.getString(R.string.settings_manual_hotspot_module) })
        assertFalse(texts(activity).any { it.text == activity.getString(R.string.settings_wireless_hotspot) })
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun headUnitLandscapeLargeFontScreenshot() = capture("head-unit-1920x720", 1920, 720)

    @Test @Config(sdk = [28], qualifiers = "zh-rCN-w640dp-h240dp-land-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactHighDensityLargeFontScreenshot() = capture("high-density-1920x720", 1920, 720)

    private fun capture(name: String, width: Int, height: Int) {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Xingrui-CarPlay")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "example123")
        val activity = open()
        val config = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.5f }
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,
            activity.resources.configuration.orientation)
        assertEquals(1920, width)
        assertEquals(720, height)
        render(activity)
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val decor = activity.window.decorView
        repeat(3) {
            views(decor).forEach { it.forceLayout() }
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
        }
        val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
        val directory = File("../agent_docs/screenshots/manual-hotspot").apply { mkdirs() }
        for ((part, title) in listOf("wireless" to R.string.settings_wireless_hotspot,
            "manual" to R.string.settings_manual_hotspot_module)) {
            val target = texts(activity).single { it.text == activity.getString(title) }
            val rect = Rect(0, 0, target.width, target.height)
            scroll.offsetDescendantRectToMyCoords(target, rect)
            scroll.scrollTo(0, (rect.top - 16).coerceAtLeast(0))
            File(directory, "$name-$part-layout.txt").writeText("scroll=${scroll.width}x${scroll.height} childHeight=${scroll.getChildAt(0).height} targetTop=${target.top} rectTop=${rect.top} scrollY=${scroll.scrollY}")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            File(directory, "$name-$part-font150.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }
}
