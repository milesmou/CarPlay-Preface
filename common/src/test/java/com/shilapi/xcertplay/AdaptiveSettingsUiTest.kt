package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class AdaptiveSettingsUiTest {
    @Test
    @Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullWheelLearningLargeFontScreenshot() = captureWheelLearning("full")

    @Test
    @Config(sdk = [28], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactWheelLearningLargeFontScreenshot() = captureWheelLearning("compact")

    private fun captureWheelLearning(size: String) {
        context.resources.updateConfiguration(android.content.res.Configuration(context.resources.configuration).apply {
            fontScale = 1.5f
        }, context.resources.displayMetrics)
        val screen = openSettings()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.VEHICLE))
        val frame = screen.findViewById<ViewGroup>(android.R.id.content)
        repeat(3) {
            descendants(frame).forEach { it.forceLayout() }
            frame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 1920, 720)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val directory = java.io.File("../agent_docs/screenshots/wheel-learning-status").apply { mkdirs() }
        for ((part, target) in listOf("keys" to texts(screen).first {
            it.text.startsWith(screen.getString(R.string.settings_wheel_previous) + " · ")
        }, "status" to texts(screen).first { it.text == screen.getString(R.string.settings_wheel_takeover_title) })) {
            val rect = android.graphics.Rect(0, 0, target.width, target.height)
            scroll.offsetDescendantRectToMyCoords(target, rect)
            scroll.scrollTo(0, rect.top)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, screen.resources.configuration.orientation)
            val bitmap = android.graphics.Bitmap.createBitmap(1920, 720, android.graphics.Bitmap.Config.ARGB_8888)
            frame.draw(android.graphics.Canvas(bitmap))
            java.io.File(directory, "$size-$part-font150.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }

    @Test fun mediaWheelLearningCanLearnDisableAndRestoreWithoutChangingTheConnection() {
        val screen = openSettings()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.VEHICLE))
        fun menu(index: Int) {
            texts(screen).single { it.text.startsWith(screen.getString(R.string.settings_wheel_next) + " · ") }.performClick()
            shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(index)
            shadowOf(Looper.getMainLooper()).idle()
        }
        menu(0)
        assertTrue(WheelMediaLearning.active(screen))
        assertTrue(screen.dispatchKeyEvent(android.view.KeyEvent(100, 100, android.view.KeyEvent.ACTION_DOWN,
            android.view.KeyEvent.KEYCODE_F1, 0)))
        assertEquals(android.view.KeyEvent.KEYCODE_F1, WheelMediaBindings.key(context, WheelMediaBindings.Action.NEXT))
        menu(2)
        assertNull(WheelMediaBindings.key(context, WheelMediaBindings.Action.NEXT))
        menu(1)
        assertEquals(87, WheelMediaBindings.key(context, WheelMediaBindings.Action.NEXT))
        val status = texts(screen).single { it.text.startsWith("输入申请：") }.text.toString()
        assertTrue(status.contains("已收到观察回调：0 次"))
        assertTrue(status.contains("独占处理：未确认"))
        assertEquals(null, shadowOf(screen).nextStartedActivity)
    }

    @Test
    @Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullBluetoothMusicLargeFontScreenshot() = captureBluetoothMusic("full")

    @Test
    @Config(sdk = [28], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactBluetoothMusicLargeFontScreenshot() = captureBluetoothMusic("compact")

    private fun captureBluetoothMusic(size: String) {
        context.resources.updateConfiguration(android.content.res.Configuration(context.resources.configuration).apply {
            fontScale = 1.5f
        }, context.resources.displayMetrics)
        val screen = openSettings()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.EXPERIMENTAL))
        val frame = screen.findViewById<ViewGroup>(android.R.id.content)
        repeat(3) {
            descendants(frame).forEach { it.forceLayout() }
            frame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 1920, 720)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val title = screen.getString(R.string.settings_music_wheel_mode)
        assertEquals(MusicWheelMode.CARPLAY_ADAPTIVE_VOLUME, MusicWheelMode.load(context))
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val target = texts(screen).first { it.text.startsWith("$title · ") }
        val rect = android.graphics.Rect(0, 0, target.width, target.height)
        scroll.offsetDescendantRectToMyCoords(target, rect)
        scroll.scrollTo(0, rect.top)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, screen.resources.configuration.orientation)
        val bitmap = android.graphics.Bitmap.createBitmap(1920, 720, android.graphics.Bitmap.Config.ARGB_8888)
        frame.draw(android.graphics.Canvas(bitmap))
        val directory = java.io.File("../agent_docs/screenshots/bluetooth-music-wheel").apply { mkdirs() }
        java.io.File(directory, "$size-font150.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun bluetoothMusicSwitchSavesForNextConnectionWithoutStoppingTheCurrentSession() {
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, false, false, 1920, 720)) { stops++ }
        try {
            CarPlayBackgroundSession.active = true
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.EXPERIMENTAL)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val setting = texts(screen).single { it.text.startsWith(screen.getString(R.string.settings_music_wheel_mode) + " · ") }
            assertFalse(descendants(screen.window.decorView).filterIsInstance<Switch>().any {
                it.contentDescription == screen.getString(R.string.settings_car_bluetooth_audio) ||
                    it.contentDescription == screen.getString(R.string.settings_navigation_wheel_volume)
            })
            setting.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(3, dialog.listView.adapter.count)
            assertEquals(MusicWheelMode.CARPLAY_ADAPTIVE_VOLUME.ordinal, dialog.listView.checkedItemPosition)
            shadowOf(dialog).clickOnItem(MusicWheelMode.CAR_BLUETOOTH.ordinal)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AirPlayPersistence.loadCarBluetoothAudio(context))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(0, stops)
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(null, shadowOf(screen).nextStartedActivity)
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactConnectionWithoutUsbAutoConfirmLargeFontScreenshot() = captureWithoutUsbAutoConfirm("compact")

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullConnectionWithoutUsbAutoConfirmLargeFontScreenshot() = captureWithoutUsbAutoConfirm("full")

    private fun captureWithoutUsbAutoConfirm(size: String) {
        val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.5f }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        // A previously enabled component must not restore the removed feature or its status row.
        android.provider.Settings.Secure.putString(context.contentResolver, "enabled_accessibility_services",
            "${context.packageName}/com.shilapi.xcertplay.UsbAutoConfirmService")
        android.provider.Settings.Secure.putInt(context.contentResolver, "accessibility_enabled", 1)
        val screen = openSettings()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.CONNECTION))
        val content = screen.findViewById<ViewGroup>(android.R.id.content)
        repeat(3) {
            descendants(content).forEach { it.forceLayout() }
            content.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, 1920, 720)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, screen.resources.configuration.orientation)
        assertEquals(1920, content.width); assertEquals(720, content.height)
        for (title in listOf("自动确认 USB 权限", "USB 弹窗自动确认", "无障碍服务已激活", "设置 USB 与地图权限")) {
            assertFalse(texts(screen).any { it.text.toString().contains(title) })
        }
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.automatic_connection) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_usb_connection) })
        val services = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES).services.orEmpty()
        assertFalse(services.any { it.name.endsWith(".UsbAutoConfirmService") })
        assertTrue(services.any { it.name.endsWith(".WheelKeyService") })
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val target = texts(screen).first { it.text == screen.getString(R.string.automatic_connection) }
        val rect = android.graphics.Rect(0, 0, target.width, target.height)
        scroll.offsetDescendantRectToMyCoords(target, rect)
        scroll.scrollTo(0, rect.top)
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = android.graphics.Bitmap.createBitmap(1920, 720, android.graphics.Bitmap.Config.ARGB_8888)
        content.draw(android.graphics.Canvas(bitmap))
        val directory = java.io.File("../agent_docs/screenshots/remove-usb-auto-confirm").apply { mkdirs() }
        java.io.File(directory, "$size-font150.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactAdvancedWithoutHotspotJoinLargeFontScreenshot() = captureAdvancedWithoutHotspotJoin("compact")

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullAdvancedWithoutHotspotJoinLargeFontScreenshot() = captureAdvancedWithoutHotspotJoin("full")

    private fun captureAdvancedWithoutHotspotJoin(size: String) {
        val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.5f }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        val screen = openSettings()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.EXPERIMENTAL))
        layoutRoot(screen)
        assertFalse(texts(screen).any { it.text.toString().contains("自动加入热点") })
        assertFalse(texts(screen).any { it.text.toString().contains("检查热点修复") })
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertEquals(1920, root.width)
        assertEquals(720, root.height)
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        val directory = java.io.File("../agent_docs/screenshots/remove-hotspot-join").apply { mkdirs() }
        java.io.File(directory, "$size-font150.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactAdvancedWithoutExternalControllerLargeFontScreenshot() = captureAdvancedWithoutExternalController("compact")

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w1280dp-h720dp-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullAdvancedWithoutExternalControllerLargeFontScreenshot() = captureAdvancedWithoutExternalController("full")

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactVolumeWithoutPermissionButtonsLargeFontScreenshot() = captureAdvancedWithoutExternalController("compact", volume = true)

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullVolumeWithoutPermissionButtonsLargeFontScreenshot() = captureAdvancedWithoutExternalController("full", volume = true)

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactAdvancedWithoutClusterMapLargeFontScreenshot() =
        captureAdvancedWithoutExternalController("compact", withoutMap = true)

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullAdvancedWithoutClusterMapLargeFontScreenshot() =
        captureAdvancedWithoutExternalController("full", withoutMap = true)

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactAdvancedWithoutDisplayCardLargeFontScreenshot() =
        captureAdvancedWithoutExternalController("compact", withoutDisplay = true)

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullAdvancedWithoutDisplayCardLargeFontScreenshot() =
        captureAdvancedWithoutExternalController("full", withoutDisplay = true)

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactConnectionWithoutPhoneAndHelpCardsLargeFontScreenshot() =
        captureAdvancedWithoutExternalController("compact", withoutPhone = true)

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullConnectionWithoutPhoneAndHelpCardsLargeFontScreenshot() =
        captureAdvancedWithoutExternalController("full", withoutPhone = true)

    private fun captureAdvancedWithoutExternalController(size: String, volume: Boolean = false,
        withoutMap: Boolean = false, withoutDisplay: Boolean = false, withoutPhone: Boolean = false) {
        val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.5f }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        if (withoutMap) context.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("cluster_map_enabled", true).putBoolean("center_map_overlay", true).commit()
        if (withoutDisplay) for (name in listOf("diplay_split_screen", "diplay_carplay_rotation", "diplay_side_panel")) {
            context.getSharedPreferences(name, 0).edit().putBoolean("enabled", true).commit()
        }
        val screen = openSettings()
        if (withoutPhone) ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java,
                if (withoutPhone) SettingsCategory.CONNECTION else SettingsCategory.EXPERIMENTAL))
        layoutRoot(screen)
        assertFalse(texts(screen).any { it.text.toString().contains("外接控制器") })
        if (withoutMap) {
            assertFalse(texts(screen).any { it.text.toString().contains("仪表盘上的CarPlay地图") })
            assertFalse(texts(screen).any { it.text.toString().contains("共享实时地图") })
        }
        if (withoutDisplay) {
            for (title in listOf("显示（实验性）", "分屏中的 CarPlay", "随屏幕旋转 CarPlay", "侧边面板")) {
                assertFalse(texts(screen).any { it.text.toString().contains(title) })
            }
        }
        if (withoutPhone) {
            assertFalse(texts(screen).any { it.text.toString().contains("选择你的 iPhone") })
            assertEquals(View.GONE, ReflectionHelpers.getField<LinearLayout>(screen, "readinessCard").visibility)
            for (title in listOf(screen.getString(R.string.permissions_and_connection_help),
                screen.getString(R.string.app_permissions), screen.getString(R.string.bluetooth_settings), "无线连接帮助")) {
                assertFalse("Removed help card: $title", texts(screen).any { it.text == title })
            }
        }
        if (volume) {
            assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_music_wheel_mode) })
            for (id in listOf(R.string.wheel_keys_open_settings, R.string.wheel_keys_enable_adb)) {
                assertFalse(texts(screen).any { it.text == screen.getString(id) })
            }
        }
        val contentFrame = screen.findViewById<ViewGroup>(android.R.id.content)
        val root = contentFrame.getChildAt(0)
        if (volume || withoutMap || withoutDisplay || withoutPhone) {
            repeat(3) {
                descendants(contentFrame).forEach { it.forceLayout() }
                contentFrame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                contentFrame.layout(0, 0, 1920, 720)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, screen.resources.configuration.orientation)
            assertEquals(1920, root.width)
            assertEquals(720, root.height)
        }
        if (volume) {
            val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
            val target = texts(screen).first { it.text == screen.getString(R.string.settings_music_wheel_mode) }
            val rect = android.graphics.Rect(0, 0, target.width, target.height)
            scroll.offsetDescendantRectToMyCoords(target, rect)
            scroll.scrollTo(0, rect.top)
            shadowOf(Looper.getMainLooper()).idle()
        }
        if (withoutPhone) {
            val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
            scroll.scrollTo(0, scroll.getChildAt(0).height)
        }
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        // Draw through the parent so a compact root ScrollView's own scroll is applied.
        (if (volume || withoutMap || withoutDisplay || withoutPhone) contentFrame else root).draw(android.graphics.Canvas(bitmap))
        val directory = java.io.File("../agent_docs/screenshots/" +
            when {
                withoutPhone -> "connection-without-phone-and-help-cards"
                withoutDisplay -> "remove-experimental-display"
                withoutMap -> "remove-cluster-map"
                volume -> "remove-volume-permission-buttons"
                else -> "remove-external-controller"
            }).apply { mkdirs() }
        java.io.File(directory, "$size-font150.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullDisplayWithFixedResolutionLargeFontScreenshot() = captureDisplayWithFixedResolution("full")

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactDisplayWithFixedResolutionLargeFontScreenshot() = captureDisplayWithFixedResolution("compact")

    private fun captureDisplayWithFixedResolution(size: String) {
        context.resources.updateConfiguration(android.content.res.Configuration(context.resources.configuration).apply {
            fontScale = 1.5f
        }, context.resources.displayMetrics)
        context.getSharedPreferences("xcertplay_airplay", 0).edit().putBoolean("adapt_pip_resolution", true).commit()
        val screen = openSettings()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.DISPLAY))
        val frame = screen.findViewById<ViewGroup>(android.R.id.content)
        repeat(3) {
            descendants(frame).forEach { it.forceLayout() }
            frame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 1920, 720)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, screen.resources.configuration.orientation)
        assertFalse(texts(screen).any { it.text.startsWith(screen.getString(R.string.resolution) + " · ") })
        assertTrue(texts(screen).any { it.text.toString() == screen.getString(R.string.settings_fixed_resolution_hint) })
        assertTrue(texts(screen).any { it.text.startsWith(screen.getString(R.string.frame_rate) + " · ") })
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val navigationTitle = texts(screen).first { it.text.toString() == screen.getString(R.string.hide_the_navigation_bar) }
        val target = android.graphics.Rect()
        navigationTitle.getDrawingRect(target)
        scroll.offsetDescendantRectToMyCoords(navigationTitle, target)
        scroll.scrollTo(0, (target.top - 24).coerceAtLeast(0))
        val bitmap = android.graphics.Bitmap.createBitmap(1920, 720, android.graphics.Bitmap.Config.ARGB_8888)
        frame.draw(android.graphics.Canvas(bitmap))
        val directory = java.io.File("../agent_docs/screenshots/fixed-resolution").apply { mkdirs() }
        java.io.File(directory, "$size-font150.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test
    @Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullUpstreamAudioLargeFontScreenshot() = captureUpstreamAudio("full")

    @Test
    @Config(sdk = [28], qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactUpstreamAudioLargeFontScreenshot() = captureUpstreamAudio("compact")

    private fun captureUpstreamAudio(size: String) {
        context.resources.updateConfiguration(android.content.res.Configuration(context.resources.configuration).apply {
            fontScale = 1.5f
        }, context.resources.displayMetrics)
        val screen = openSettings()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.AUDIO))
        val frame = screen.findViewById<ViewGroup>(android.R.id.content)
        repeat(3) {
            descendants(frame).forEach { it.forceLayout() }
            frame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 1920, 720)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(texts(screen).any { it.text.startsWith(screen.getString(R.string.settings_music_buffer_wireless) + " · ") })
        assertTrue(texts(screen).any { it.text.startsWith(screen.getString(R.string.settings_music_buffer_wired) + " · ") })
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        val bitmap = android.graphics.Bitmap.createBitmap(1920, 720, android.graphics.Bitmap.Config.ARGB_8888)
        frame.draw(android.graphics.Canvas(bitmap))
        val directory = java.io.File("../agent_docs/screenshots/upstream-0.2.20").apply { mkdirs() }
        java.io.File(directory, "$size-font150.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @Test
    @Config(sdk = [29], qualifiers = "ja-w320dp-h600dp")
    fun chineseSettingsDoNotEllipsizeOnNarrowScreensWithAnotherSystemLanguage() {
        val screen = openSettings()
        assertEquals("连接", screen.getString(R.string.connection))

        fun verifyVisibleText() {
            layoutRoot(screen)
            for (view in texts(screen)) {
                if (view.visibility != View.VISIBLE) continue
                val lines = view.layout ?: continue
                for (line in 0 until lines.lineCount) {
                    assertEquals("Truncated Chinese label: ${view.text}", 0, lines.getEllipsisCount(line))
                }
            }
        }

        verifyVisibleText()
        for (category in SettingsCategory.entries) {
            ReflectionHelpers.callInstanceMethod<Unit>(
                screen, "openSettingsCategory",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category),
            )
            verifyVisibleText()
        }
    }

    @After fun tearDown() {
        activity?.finish()
        activity?.let { WheelMediaLearning.cancel(it) }
        WheelMediaLearning.resetForTest()
        // The learnt press has a short echo quarantine; expire it before another screen/service test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        AppAppearanceRuntime.resetForTest()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        context.getSharedPreferences("carplay_audio_volume", 0).edit().clear().commit()
        context.getSharedPreferences("diplay_navigation_wheel", 0).edit().clear().commit()
        context.getSharedPreferences(WheelMediaBindings.PREFS, 0).edit().clear().commit()
        for (name in listOf("diplay_split_screen", "diplay_carplay_rotation", "diplay_side_panel")) {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    @Test fun compactSettingsOpenDirectlyOnConnection() {
        val screen = openSettings()

        assertEquals(SettingsCategory.CONNECTION, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_overview) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun audioSettingsHaveNoCarPlayVolumeSliders() {
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.AUDIO)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertFalse(texts(screen).any { it.text.startsWith("媒体音量 · ") || it.text.startsWith("导航音量 · ") })
        assertFalse(descendants(screen.window.decorView).any { it is android.widget.SeekBar })
        assertTrue(texts(screen).any { it.text.contains(screen.getString(R.string.music_buffer)) })
    }

    @Test fun savedLightAppearanceThemesTheSettingsCanvasAndSystemBars() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        val screen = openSettings()
        val palette = ReflectionHelpers.getField<DiPlayPalette>(screen, "palette")
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")

        assertSame(DiPlayPalette.LIGHT, palette)
        assertEquals(DiPlayPalette.LIGHT.background, (scroll.background as ColorDrawable).color)
        assertEquals(
            DiPlayPalette.LIGHT.background,
            (screen.window.decorView.background as ColorDrawable).color,
        )
        assertEquals(DiPlayPalette.LIGHT.systemBar, screen.window.navigationBarColor)
    }

    @Test fun headerShortcutTogglesAppearanceAndReturnsFocusToItself() {
        val screen = openSettings()
        val switchToLight = screen.getString(R.string.settings_switch_to_light_appearance)
        val button = descendants(screen.window.decorView).single {
            it.contentDescription == switchToLight
        }
        assertEquals(Math.round(52 * screen.resources.displayMetrics.density), button.layoutParams.width)
        assertTrue(button.requestFocus())

        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        layoutRoot(screen)

        assertEquals(AppAppearance.LIGHT, AirPlayPersistence.loadAppAppearance(screen))
        assertSame(DiPlayPalette.LIGHT, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        val replacement = descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_switch_to_dark_appearance)
        }
        assertTrue(replacement.isFocused)
    }

    @Test fun autoAppearanceRepaintsWhenTheCarPlayPolicyChanges() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.AUTO)
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.DAY)
        val screen = openSettings()
        assertSame(DiPlayPalette.LIGHT, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))

        AirPlayPersistence.saveCarPlayNightMode(screen, CarPlayNightMode.NIGHT)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertSame(DiPlayPalette.DARK, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        assertEquals(DiPlayPalette.DARK.background, (scroll.background as ColorDrawable).color)
    }

    @Test fun hostAppearancePublishedOffMainRepaintsOnMain() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.AUTO)
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.DAY)
        val screen = openSettings()
        assertSame(DiPlayPalette.LIGHT, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        val owner = Any()
        val failure = AtomicReference<Throwable?>()

        val publisher = Thread {
            try {
                AppAppearanceRuntime.publishHost(owner, true)
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }
        publisher.start()
        publisher.join()

        assertNull(failure.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(DiPlayPalette.DARK, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        AppAppearanceRuntime.clearHost(owner)
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w500dp-h400dp")
    fun appearanceRepaintKeepsTheCurrentSettingsPageAndScrollPosition() {
        val screen = openSettings()
        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(
                R.string.settings_open_category,
                screen.getString(R.string.settings_display),
            )
        }.performClick()
        layoutRoot(screen)
        val oldScroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        oldScroll.scrollTo(0, oldScroll.getChildAt(0).height)
        val previousY = oldScroll.scrollY
        assertTrue(previousY > 0)

        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_switch_to_light_appearance)
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        layoutRoot(screen)

        assertEquals(SettingsCategory.DISPLAY, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertEquals(previousY, ReflectionHelpers.getField<ScrollView>(screen, "rootScroll").scrollY)
    }

    @Test fun openDialogKeepsItsAppearanceAndEditsWhileTheScreenRepaints() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        val screen = openSettings()
        // Search is an inline box now; any themed text dialog shows the same behaviour.
        DiPlayActivity::class.java.getDeclaredMethod("textInput", String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Function1::class.java).apply { isAccessible = true }
            .invoke(screen, screen.getString(R.string.settings_search), "", false, { _: String -> })
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText("display")
        val dialogAccent = dialog.context.theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorAccent),
        ).let { attributes ->
            try {
                attributes.getColor(0, 0)
            } finally {
                attributes.recycle()
            }
        }

        AirPlayPersistence.saveAppAppearance(screen, AppAppearance.DARK)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "checkForAppearanceChange")

        assertTrue(dialog.isShowing)
        assertEquals("display", input.text.toString())
        assertEquals(DiPlayPalette.LIGHT.accent, dialogAccent)
        assertSame(DiPlayPalette.DARK, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
    }

    @Config(qualifiers = "en-w700dp-h400dp-land")
    @Test fun fullSettingsOverrideIsIndependentAndCanBeDisabledFromDisplay() {
        val screen = openSettings()
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.DISPLAY)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        fun settingsSwitch() = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.settings_force_full_settings) }

        settingsSwitch().isChecked = true
        assertTrue(SettingsLayoutPreferences.forceFull(context))
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))

        settingsSwitch().isChecked = false
        assertFalse(SettingsLayoutPreferences.forceFull(context))
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))
    }

    @Config(qualifiers = "en-w400dp-h700dp-port")
    @Test fun portraitIgnoresFullSettingsOverrideWithoutClearingPreference() {
        SettingsLayoutPreferences.saveForceFull(context, true)
        val screen = openSettings()
        assertFalse(SettingsLayoutPreferences.isActive(screen))
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))
        assertTrue(SettingsLayoutPreferences.forceFull(context))
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h700dp-land")
    fun naturallyExpandedSettingsHideTheForceLayoutSwitch() {
        SettingsLayoutPreferences.saveForceFull(context, true)
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.DISPLAY)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertFalse(descendants(screen.window.decorView).filterIsInstance<Switch>().any {
            it.contentDescription == screen.getString(R.string.settings_force_full_settings)
        })
    }

    @Test fun connectionPreferencesDoNotDuplicateHomePhonePairing() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        val screen = openSettings()
        // Robolectric has no CarPlay authentication assets, which is a setup error of its own.
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")

        assertFalse(texts(screen).any { it.text.toString().contains("选择你的 iPhone") })
        assertEquals(View.GONE, ReflectionHelpers.getField<LinearLayout>(screen, "readinessCard").visibility)
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.ready) })
    }

    @Test fun homeOffersConnectedPhonesWithoutSavedHotspotCredentials() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        DiPlayPreferences.savePhone(context, "AA:BB:CC:DD:EE:FF", "My iPhone")
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "home"))
            .setup().get().also { activity = it }
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.setField(screen, "connectedPhoneState", ConnectedPhoneState(true, true,
            listOf(ConnectedPhone("AA:BB:CC:DD:EE:FF", "My iPhone"))))
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")

        assertTrue(texts(screen).any { it.text.toString() == "My iPhone · ${screen.getString(R.string.home_bluetooth_connected)}" && it.isEnabled })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.choose_iphone) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_connect_usb) })
        assertFalse(texts(screen).any { it.text.toString().contains("选择你的 iPhone") })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.ready) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.setup_needs_attention) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.open_connection_setup) })
        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun searchOpensTheCategoryThatOwnsTheSetting() {
        val screen = openSettings()
        val index = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "buildSettingsSearchIndex")
        val results = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "searchSettings",
            ReflectionHelpers.ClassParameter(List::class.java, index),
            ReflectionHelpers.ClassParameter(String::class.java, screen.getString(R.string.frame_rate)))
        val frameRate = results.single {
            ReflectionHelpers.getField<String>(it, "title") == screen.getString(R.string.frame_rate)
        }

        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSearchResult",
            ReflectionHelpers.ClassParameter(frameRate.javaClass, frameRate))

        assertEquals(SettingsCategory.DISPLAY, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertTrue(texts(screen).any { it.text.startsWith(screen.getString(R.string.frame_rate) + " · ") })
    }

    @Config(sdk = [28])
    @Test fun headerSearchBoxListsMatchesAsYouTypeAndOpensTheChosenSetting() {
        val screen = openSettings()
        fun box() = descendants(screen.window.decorView).filterIsInstance<EditText>()
            .single { it.contentDescription == screen.getString(R.string.settings_search) }
        assertTrue(descendants(screen.window.decorView).none { it is EditText })
        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(box().hasFocus())

        val editor = box()
        box().setText(screen.getString(R.string.frame_rate))
        shadowOf(Looper.getMainLooper()).idle()

        // Typing and clearing a query must keep the keyboard's original editor.
        assertSame(editor, box())
        box().setText("")
        shadowOf(Looper.getMainLooper()).idle()
        box().setText(screen.getString(R.string.frame_rate))
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(editor, box())
        assertEquals(screen.getString(R.string.frame_rate), box().text.toString())
        assertTrue(box().hasFocus())
        val popup = ReflectionHelpers.getField<PopupWindow>(screen, "settingsSearchPopup")
        assertTrue(popup.isShowing)
        val row = descendants(popup.contentView).first {
            it.isClickable && it.contentDescription?.startsWith(screen.getString(R.string.frame_rate) + ", ") == true
        }
        row.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(popup.isShowing)
        assertEquals(SettingsCategory.DISPLAY, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertTrue(texts(screen).any { it.text.startsWith(screen.getString(R.string.frame_rate) + " · ") })
        assertTrue(descendants(screen.window.decorView).none { it is EditText })
    }

    @Test fun backClosesAnOpenSearchBeforeLeavingSettings() {
        val screen = openSettings()
        fun box() = descendants(screen.window.decorView).filterIsInstance<EditText>()
            .single { it.contentDescription == screen.getString(R.string.settings_search) }
        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        box().setText("no such setting zz")
        shadowOf(Looper.getMainLooper()).idle()
        val popup = ReflectionHelpers.getField<PopupWindow>(screen, "settingsSearchPopup")
        assertTrue(descendants(popup.contentView).filterIsInstance<TextView>()
            .any { it.text == screen.getString(R.string.settings_search_empty) })

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertFalse(popup.isShowing)
        assertTrue(descendants(screen.window.decorView).none { it is EditText })
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun rebuiltSearchFieldsDoNotLeaveLayoutListenersOnTheWindow() {
        val screen = openSettings()
        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        fun globalLayoutListeners(): Int {
            val listeners = ReflectionHelpers.getField<Any?>(screen.window.decorView.viewTreeObserver, "mOnGlobalLayoutListeners")
                ?: return 0
            return ReflectionHelpers.callInstanceMethod<Int>(listeners, "size")
        }
        val before = globalLayoutListeners()
        repeat(5) {
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(descendants(screen.window.decorView).any { it is EditText })
        assertEquals(before, globalLayoutListeners())
    }

    @Test
    @Config(qualifiers = "en-w900dp-h600dp-land")
    fun backCollapsesAnEmptySearchAndFullLayoutKeepsItsExistingLabel() {
        val screen = openSettings()
        val button = descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }
        assertTrue(button is android.widget.Button)
        assertEquals(screen.getString(R.string.settings_search_hint), (button as TextView).text.toString())
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(descendants(screen.window.decorView).any { it is EditText && it.hasFocus() })
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertTrue(descendants(screen.window.decorView).none { it is EditText })
        assertTrue(descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }.hasFocus())
    }

    @Test
    @Config(qualifiers = "en-w500dp-h400dp-land")
    fun compactSearchUsesAnIconTheSameSizeAsAppearanceAndCollapsesAfterBack() {
        val screen = openSettings()
        fun search() = descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }
        val appearance = descendants(screen.window.decorView).single { it.tag == "app_appearance_button" }
        assertTrue(search() is android.widget.ImageButton)
        assertEquals(appearance.layoutParams.width, search().layoutParams.width)
        assertEquals(appearance.layoutParams.height, search().layoutParams.height)
        search().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(search() is EditText)
        screen.onBackPressedDispatcher.onBackPressed()
        assertTrue(search() is android.widget.ImageButton)
        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
    }





    @Test fun cardControlsShareOneHeightAndGap() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .first { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()
        val density = screen.resources.displayMetrics.density
        fun dp(value: Int) = Math.round(value * density)
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")

        val buttons = descendants(scroll).filterIsInstance<android.widget.Button>()
            .filter { it !is Switch && (it.parent as? LinearLayout)?.orientation == LinearLayout.VERTICAL }
            .toList()

        assertTrue(buttons.size >= 4)
        buttons.forEach { button ->
            val lp = button.layoutParams as LinearLayout.LayoutParams
            assertEquals(button.text.toString(), dp(60), lp.height)
            assertEquals(button.text.toString(), 0, lp.topMargin)
            assertTrue(button.text.toString(), lp.bottomMargin in setOf(0, dp(6), dp(12)))
        }
        val page = scroll.getChildAt(0)
        assertFalse(descendants(scroll).any { it.tag == "spacer" && it.parent !== page })
    }

    @Test fun searchNamesOpenTheirOwnCategoryNotACardThatLinksToIt() {
        val screen = openSettings()
        val index = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "buildSettingsSearchIndex")
        val advanced = index.single { ReflectionHelpers.getField<String>(it, "title") == screen.getString(R.string.settings_advanced) }

        assertEquals(SettingsCategory.EXPERIMENTAL, ReflectionHelpers.getField<SettingsCategory>(advanced, "category"))
    }

    @Test fun aRowBeforeAButtonKeepsTheGap() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .first { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_vehicle)) }
            .performClick()
        val chooseImage = texts(screen).single { it.text == screen.getString(R.string.choose_image) }
        val card = chooseImage.parent as LinearLayout
        val preview = card.getChildAt(card.indexOfChild(chooseImage) - 1)

        assertEquals(Math.round(12 * screen.resources.displayMetrics.density),
            (preview.layoutParams as LinearLayout.LayoutParams).bottomMargin)
    }

    @Test fun tappingAToggleRowChangesTheSetting() {
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, false)
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.DISPLAY)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val fullScreen = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.hide_the_status_bar) }

        (fullScreen.parent as View).performClick()

        assertTrue(fullScreen.isChecked)
        assertTrue(AirPlayPersistence.loadHideTopBar(context))
    }

    @Test fun compactCategoryBackReturnsHome() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.display_and_performance) })

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun openingACompactCategoryAlwaysStartsAtTheTop() {
        val screen = openSettings()
        val firstScroll = descendants(screen.window.decorView).filterIsInstance<ScrollView>().single()
        firstScroll.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
        )
        firstScroll.layout(0, 0, 600, 700)
        firstScroll.scrollTo(0, 400)
        assertTrue(firstScroll.scrollY > 0)

        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()

        val detailScroll = descendants(screen.window.decorView).filterIsInstance<ScrollView>().single()
        assertEquals(0, detailScroll.scrollY)
    }

    @Test fun settingsNoLongerLinksToConnectionSetup() {
        val screen = openSettings()

        assertFalse(texts(screen).any { it.text == screen.getString(R.string.open_connection_setup) })

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.home_connected_devices) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun unsupportedRestoredPagesUseHome() {
        val screen = openSettings()
        for (unsupportedPage in listOf("missing", "unknown")) {
            ReflectionHelpers.setField(screen, "page", unsupportedPage)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
            assertFalse(texts(screen).any { it.text == screen.getString(R.string.connection_setup) })
        }
    }
    @Test fun connectionSetupOpenedDirectlyStillReturnsHome() {
        val screen = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "connection"),
        ).setup().get().also { activity = it }

        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun experimentalContainsOnlyExperimentalSections() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { candidate ->
                candidate.contentDescription == screen.getString(
                    R.string.settings_open_category,
                    screen.getString(R.string.settings_advanced),
                )
            }
            .performClick()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_advanced_caution_title) })
        assertFalse(texts(screen).any { it.text.toString().contains("仪表盘上的CarPlay地图") })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.advanced_vehicle_data) })
        assertFalse(texts(screen).any { it.text.toString().contains("External controller") || it.text.toString().contains("外接控制器") })
        assertFalse(texts(screen).any { it.text == "热点加入修复" })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.automatic_connection) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.display_and_performance) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.audio_routing) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.location) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.save_diagnostic_report) })
    }

    @Test fun displayOpensPictureAdjustments() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.picture_adjustments) })
    }

    @Test fun settingsLiveWhereDriversLookForThem() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        val screen = openSettings()
        fun visibleIn(category: Int): List<CharSequence> {
            descendants(screen.window.decorView).first { candidate ->
                candidate.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(category))
            }.performClick()
            return texts(screen).map { it.text }.toList()
        }
        fun text(id: Int) = screen.getString(id)

        val display = visibleIn(R.string.settings_display)
        val audio = visibleIn(R.string.audio)
        val vehicle = visibleIn(R.string.settings_vehicle)
        val advanced = visibleIn(R.string.settings_advanced)
        val connection = visibleIn(R.string.connection)
        val authorization = visibleIn(R.string.settings_authorization)

        for (id in listOf(R.string.settings_authorization_write, R.string.settings_authorization_vpn)) {
            assertTrue(authorization.any { it.startsWith(text(id) + " · ") })
            for (other in listOf(connection, display, audio, vehicle, advanced)) {
                assertFalse(other.any { it.startsWith(text(id) + " · ") })
            }
        }

        assertFalse(advanced.any { it.toString().contains("仪表盘上的CarPlay地图") })
        assertFalse(vehicle.any { it == text(R.string.wheel_map_zoom) })

        assertTrue(connection.any { it.startsWith(text(R.string.settings_usb_charging)) })
        assertTrue(text(R.string.settings_wireless_hotspot) in connection)
        assertTrue(connection.any { it.startsWith(text(R.string.settings_manual_hotspot_details) + " · ") })
        assertFalse(connection.any { it == "热点手动配置" })
        for (other in listOf(display, audio, vehicle, advanced)) {
            assertFalse(other.any { it.startsWith(text(R.string.settings_manual_hotspot_details) + " · ") })
        }
        assertFalse(advanced.any { it.startsWith(text(R.string.settings_usb_charging)) })
        assertTrue(audio.any { it.startsWith(text(R.string.music_buffer)) })
        listOf(R.string.main_buffered_audio, R.string.efficient_video, R.string.smooth_video, R.string.settings_direct_video_output, R.string.settings_low_latency_decoder, R.string.call_echo_cancellation, R.string.call_voice_filter, R.string.settings_disable_media_key_audio_focus, R.string.contrib_audio_home_toggle_audio_focus).forEach {
            assertTrue(text(it), text(it) in advanced)
            assertFalse(text(it), text(it) in audio)
        }
        assertTrue(text(R.string.right_hand_drive) in vehicle)
        assertTrue(text(R.string.car_button_in_carplay) in vehicle)
        assertTrue(vehicle.any { it.startsWith(text(R.string.settings_wheel_siri) + " · ") })
        assertTrue(text(R.string.settings_wheel_keys) in vehicle)
        assertTrue(text(R.string.settings_music_wheel_mode) in advanced)
        assertFalse(text(R.string.settings_music_wheel_mode) in audio)
        assertFalse(text(R.string.settings_music_wheel_mode) in vehicle)
        assertFalse(text(R.string.settings_ambient_title) in advanced)
        assertFalse(text(R.string.settings_ambient_title) in audio)
        assertFalse(text(R.string.settings_ambient_title) in vehicle)
        assertFalse(text(R.string.settings_ambient_title) in display)
        assertFalse(advanced.any { it.toString().contains("显示（实验性）") })
        assertTrue(display.any { it.startsWith(text(R.string.settings_app_appearance)) })
        assertFalse(audio.any { it.startsWith(text(R.string.settings_app_appearance)) })
        assertFalse(vehicle.any { it.startsWith(text(R.string.settings_app_appearance)) })
        assertFalse(advanced.any { it.startsWith(text(R.string.settings_app_appearance)) })
        listOf(R.string.main_buffered_audio, R.string.efficient_video, R.string.smooth_video, R.string.settings_direct_video_output, R.string.settings_low_latency_decoder, R.string.call_echo_cancellation, R.string.call_voice_filter, R.string.settings_disable_media_key_audio_focus, R.string.right_hand_drive, R.string.car_button_in_carplay).forEach {
            assertFalse(text(it), text(it) in display)
        }
    }

    @Test fun experimentalTitlesOnlyAppearInTheExperimentalCategory() {
        val screen = openSettings()
        for (category in SettingsCategory.entries) {
            ReflectionHelpers.setField(screen, "settingsCategory", category)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            texts(screen).filter { it.text.toString().endsWith("（实验性）") }.forEach { title ->
                assertEquals(title.text.toString(), SettingsCategory.EXPERIMENTAL, category)
            }
        }
    }



    @Test fun usbChargingIsSavedForTheNextWiredConnectionWithoutDroppingTheSession() {
        AirPlayPersistence.saveWirelessEnabled(context, false)
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            PendingReconnect.clear()
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.CONNECTION)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val row = texts(screen).single { it.text.startsWith(screen.getString(R.string.settings_usb_charging)) }
            assertEquals("${screen.getString(R.string.settings_usb_charging)} · ${screen.getString(R.string.settings_usb_charging_normal)}", row.text.toString())
            row.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(screen.getString(R.string.save), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            shadowOf(dialog).clickOnItem(UsbChargingCurrent.entries.indexOf(UsbChargingCurrent.LOW))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(UsbChargingCurrent.LOW, AirPlayPersistence.loadUsbChargingCurrent(context))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(0, stops)
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [28, 33])
    fun experimentalCallProcessingIsOptInAndMarksTheActiveSessionForReconnect() {
        assertFalse(AirPlayPersistence.loadCallEchoCancellation(context))
        assertFalse(AirPlayPersistence.loadCallVoiceFilter(context))
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.EXPERIMENTAL)
            listOf(R.string.call_echo_cancellation, R.string.call_voice_filter).forEach { title ->
                PendingReconnect.clear()
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                val setting = descendants(screen.window.decorView).filterIsInstance<Switch>()
                    .single { it.contentDescription == screen.getString(title) }
                assertFalse(setting.isChecked)
                setting.performClick()

                assertTrue(PendingReconnect.isPending(session))
                assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
                assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
                assertEquals(0, stops)
                assertEquals(null, shadowOf(screen).nextStartedActivity)
            }
            assertTrue(AirPlayPersistence.loadCallEchoCancellation(context))
            assertTrue(AirPlayPersistence.loadCallVoiceFilter(context))
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [28])
    fun mtkDecoderTuningAppearsOnlyForMtkAndMarksReconnect() {
        AirPlayPersistence.saveMtkDecoderTuning(context, false)
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.EXPERIMENTAL)
        screen.selectedMtkDecoderName = { null }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_mtk_decoder_tuning) })

        val decoderName = "OMX.MTK.VIDEO.DECODER.HEVC"
        screen.selectedMtkDecoderName = { decoderName }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertTrue(texts(screen).any {
            it.text == screen.getString(R.string.settings_mtk_decoder_tuning_description, decoderName)
        })

        val session = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        CarPlayBackgroundSession.active = true
        try {
            val setting = descendants(screen.window.decorView).filterIsInstance<Switch>()
                .single { it.contentDescription == screen.getString(R.string.settings_mtk_decoder_tuning) }
            assertFalse(setting.isChecked)
            setting.performClick()

            assertTrue(AirPlayPersistence.loadMtkDecoderTuning(context))
            assertTrue(PendingReconnect.isPending(session))
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        } finally {
            AirPlayPersistence.saveMtkDecoderTuning(context, false)
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [28, 33])
    fun disablingMediaKeyAudioFocusIsOptInAndMarksTheActiveSessionForReconnect() {
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, false)
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.EXPERIMENTAL)
            PendingReconnect.clear()
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val setting = descendants(screen.window.decorView).filterIsInstance<Switch>()
                .single { it.contentDescription == screen.getString(R.string.settings_disable_media_key_audio_focus) }
            assertFalse(setting.isChecked)

            setting.performClick()

            assertTrue(AirPlayPersistence.loadDisableMediaKeyAudioFocus(context))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(0, stops)
            assertEquals(null, shadowOf(screen).nextStartedActivity)
        } finally {
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, false)
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [28, 33])
    fun theAudioFocusAutoYieldSwitchMarksTheActiveSessionForReconnect() {
        // The sink reads this flag once, when it is built, so the change lands on the next session.
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        assertTrue(AirPlayPersistence.loadAudioFocusAutoYield(context))
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.EXPERIMENTAL)
            PendingReconnect.clear()
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val setting = descendants(screen.window.decorView).filterIsInstance<Switch>()
                .single { it.contentDescription == screen.getString(R.string.audio_focus_auto_yield) }
            assertTrue(setting.isChecked)
            setting.performClick()

            assertFalse(AirPlayPersistence.loadAudioFocusAutoYield(context))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(0, stops)
            assertEquals(null, shadowOf(screen).nextStartedActivity)
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun expandedRailStaysOutsideTheScrollingCategory() {
        val screen = openSettings()
        val categoryScroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val railDestination = descendants(screen.window.decorView).first {
            it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) &&
                descendants(it).filterIsInstance<ImageView>().count() == 1
        }

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.automatic_connection) && isInside(it, categoryScroll) })
        assertFalse(isInside(railDestination, categoryScroll))
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun expandedRailUsesAnIconForEveryDestination() {
        val screen = openSettings()
        val rail = ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll")
        val descriptions = SettingsCategory.entries.map { category ->
            val title = ReflectionHelpers.callInstanceMethod<String>(screen, "settingsCategoryTitle",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
            screen.getString(R.string.settings_open_category, title)
        }.toSet()
        val destinations = descendants(rail).filter { destination ->
            destination.contentDescription?.toString() in descriptions &&
                descendants(destination).filterIsInstance<ImageView>().count() == 1
        }.toList()
        assertEquals(SettingsCategory.entries.size, destinations.size)
        destinations.forEach { destination ->
            assertEquals(1, descendants(destination).filterIsInstance<ImageView>().count())
        }
    }

    @Test fun compactCategoriesHaveIcons() {
        val screen = openSettings()
        listOf(SettingsCategory.CONNECTION, SettingsCategory.DISPLAY, SettingsCategory.AUDIO,
            SettingsCategory.NAVIGATION, SettingsCategory.VEHICLE).forEach { category ->
            val title = ReflectionHelpers.callInstanceMethod<String>(screen, "settingsCategoryTitle",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
            val destination = descendants(screen.window.decorView).single {
                it.contentDescription == screen.getString(R.string.settings_open_category, title)
            }
            assertEquals(1, descendants(destination).filterIsInstance<ImageView>().count())
        }
    }

    @Test fun settingsLinksToEveryCategory() {
        val screen = openSettings()
        SettingsCategory.entries.forEach { category ->
            val title = ReflectionHelpers.callInstanceMethod<String>(screen, "settingsCategoryTitle",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
            assertTrue(category.name, descendants(screen.window.decorView).any {
                it.contentDescription == screen.getString(R.string.settings_open_category, title)
            })
        }
    }

    @Test fun headerActionsShareOneHeight() = assertHeaderActionsShareOneHeight()

    @Test
    @Config(sdk = [29], qualifiers = "en-w400dp-h700dp-port")
    fun compactHeaderActionsShareOneHeight() = assertHeaderActionsShareOneHeight()

    private fun assertHeaderActionsShareOneHeight() {
        val screen = openSettings()
        val appearance = descendants(screen.window.decorView).single { it.tag == "app_appearance_button" }
        val actions = (appearance.parent as ViewGroup).let { header ->
            (0 until header.childCount).map(header::getChildAt)
                .filter { it is android.widget.Button || it is android.widget.ImageButton || it is EditText }
        }
        assertTrue(actions.size >= 3)
        assertEquals(1, actions.map { it.layoutParams.height }.toSet().size)
    }

    @Test fun compactCategoriesHaveNoLanguageAndAboutWorks() {
        verifyAboutWithoutLanguageDestination(expanded = false)
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h700dp")
    fun expandedCategoriesHaveNoLanguageAndAboutWorks() {
        verifyAboutWithoutLanguageDestination(expanded = true)
    }

    private fun verifyAboutWithoutLanguageDestination(expanded: Boolean) {
        val screen = openSettings()
        val categoryScroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val languageTitle = screen.getString(R.string.settings_open_category,
            screen.getString(R.string.language_section_title))
        assertFalse(descendants(screen.window.decorView).any { it.contentDescription == languageTitle })
        fun open(title: Int) {
            descendants(screen.window.decorView).first {
                it.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(title))
            }.performClick()
        }
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.language_app_language) })
        open(R.string.about)
        assertEquals(SettingsCategory.ABOUT, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.carplay_at_home_in_your_car) })
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h400dp")
    fun selectingTheBottomRailDestinationKeepsItVisibleAndFocusedOnAShortScreen() {
        val screen = openSettings()
        val density = screen.resources.displayMetrics.density
        fun layout() {
            val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val width = Math.round(1000 * density)
            val height = Math.round(400 * density)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
        }
        fun rail() = ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll")
        fun advanced(scroll: ScrollView) = descendants(scroll).single {
            it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_advanced))
        }
        layout()
        val previousRail = rail()
        assertTrue("The rail viewport must fit within the settings window", previousRail.height <= Math.round(400 * density))
        previousRail.scrollTo(0, previousRail.getChildAt(0).height)
        assertTrue("This window must require rail scrolling", previousRail.scrollY > 0)
        val destination = advanced(previousRail)
        assertTrue(destination.requestFocus())
        assertTrue(previousRail.hasFocus())

        destination.performClick()
        layout()

        val currentRail = rail()
        val selected = advanced(currentRail)
        assertFalse(previousRail === currentRail)
        assertTrue(selected.isSelected)
        assertTrue("D-pad focus must follow the selected category", selected.isFocused)
        assertTrue(currentRail.scrollY > 0)
        val top = selected.top + currentRail.getChildAt(0).top
        assertTrue("Selected row starts inside the rail viewport", top >= currentRail.scrollY)
        assertTrue("Selected row ends inside the rail viewport",
            top + selected.height <= currentRail.scrollY + currentRail.height)
        assertEquals("Category scrolling remains independent", 0,
            ReflectionHelpers.getField<ScrollView>(screen, "rootScroll").scrollY)
        val railContent = currentRail.getChildAt(0) as ViewGroup
        val railPanel = railContent.getChildAt(0)
        currentRail.scrollTo(0, railContent.height)
        val panelBottom = railContent.top + railPanel.bottom - currentRail.scrollY
        assertEquals("Bottom spacing is reachable inside the scroll content",
            Math.round(12 * density), currentRail.height - panelBottom)
    }

    @Test fun utilityCategoriesHaveDirectDestinations() {
        val screen = openSettings()
        for (category in listOf(SettingsCategory.ABOUT, SettingsCategory.DIAGNOSTICS, SettingsCategory.EXPERIMENTAL, SettingsCategory.AUTHORIZATION)) {
            val title = ReflectionHelpers.callInstanceMethod<String>(screen, "settingsCategoryTitle",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
            val destination = descendants(screen.window.decorView).single {
                it.contentDescription == screen.getString(R.string.settings_open_category, title)
            }
            destination.performClick()
            assertEquals(category, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        }
    }
    @Test fun diagnosticsOpensThePhonePageAndBackReturnsToDiagnostics() {
        val screen = openSettings()
        descendants(screen.window.decorView).first { it.contentDescription ==
            screen.getString(R.string.settings_open_category, screen.getString(R.string.diagnostics)) }.performClick()
        texts(screen).first { it.text == screen.getString(R.string.settings_report_on_phone) }.performClick()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_report_on_phone) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.backup_title) })
        screen.onBackPressedDispatcher.onBackPressed()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.save_diagnostic_report) })
    }


    @Test fun unsupportedPageEntryUsesHomeConnectionActions() {
        val screen = openConnectionSetupFromSettings()
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)
        assertEquals(1, texts(screen).count { it.text == screen.getString(R.string.home_connected_devices) })
        assertEquals(1, texts(screen).count { it.text == screen.getString(R.string.home_usb_waiting) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_connect_usb) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_temporary_hotspot) })
        assertFalse(texts(screen).any { it.text.contains("车载热点") || it.text.contains("Wi-Fi Direct") || it.text.contains("同一局域网") })
        assertFalse(descendants(screen.window.decorView).any { it is EditText })
    }

    private fun openSettings(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { activity = it }

    private fun openConnectionSetupFromSettings(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "connection"),
    ).setup().get().also { activity = it }

    private fun layoutRoot(screen: DiPlayActivity) {
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = screen.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun isInside(view: View, ancestor: View): Boolean =
        generateSequence(view.parent) { it.parent }.any { it === ancestor }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
