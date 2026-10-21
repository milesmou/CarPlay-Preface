package com.shilapi.xcertplay

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.RadioButton
import android.widget.TextView
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import android.app.AlertDialog
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.util.concurrent.PausedExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostSettingsTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var activityLifecycle: org.robolectric.android.controller.ActivityController<CarPlayHostActivity>
    private lateinit var controllers: MockedConstruction<CarPlayController>

    @Before fun setUp() {
        ConnectionAutoStart.resetForTest()
        activityLifecycle = Robolectric.buildActivity(CarPlayHostActivity::class.java)
        activity = activityLifecycle.get()
        controllers = mockConstruction(CarPlayController::class.java) { controller, _ ->
            org.mockito.Mockito.`when`(controller.awaitClosed(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true)
        }
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        setField("airPlayIdentity", AirPlayIdentity.generate())
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 990)
        setField("activeDisplaySize", size)
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, activity)
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        invoke("loadPersistedSettings")
        invoke("buildContentView")
    }

    @After fun tearDown() {
        (field("shuttingDown") as AtomicBoolean).set(true)
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        controllers.close()
        ConnectionAutoStart.resetForTest()
    }

    @Test fun hotspotStatusShowsNetworkDetailsWithoutExportingThePassword() {
        setField("wirelessEnabled", true)
        invoke("openSettingsMenu")
        val status = (field("hotspotStatusView") as? TextView ?: TextView(activity).also { setField("hotspotStatusView", it) })
        fun update(value: CarPlayStatus) {
            activity.javaClass.getDeclaredMethod("updateHotspotStatus", CarPlayStatus::class.java)
                .apply { isAccessible = true }.invoke(activity, value)
        }
        val ready = CarPlayStatus.HotspotReady("AndroidShare_test", "2.4 GHz", 0,
            "02:00:00:00:00:01", "192.0.2.1", "LocalOnlyHotspot", "test-password")
        update(ready)
        assertTrue(status.text.contains("AndroidShare_test"))
        assertFalse(status.text.contains("test-password"))
        assertFalse(ready.toString().contains("test-password"))
        update(CarPlayStatus.StartingHotspot)
        assertFalse(status.text.contains("AndroidShare_test"))
    }

    @Test @Config(sdk = [28, 33])
    fun systemBackKeepsTheCarPlayPageAndItsGestureMenu() {
        activityLifecycle.create().start().resume()
        // Startup may launch the permission controller; measure only navigation caused by Back.
        while (shadowOf(activity).nextStartedActivity != null) { }
        val controller = attachController()
        setField("activeAirPlaySession", mock(AirPlaySession::class.java))
        CarPlayBackgroundSession.active = true
        for (open in listOf(false, true)) {
            if (open) invoke("openSettingsMenu")
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                assertTrue(activity.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_BACK)))
            }
            activity.onBackPressedDispatcher.onBackPressed()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(activity.isFinishing)
            assertEquals(open, field("menuOpen"))
            assertSame(controller, field("controller"))
            assertTrue(CarPlayBackgroundSession.active)
        }
        org.mockito.Mockito.verify(controller, org.mockito.Mockito.never()).close()
        activityLifecycle.pause().stop().destroy()
    }

    @Test fun systemBackFromTheConnectionMenuCancelsAfterDiscardingPendingEdits() {
        attachController()
        invoke("openSettingsMenu")
        val original = AirPlayPersistence.loadFps(activity)
        setField("fps", if (original == 30) 60 else 30)
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
        discardPendingEdits()
        assertEquals("home", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
        assertTrue(activity.isFinishing)
        assertTrue(ConnectionAutoStart.paused)
        assertNull(field("controller"))
        assertEquals(original, AirPlayPersistence.loadFps(activity))
        assertFalse(field("reconnectScheduled") as Boolean)
    }

    @Test fun menuHomeButtonReturnsToHomeAndKeepsTheConnection() {
        val controller = attachController()
        CarPlayBackgroundSession.active = true
        invoke("openSettingsMenu")
        homeButton().performClick()
        val destination = shadowOf(activity).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, destination.component?.className)
        assertEquals("home", destination.getStringExtra("page"))
        assertFalse(field("menuOpen") as Boolean)
        assertSame(controller, field("controller"))
        assertTrue(CarPlayBackgroundSession.active)
        assertFalse(activity.isFinishing)
        assertEquals(0, field("restartGeneration"))
        org.mockito.Mockito.verify(controller, org.mockito.Mockito.never()).close()
    }

    @Test fun menuHomeButtonConfirmsUnsavedChangesBeforeNavigating() {
        val controller = attachController()
        setField("activeAirPlaySession", mock(AirPlaySession::class.java))
        CarPlayBackgroundSession.active = true
        invoke("openSettingsMenu")
        val original = AirPlayPersistence.loadFps(activity)
        setField("fps", if (original == 30) 60 else 30)
        homeButton().performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(field("menuOpen") as Boolean)
        discardPendingEdits()
        assertEquals("home", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
        assertEquals(original, AirPlayPersistence.loadFps(activity))
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
    }

    @Test
    @Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullGestureMenuHomeLargeFontScreenshot() = captureGestureMenuHome("full")

    @Test
    @Config(sdk = [28], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactGestureMenuHomeLargeFontScreenshot() = captureGestureMenuHome("compact")

    @Test
    @Config(sdk = [28], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun standardGestureMenuScreenshot() = captureGestureMenuHome("standard")

    @Test
    @Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun narrowGestureMenuLargeFontScreenshot() = captureGestureMenuHome("narrow")


    private fun captureGestureMenuHome(name: String) {
        InterfaceSize.save(activity, if (name == "compact") 100 else InterfaceSize.PREFACE_12_3)
        val audio = mock(android.media.AudioManager::class.java)
        `when`(audio.getStreamMaxVolume(org.mockito.ArgumentMatchers.anyInt())).thenReturn(20)
        `when`(audio.getStreamVolume(3)).thenReturn(7)
        `when`(audio.getStreamVolume(12)).thenReturn(9)
        var base: android.content.Context = activity
        while (base is android.content.ContextWrapper) base = base.baseContext
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowContextImpl>(base)
            .setSystemService(android.content.Context.AUDIO_SERVICE, audio)
        val config = android.content.res.Configuration(activity.resources.configuration).apply {
            fontScale = if (name == "standard") 1f else 1.5f
        }
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        val root = invoke("buildContentView") as View
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        attachController()
        setField("activeAirPlaySession", mock(AirPlaySession::class.java))
        invoke("openSettingsMenu")
        if (name == "narrow") {
            (menu().layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                width = (520 * activity.resources.displayMetrics.density).toInt()
                gravity = android.view.Gravity.CENTER
            }
        }
        (field("connectionPanel") as View).visibility = View.GONE
        views(root).forEach { it.layoutDirection = root.layoutDirection }
        repeat(3) {
            views(root).forEach { it.forceLayout() }
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
        }
        val bounds = android.graphics.Rect()
        for (button in views(menu()).filterIsInstance<Button>()) {
            assertTrue(button.height >= (56 * menu().resources.displayMetrics.density).toInt())
            assertTrue(button.text.toString(), button.getGlobalVisibleRect(bounds))
            assertTrue(button.text.toString(), bounds.top >= 0 && bounds.bottom <= 720)
            assertEquals("${button.text}: bounds=$bounds parent=${(button.parent as View).height}", button.height, bounds.height())
            assertTrue(button.text.toString(), button.layout.height + button.compoundPaddingTop + button.compoundPaddingBottom <= button.height)
            for (line in 0 until button.layout.lineCount) assertEquals(0, button.layout.getEllipsisCount(line))
        }
        for (slider in views(menu()).filterIsInstance<SeekBar>()) {
            assertTrue(slider.height >= (56 * menu().resources.displayMetrics.density).toInt())
            assertTrue(slider.isEnabled)
            assertTrue(slider.contentDescription.toString(), slider.getGlobalVisibleRect(bounds))
            assertEquals(slider.height, bounds.height())
        }
        val volumeHeading = views(menu()).filterIsInstance<TextView>()
            .single { it.text == activity.getString(R.string.settings_quick_volume) }
        val headingBounds = menuBounds(volumeHeading)
        assertTrue(views(menu()).filterIsInstance<Button>().all { menuBounds(it).bottom < headingBounds.top })
        val sliders = views(menu()).filterIsInstance<SeekBar>().toList()
        assertTrue(sliders.all { it.width == (it.parent as View).width })
        assertEquals(menuBounds(sliders.first()).left, menuBounds(sliders.last()).left)
        assertEquals(menuBounds(sliders.first()).right, menuBounds(sliders.last()).right)
        val panel = (menu() as ViewGroup).getChildAt(0)
        assertTrue(panel.getGlobalVisibleRect(bounds))
        assertTrue("Menu panel should be vertically centered", kotlin.math.abs(bounds.top + bounds.bottom - 720) <= 4)
        val bitmap = android.graphics.Bitmap.createBitmap(1920, 720, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        val directory = java.io.File("../agent_docs/screenshots/quick-menu-stacked-20261020-test-renders").apply { mkdirs() }
        val font = if (name == "standard") 100 else 150
        java.io.File(directory, "$name-font$font.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        java.io.File(directory, "$name-font$font.json").writeText(org.json.JSONObject()
            .put("source", "Robolectric native graphics unit-test render; not an installed APK screenshot")
            .put("widthPixels", 1920).put("heightPixels", 720)
            .put("hostDensityDpi", activity.resources.displayMetrics.densityDpi)
            .put("menuDensityDpi", menu().resources.displayMetrics.densityDpi)
            .put("interfaceSize", InterfaceSize.preference(activity))
            .put("fontScale", menu().resources.configuration.fontScale.toDouble())
            .toString(2))
        bitmap.recycle()
    }

    @Test fun switchingToUsbReplacesWirelessStatus() {
        setField("wirelessEnabled", true)
        invoke("openSettingsMenu")
        val status = TextView(activity).also { setField("hotspotStatusView", it) }
        activity.javaClass.getDeclaredMethod("updateHotspotStatus", CarPlayStatus::class.java)
            .apply { isAccessible = true }.invoke(activity,
                CarPlayStatus.HotspotReady("AndroidShare_test", "2.4 GHz", 0,
                    "02:00:00:00:00:01", "192.0.2.1", "LocalOnlyHotspot", "test-password"))
        setField("wirelessEnabled", false)
        invoke("updateHotspotStatusBlock")
        assertEquals(activity.getString(R.string.wireless_hotspot_off), status.text.toString())
    }

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactWaitingScreenLargeFontScreenshot() = captureWaitingScreen("compact")

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullWaitingScreenLargeFontScreenshot() = captureWaitingScreen("full")

    private fun captureWaitingScreen(name: String) {
        val config = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.5f }
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        setField("wirelessEnabled", true)
        val root = invoke("buildContentView") as View
        repeat(3) {
            views(root).forEach { it.forceLayout() }
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
        }
        val bitmap = android.graphics.Bitmap.createBitmap(1920, 720, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        val directory = java.io.File("../agent_docs/screenshots/upstream-0.2.20").apply { mkdirs() }
        java.io.File(directory, "waiting-$name-font150.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun quickMenuContainsOnlyImmediateActions() {
        invoke("openSettingsMenu")
        val buttons = views(menu()).filterIsInstance<Button>().map { it.text.toString() }.toList()
        assertEquals(listOf(R.string.settings_return_to_home, R.string.settings_all_settings,
            R.string.picture_adjustments, R.string.exit_application)
            .map { activity.getString(it) }, buttons)
        assertEquals(2, views(menu()).filterIsInstance<SeekBar>().count())
        assertFalse(views(menu()).any { it is RadioButton || it is android.widget.Switch })
        assertFalse(buttons.contains(activity.getString(R.string.save_and_reconnect)))
    }

    @Test @Config(sdk = [28], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    fun quickMenuUsesTheAppSizeWithoutChangingVideoWindowDensity() {
        val originalDensity = activity.resources.displayMetrics.densityDpi
        InterfaceSize.save(activity, InterfaceSize.PREFACE_12_3)
        invoke("openSettingsMenu")
        assertEquals(160, menu().resources.displayMetrics.densityDpi)
        assertEquals(originalDensity, activity.resources.displayMetrics.densityDpi)
        assertEquals(activity.resources.configuration.fontScale, menu().resources.configuration.fontScale, .001f)
        invoke("cancelSettingsEdits")
        InterfaceSize.save(activity, 100)
        invoke("openSettingsMenu")
        assertEquals(originalDensity, menu().resources.displayMetrics.densityDpi)
    }

    @Test fun menuVolumeUsesTheCurrentSinkAndDoesNotUndoLiveVolumeOnClose() {
        val audio = mock(android.media.AudioManager::class.java)
        val levels = mutableMapOf(3 to 7, 12 to 9)
        `when`(audio.getStreamMaxVolume(org.mockito.ArgumentMatchers.anyInt())).thenReturn(20)
        `when`(audio.getStreamVolume(org.mockito.ArgumentMatchers.anyInt())).thenAnswer {
            levels[it.getArgument<Int>(0)] ?: 0
        }
        org.mockito.Mockito.doAnswer { levels[it.getArgument(0)] = it.getArgument(1); null }
            .`when`(audio).setStreamVolume(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())
        var base: android.content.Context = activity
        while (base is android.content.ContextWrapper) base = base.baseContext
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowContextImpl>(base)
            .setSystemService(android.content.Context.AUDIO_SERVICE, audio)
        val sink = mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java)
        `when`(sink.configuredVolumeStream(true)).thenReturn(12)
        `when`(sink.configuredVolumeStream(false)).thenReturn(3)
        setField("sink", sink)
        val controller = attachController()
        AirPlayPersistence.saveNavigationAudioChannel(activity, 9) // Saved for the next connection.
        invoke("openSettingsMenu")
        val navigation = views(menu()).filterIsInstance<SeekBar>().first()
        assertTrue(navigation.performAccessibilityAction(
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            android.os.Bundle().apply {
                putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 14f)
            }))
        invoke("cancelSettingsEdits")
        assertEquals(14, levels[12])
        assertEquals(7, levels[3])
        assertEquals(9, AirPlayPersistence.loadNavigationAudioChannel(activity))
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        org.mockito.Mockito.verify(audio).setStreamVolume(12, 14, 0)
    }

    @Test fun returningToCarPlayClosesTheMenuWithoutChangingSettingsOrRestarting() {
        val controller = attachController()
        val before = activity.getSharedPreferences("xcertplay_airplay", 0).all.toMap()
        mountMenuForTouch()
        tapMenu(10f, 10f)
        assertFalse(field("menuOpen") as Boolean)
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(before, activity.getSharedPreferences("xcertplay_airplay", 0).all)
        org.mockito.Mockito.verify(controller, org.mockito.Mockito.never()).close()
    }

    @Test fun blankGapsInsideTheMenuAlsoDismissWithoutForwardingTheTap() {
        val controller = attachController()
        mountMenuForTouch()
        val home = menuBounds(homeButton())
        val settings = menuBounds(fullSettingsButton())
        tapMenu((home.right + settings.left) / 2f, home.exactCenterY())
        assertFalse(field("menuOpen") as Boolean)
        assertSame(controller, field("controller"))
        assertTrue(org.mockito.Mockito.mockingDetails(controller).invocations
            .filter { it.method.name == "sendTouch" }
            .all { (it.arguments[0] as List<*>).isEmpty() })
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun menuPaddingAndTheGapBetweenActionsAndVolumeDismiss() {
        attachController()
        for (target in listOf("padding", "sections")) {
            mountMenuForTouch()
            val home = menuBounds(homeButton())
            val heading = menuBounds(views(menu()).filterIsInstance<TextView>()
                .single { it.text == activity.getString(R.string.settings_quick_volume) })
            val actionsBottom = views(menu()).filterIsInstance<Button>().maxOf { menuBounds(it).bottom }
            val x = if (target == "padding") home.left - 8f else home.exactCenterX()
            val y = if (target == "padding") home.exactCenterY() else (actionsBottom + heading.top) / 2f
            tapMenu(x, y)
            assertFalse(target, field("menuOpen") as Boolean)
        }
    }

    @Test fun touchingButtonsOrDisabledSlidersDoesNotBecomeABlankTap() {
        activityLifecycle.create().start().resume().visible()
        while (shadowOf(activity).nextStartedActivity != null) { }
        attachController()
        setField("activeAirPlaySession", mock(AirPlaySession::class.java))
        mountMenuForTouch()
        val slider = views(menu()).filterIsInstance<SeekBar>().first()
        slider.isEnabled = false
        val sliderBounds = menuBounds(slider)
        assertTrue(slider.height >= (56 * menu().resources.displayMetrics.density).toInt())
        for (y in listOf(sliderBounds.top + 1f, sliderBounds.exactCenterY(), sliderBounds.bottom - 1f)) {
            tapMenu(sliderBounds.exactCenterX(), y)
            assertTrue(field("menuOpen") as Boolean)
        }
        assertNull(shadowOf(activity).nextStartedActivity)
        val home = menuBounds(homeButton())
        tapMenu(home.exactCenterX(), home.exactCenterY())
        assertFalse(field("menuOpen") as Boolean)
        assertEquals("home", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
        activityLifecycle.pause().stop().destroy()
    }

    @Test fun blankDragsAndCancelledGesturesKeepTheMenuOpen() {
        attachController()
        mountMenuForTouch()
        menuTouch(MotionEvent.ACTION_DOWN, 20f, 20f)
        menuTouch(MotionEvent.ACTION_MOVE, 120f, 20f)
        menuTouch(MotionEvent.ACTION_UP, 120f, 20f)
        assertTrue(field("menuOpen") as Boolean)
        menuTouch(MotionEvent.ACTION_DOWN, 20f, 20f)
        menuTouch(MotionEvent.ACTION_CANCEL, 20f, 20f)
        assertTrue(field("menuOpen") as Boolean)
        // A drag ending over blank space must not convert a control gesture into dismissal.
        val slider = menuBounds(views(menu()).filterIsInstance<SeekBar>().first())
        menuTouch(MotionEvent.ACTION_DOWN, slider.exactCenterX(), slider.exactCenterY())
        menuTouch(MotionEvent.ACTION_MOVE, 20f, 20f)
        menuTouch(MotionEvent.ACTION_UP, 20f, 20f)
        assertTrue(field("menuOpen") as Boolean)
    }

    @Test fun pictureAdjustmentsRequireTheSameConnectedSessionWhenThePanelOpens() {
        setField("activeAirPlaySession", null)
        invoke("openPicturePanel")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(field("picturePanel"))

        setField("activeAirPlaySession", mock(AirPlaySession::class.java))
        invoke("openPicturePanel")
        setField("activeAirPlaySession", null)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(field("picturePanel"))
    }

    @Test fun pictureShortcutOpensLiveAdjustmentsAndKeepsTheConnection() {
        activityLifecycle.create().start().resume().visible()
        while (shadowOf(activity).nextStartedActivity != null) { }
        val controller = attachController()
        setField("activeAirPlaySession", mock(AirPlaySession::class.java))
        invoke("openSettingsMenu")
        views(menu()).filterIsInstance<Button>().single {
            it.text == activity.getString(R.string.picture_adjustments)
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(field("menuOpen") as Boolean)
        assertNotNull(field("picturePanel"))
        val contrast = views(field("picturePanel") as View).filterIsInstance<SeekBar>().toList()[1]
        assertTrue(contrast.performAccessibilityAction(
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            android.os.Bundle().apply {
                putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 125f)
            }))
        assertEquals(125, CarPlayPicture.value(CarPlayPicture.preferences(activity), CarPlayPicture.CONTRAST))
        invoke("openSettingsMenu")
        assertNull(field("picturePanel"))
        invoke("cancelSettingsEdits")
        assertEquals(125, CarPlayPicture.value(CarPlayPicture.preferences(activity), CarPlayPicture.CONTRAST))
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        assertNull(shadowOf(activity).nextStartedActivity)
        org.mockito.Mockito.verify(controller, org.mockito.Mockito.never()).close()
        activityLifecycle.pause().stop().destroy()
    }
    @Test fun disabledGesturePersistsAndDoesNotOpenTheMenu() {
        AirPlayPersistence.saveSettingsGestureFingers(activity, 0)
        invoke("loadPersistedSettings")
        assertEquals(0, AirPlayPersistence.loadSettingsGestureFingers(activity))
        for (fingers in 2..4) {
            gesture(fingers)
            assertFalse(field("menuOpen") as Boolean)
            assertFalse(activity.touchController.trackingSettingsGesture)
        }
        invoke("openSettingsMenu")
        invoke("cancelSettingsEdits")
        assertEquals(0, field("gestureFingerCount"))
    }

    @Test fun menuStaysCenteredAndUsesAvailableWidthAcrossWindowSizes() {
        invoke("openSettingsMenu")
        val overlay = menu() as ViewGroup
        val panel = overlay.getChildAt(0)
        val density = activity.resources.displayMetrics.density
        for (widthDp in listOf(320, 600, 1024, 1600)) {
            val width = (widthDp * density + 0.5f).toInt()
            val height = (480 * density + 0.5f).toInt()
            repeat(3) {
                overlay.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                overlay.layout(0, 0, width, height)
            }
            val expected = (minOf(1040, widthDp - 32) * density + 0.5f).toInt()
            assertEquals(expected, panel.width)
            assertTrue(kotlin.math.abs(panel.left - (width - panel.right)) <= 1)
            assertTrue(kotlin.math.abs(panel.top - (height - panel.bottom)) <= 1)
            val buttons = views(panel).filterIsInstance<Button>().toList()
            assertTrue(buttons.all { it.width > 0 && it.width <= panel.width })
            val picture = homeButton()
            val settings = buttons.single { it.text == activity.getString(R.string.settings_all_settings) }
            assertEquals(picture.height, settings.height)
            if (widthDp >= 600) {
                assertEquals(picture.top, settings.top)
                assertEquals((12 * density + 0.5f).toInt(), settings.left - picture.right)
            }
            else assertTrue(settings.top > picture.top)
        }
    }
    @Test fun configuredFingerCountsOpenTheMountedMenuWithoutLeavingCarPlay() {
        assertEquals(3, AirPlayPersistence.loadSettingsGestureFingers(activity))
        for (fingers in 2..4) {
            AirPlayPersistence.saveSettingsGestureFingers(activity, fingers)
            invoke("loadPersistedSettings")
            gesture(fingers)
            assertTrue(field("menuOpen") as Boolean)
            assertEquals(View.VISIBLE, menu().visibility)
            assertNotNull(menu().parent)
            assertEquals(View.GONE, (field("gestureOverlay") as View).visibility)
            assertNull(shadowOf(activity).nextStartedActivity)
            invoke("cancelSettingsEdits")
        }
    }

    @Test fun wrongFingerCountsAndNonDownwardSwipesDoNotOpenTheMenu() {
        for (configured in 2..4) {
            AirPlayPersistence.saveSettingsGestureFingers(activity, configured)
            invoke("loadPersistedSettings")
            for (actual in 2..4) {
                if (actual == configured) continue
                gesture(actual)
                assertFalse(field("menuOpen") as Boolean)
            }
            gesture(configured, x = 1000f, y = 130f)
            assertFalse(field("menuOpen") as Boolean)
            gesture(configured, y = -500f)
            assertFalse(field("menuOpen") as Boolean)
        }
    }

    @Test fun liftingAFingerCancelsTrackingUntilTheNextGesture() {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, 100f)
        touch(MotionEvent.ACTION_POINTER_UP, 3, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, 100f)
        touch(MotionEvent.ACTION_MOVE, 3, 700f)
        assertFalse(field("menuOpen") as Boolean)
        touch(MotionEvent.ACTION_UP, 1, 700f)
        gesture(3)
        assertTrue(field("menuOpen") as Boolean)
    }

    @Test fun fullSettingsShortcutDiscardsPreviewWithoutRestartingTheSession() {
        val controller = attachController()
        invoke("openSettingsMenu")
        val original = AirPlayPersistence.loadFps(activity)
        setField("fps", 30)
        fullSettingsButton().performClick()
        // A staged edit is pending, so leaving asks first and nothing has been thrown away yet.
        assertTrue(field("menuOpen") as Boolean)
        assertNull(shadowOf(activity).nextStartedActivity)
        discardPendingEdits()
        assertFalse(field("menuOpen") as Boolean)
        assertNull(field("settingsBaseline"))
        assertEquals(original, field("fps"))
        assertEquals(original, AirPlayPersistence.loadFps(activity))
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, intent.component!!.className)
        assertEquals("settings", intent.getStringExtra("page"))
        assertTrue(intent.getBooleanExtra(DiPlayActivity.EXTRA_SETTINGS_RETURN_TO_CARPLAY, false))
    }

    @Test fun leavingWithoutAPendingEditDoesNotAsk() {
        attachController()
        invoke("openSettingsMenu")
        fullSettingsButton().performClick()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertFalse(field("menuOpen") as Boolean)
        assertEquals(DiPlayActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun theQuestionAloneChangesNothing() {
        attachController()
        invoke("openSettingsMenu")
        val original = AirPlayPersistence.loadFps(activity)
        setField("fps", 30)
        val staged = field("fps")
        assertNotEquals(original, staged)

        fullSettingsButton().performClick()
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(field("menuOpen") as Boolean)
        assertNotNull(field("settingsBaseline"))
        assertEquals(staged, field("fps"))
        assertEquals(original, AirPlayPersistence.loadFps(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun answeringSaveKeepsTheStagedEditInsteadOfDiscardingIt() {
        attachController()
        invoke("openSettingsMenu")
        setField("fps", 30)
        val staged = field("fps")

        fullSettingsButton().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("leaving the menu with a pending edit should ask before discarding")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(field("menuOpen") as Boolean)
        assertEquals(staged, AirPlayPersistence.loadFps(activity))
    }

    @Test fun returningFromFullSettingsReloadsSavedConnectionPreferences() {
        attachController()
        invoke("openSettingsMenu")
        fullSettingsButton().performClick()
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        invoke("onResume")
        assertEquals(MfiTarget.LOCAL, field("mfiTarget"))
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, field("wirelessHotspotMode"))
    }

    @Test fun openingAndCancellingKeepsTheCurrentControllerAndRestoresControls() {
        val controller = attachController()
        invoke("openSettingsMenu")
        val original = AirPlayPersistence.loadFps(activity)
        setField("fps", 30)
        setField("gestureFingerCount", 4)
        assertEquals(3, AirPlayPersistence.loadSettingsGestureFingers(activity))
        invoke("cancelSettingsEdits")
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        assertEquals(original, field("fps"))
        assertEquals(3, field("gestureFingerCount"))
        invoke("openSettingsMenu")
        assertEquals(original, field("fps"))
        assertEquals(3, field("gestureFingerCount"))
    }

    @Test fun obsoleteResolutionPreferenceHasNoSliderAndIsNotRewrittenByUnrelatedSave() {
        AirPlayPersistence.saveDisplayScalePercent(activity, 157)
        invoke("openSettingsMenu")
        val labels = views(menu()).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertFalse(labels.contains(activity.getString(R.string.resolution)))
        assertFalse(views(menu()).filterIsInstance<SeekBar>().any { it.max == 130 })
        invoke("persistMenuSettings")
        assertEquals(157, AirPlayPersistence.loadDisplayScalePercent(activity))
        invoke("cancelSettingsEdits")
    }

    @Test fun resumingWithTheMenuOpenPreservesUnsavedConnectionEdits() {
        invoke("openSettingsMenu")
        setField("wirelessHotspotMode", WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)
        setField("mfiTarget", MfiTarget.LOCAL)
        invoke("onResume")
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, field("wirelessHotspotMode"))
        assertEquals(MfiTarget.LOCAL, field("mfiTarget"))
        invoke("cancelSettingsEdits")
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, field("wirelessHotspotMode"))
        assertEquals(MfiTarget.USB_CH341, field("mfiTarget"))
    }

    @Test fun waitingScreenFollowsTheAppAppearanceIndependentlyOfCarPlayNightMode() {
        val title = field("stageStatusView") as TextView
        for ((appearance, night) in listOf(AppAppearance.LIGHT to false, AppAppearance.DARK to true)) {
            setField("darkMode", !night)
            AirPlayPersistence.saveAppAppearance(activity, appearance)
            invoke("refreshAppAppearance")
            assertEquals(WaitingScreenColors.of(night).text, title.currentTextColor)
        }
    }

    @Test fun lightAppearanceRepaintsAnOpenMenuWithoutLosingDraftState() {
        invoke("openSettingsMenu")
        val oldMenu = menu()
        val scroll = views(oldMenu).filterIsInstance<android.widget.ScrollView>().single()
        scroll.scrollTo(0, 120)

        AirPlayPersistence.saveAppAppearance(activity, AppAppearance.LIGHT)
        invoke("refreshAppAppearance")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNotSame(oldMenu, menu())
        assertEquals(false, field("appNight"))
        assertEquals(
            androidx.core.graphics.ColorUtils.setAlphaComponent(DiPlayPalette.LIGHT.background, 224),
            (menu().background as android.graphics.drawable.ColorDrawable).color,
        )
        val volumeLabel = views(menu()).filterIsInstance<TextView>()
            .first { it.text == activity.getString(R.string.settings_navigation_volume) }
        assertEquals(DiPlayPalette.LIGHT.primaryText, volumeLabel.currentTextColor)
    }

    @Test fun savingPersistsSettingsAndRestartsOnce() {
        attachController()
        invoke("openSettingsMenu")
        setField("fps", 30)
        setField("gestureFingerCount", 4)
        invoke("saveSettingsAndReconnect")
        assertFalse(field("menuOpen") as Boolean)
        assertEquals(30, AirPlayPersistence.loadFps(activity))
        assertEquals(4, AirPlayPersistence.loadSettingsGestureFingers(activity))
        assertEquals(1, field("restartGeneration"))
        assertTrue(field("handshakeResetInProgress") as Boolean)
    }

    @Test fun failedStartupWhileMenuIsOpenRecoversOnCancel() {
        attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Authentication failed"))
        assertEquals(0, field("restartGeneration"))
        invoke("cancelSettingsEdits")
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun transportLossWhileMenuIsOpenRecoversOnCancel() {
        attachController()
        invoke("openSettingsMenu")
        val listener = activity.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 0) as AirPlaySessionListener
        listener.onTransportError("transport lost")
        invoke("cancelSettingsEdits")
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun wifiResetFailureRequiresManualRecoveryAfterCancel() {
        attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Wi-Fi needs a reset", wifiResetRequired = true))
        invoke("cancelSettingsEdits")
        assertEquals(View.VISIBLE, (field("wifiRecoveryButton") as View).visibility)
        assertEquals(0, field("restartGeneration"))
        assertFalse(field("reconnectScheduled") as Boolean)
    }

    @Test fun savingWirelessAfterWifiResetFailureStillRequiresManualRecovery() {
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Wi-Fi needs a reset", wifiResetRequired = true))
        invoke("saveSettingsAndReconnect")
        assertEquals(View.VISIBLE, (field("wifiRecoveryButton") as View).visibility)
        assertEquals(0, field("restartGeneration"))
    }

    @Test fun savingWiredModeAfterWifiResetFailureRestartsWithTheNewSettings() {
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Wi-Fi needs a reset", wifiResetRequired = true))
        setField("wirelessEnabled", false)
        invoke("saveSettingsAndReconnect")
        assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun staleFailureDoesNotRestartWhenTheMenuCloses() {
        val controller = attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("old failure"), generation = -1)
        invoke("cancelSettingsEdits")
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
    }

    @Test fun switchingToUsbPersistsAndPassesUsbToTheRuntime() {
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        attachController()
        invoke("openSettingsMenu")
        setField("mfiTarget", MfiTarget.USB_CH341)
        invoke("saveSettingsAndReconnect")
        assertFalse(field("menuOpen") as Boolean)
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(activity))
        val config = invoke("createRuntimeConfig") as CarPlayRuntimeConfig
        assertEquals(MfiTarget.USB_CH341, config.mfiTarget)
        assertEquals(listOf(com.shilapi.xcertplay.transport.UsbDeviceId(0x1a86, 0x5512)), config.ch341Devices)
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun localRuntimeDoesNotRequestCh341Devices() {
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        invoke("loadPersistedSettings")
        val config = invoke("createRuntimeConfig") as CarPlayRuntimeConfig
        assertEquals(MfiTarget.LOCAL, config.mfiTarget)
        assertTrue(config.ch341Devices.isEmpty())
    }

    @Test fun ch341AttachmentKeepsTheWirelessSession() {
        val controller = attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x1a86)
        `when`(device.productId).thenReturn(0x5512)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(activity))
        assertTrue(field("wirelessEnabled") as Boolean)
        assertSame(controller, field("controller"))
        assertFalse((field("shuttingDown") as AtomicBoolean).get())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun iphoneAttachmentSwitchesToWiredTransportWithoutFinishing() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent)
        assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
        assertFalse(field("wirelessEnabled") as Boolean)
        assertFalse((field("shuttingDown") as AtomicBoolean).get())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun switchingToUsbRequestsVpnConsentOnlyOnce() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(Intent("test.VPN_CONSENT"))
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        val method = activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
        method.invoke(activity, intent)
        assertNull(field("controller"))
        assertEquals("test.VPN_CONSENT", shadowOf(activity).nextStartedActivityForResult?.intent?.action)
        method.invoke(activity, intent)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertFalse(activity.isFinishing)
    }

    @Test fun usbAttachmentClosesSettingsAndRestartsInPlace() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        invoke("openSettingsMenu")
        assertTrue(field("menuOpen") as Boolean)
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
            .invoke(activity, intent)
        assertFalse(field("menuOpen") as Boolean)
        assertFalse(field("wirelessEnabled") as Boolean)
        assertNull(field("controller"))
        assertEquals(1, field("restartGeneration"))
        assertFalse(activity.isFinishing)
    }

    @Test fun onlyIphoneAttachmentSelectsWiredTransport() {
        val method = activity.javaClass.getDeclaredMethod("isIphoneUsbAttachment", Intent::class.java)
            .apply { isAccessible = true }
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val attached = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        assertEquals(true, method.invoke(activity, attached))
        assertEquals(false, method.invoke(activity, Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)))
        assertEquals(false, method.invoke(activity, Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, device)))
    }

    @Test fun cancelRestoresSafeAreaResetAfterTheWindowChangesSize() {
        val original = SafeAreaRect(20, 20, 1800, 900)
        AirPlayPersistence.saveSafeAreaRect(activity, 1920, 942, original)
        invoke("openSettingsMenu")
        resizeWindow(1920, 942)
        invoke("resetSafeAreaForCurrentSize")
        assertNull(AirPlayPersistence.loadSafeAreaRect(activity, 1920, 942))

        invoke("cancelSettingsEdits")

        assertEquals(original, AirPlayPersistence.loadSafeAreaRect(activity, 1920, 942))
    }

    @Test fun cancelRemovesNewSafeAreaSavedAtAnotherWindowSize() {
        invoke("openSettingsMenu")
        resizeWindow(1920, 942)
        invoke("openSafeAreaEditor")
        val editor = field("safeAreaEditorView") as SafeAreaEditorView
        editor.setRect(SafeAreaRect(20, 20, 1800, 900), 1920, 942)
        invoke("saveSafeAreaEditor")
        assertNotNull(AirPlayPersistence.loadSafeAreaRect(activity, 1920, 942))

        invoke("cancelSettingsEdits")

        assertNull(AirPlayPersistence.loadSafeAreaRect(activity, 1920, 942))
    }

    @Test fun savingKeepsSafeAreaEditsMadeAfterAWindowResize() {
        invoke("openSettingsMenu")
        resizeWindow(1920, 942)
        invoke("openSafeAreaEditor")
        val edited = SafeAreaRect(20, 20, 1800, 900)
        (field("safeAreaEditorView") as SafeAreaEditorView).setRect(edited, 1920, 942)
        invoke("saveSafeAreaEditor")

        invoke("saveSettingsAndReconnect")

        assertFalse(field("menuOpen") as Boolean)
        assertEquals(edited, AirPlayPersistence.loadSafeAreaRect(activity, 1920, 942))
    }

    private fun resizeWindow(width: Int, height: Int) {
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, height)
        setField("activeDisplaySize", size)
    }

    private fun gesture(fingers: Int, x: Float = 400f, y: Float = 700f) {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        for (count in 2..fingers) touch(MotionEvent.ACTION_POINTER_DOWN, count, 100f)
        touch(MotionEvent.ACTION_MOVE, fingers, y, x)
        touch(MotionEvent.ACTION_UP, 1, y, x)
    }

    private fun touch(action: Int, count: Int, y: Float, x: Float = 400f) {
        val pointers = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val positions = Array(count) { i -> MotionEvent.PointerCoords().apply { this.x = x + i * 50f; this.y = y; pressure = 1f; size = 1f } }
        val indexedAction = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event = MotionEvent.obtain(0, 10, indexedAction, count, pointers, positions, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try {
            activity.javaClass.getDeclaredMethod("onHostTouch", View::class.java, MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(activity, field("gestureOverlay"), event)
        } finally { event.recycle() }
    }

    private fun attachController(): CarPlayController = CarPlayController(
        activity, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
        AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 990)),
        AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
    ).also { setField("controller", it) }

    @Suppress("UNCHECKED_CAST")
    private fun report(status: CarPlayStatus, generation: Int = 0) {
        val reporter = activity.javaClass.getDeclaredMethod("createStatusReporter", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, generation) as (CarPlayStatus) -> Unit
        reporter(status)
    }

    private fun menu() = field("settingsMenu") as View
    private fun mountMenuForTouch() {
        if (field("menuOpen") as Boolean) invoke("cancelSettingsEdits")
        val root = invoke("buildContentView") as View
        activity.window.setContentView(root)
        invoke("openSettingsMenu")
        (field("connectionPanel") as View).visibility = View.GONE
        repeat(3) {
            views(root).forEach { it.forceLayout() }
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
        }
    }
    private fun menuBounds(view: View): android.graphics.Rect =
        android.graphics.Rect(0, 0, view.width, view.height).also {
            (menu() as ViewGroup).offsetDescendantRectToMyCoords(view, it)
        }
    private fun menuTouch(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(10, 30, action, x, y, 0)
        try { menu().dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun tapMenu(x: Float, y: Float) {
        menuTouch(MotionEvent.ACTION_DOWN, x, y)
        menuTouch(MotionEvent.ACTION_UP, x, y)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun fullSettingsButton() = views(menu()).filterIsInstance<Button>()
        .single { it.text == activity.getString(R.string.settings_all_settings) }
    private fun homeButton() = views(menu()).filterIsInstance<Button>()
        .single { it.text == activity.getString(R.string.settings_return_to_home) }
    /** Answers the "Discard your changes?" dialog that an exit with staged edits now shows. */
    private fun discardPendingEdits() {
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("leaving the menu with a pending edit should ask before discarding")
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
    private fun invoke(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
