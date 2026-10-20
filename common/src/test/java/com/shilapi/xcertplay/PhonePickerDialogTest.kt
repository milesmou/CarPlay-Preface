package com.shilapi.xcertplay

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
class PhonePickerDialogTest {
    private lateinit var owner: Activity
    private var dialog: PhonePickerDialog? = null
    private val first = PhonePickerDialog.Choice("12:34:56:78:AA:01", "我的 iPhone 16 Pro Max")
    private val second = PhonePickerDialog.Choice("12:34:56:78:AA:02", "我的 iPhone 16 Pro Max")
    private var selected: PhonePickerDialog.Choice? = null
    private var paired = 0
    private var cancelled = 0
    private var previousFlags = 0

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        previousFlags = app.applicationInfo.flags
        owner = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
    }

    @After fun cleanup() {
        dialog?.dismiss()
        owner.finish()
        RuntimeEnvironment.getApplication().applicationInfo.flags = previousFlags
        AppAppearanceRuntime.resetForTest()
    }

    private fun show(choices: List<PhonePickerDialog.Choice> = listOf(first, second),
        palette: DiPlayPalette = DiPlayPalette.DARK): PhonePickerDialog {
        return PhonePickerDialog(owner, palette, choices, first.address.lowercase(),
            owner.getString(R.string.choose_your_iphone),
            owner.getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c),
            owner.getString(R.string.pair_another), { selected = it }, { paired++ }, { cancelled++ })
            .also { dialog = it; it.show() }
    }

    @Test fun selectedDeviceAndDuplicateNamesAreClearAndSelectionReturnsTheExactDevice() {
        val picker = show()
        val rows = views(picker.window!!.decorView).filterIsInstance<Button>().filter { it.tag is String }.toList()
        assertEquals(2, rows.size)
        assertTrue(rows.first().isSelected)
        assertTrue(rows.first().text.contains("AA:01"))
        assertTrue(rows.last().text.contains("AA:02"))
        assertTrue(rows.first().contentDescription.contains("已选择"))
        rows.last().performClick()
        assertSame(second, selected)
        assertFalse(picker.isShowing)
        assertEquals(0, cancelled)
    }

    @Test fun cancelAndSystemCancellationNeverChooseADevice() {
        val picker = show()
        button(picker, R.string.cancel).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, cancelled)
        assertNull(selected)
        show().cancel()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(2, cancelled)
        assertNull(selected)
    }

    @Test fun pairingDoesNotSelectOrAccidentallyResumeConnection() {
        val picker = show(emptyList())
        assertTrue(views(picker.window!!.decorView).filterIsInstance<TextView>().any {
            it.text == owner.getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c)
        })
        button(picker, R.string.pair_another).performClick()
        assertEquals(1, paired)
        assertNull(selected)
        assertFalse(picker.isShowing)
    }

    @Test fun homePickerSavesTheChosenDevice() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice(first.address)
        shadowOf(device).setName(first.name)
        shadowOf(adapter).setBondedDevices(setOf(device))
        ReflectionHelpers.callInstanceMethod<Unit>(owner, "choosePhone")
        val picker = ReflectionHelpers.getField<PhonePickerDialog>(owner, "phonePickerDialog")
        dialog = picker
        views(picker.window!!.decorView).filterIsInstance<Button>().single { it.tag == first.address }.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(first.address, DiPlayPreferences.phoneAddress(owner))
        assertEquals(first.name, DiPlayPreferences.phoneName(owner))
        assertNull(ReflectionHelpers.getField<PhonePickerDialog?>(owner, "phonePickerDialog"))
    }

    @Test fun cancellingHomePickerClearsPendingWirelessWithoutChangingTheSavedPhone() {
        DiPlayPreferences.savePhone(owner, first.address, first.name!!)
        ReflectionHelpers.setField(owner, "pendingWireless", true)
        ReflectionHelpers.callInstanceMethod<Unit>(owner, "choosePhone")
        val picker = ReflectionHelpers.getField<PhonePickerDialog>(owner, "phonePickerDialog")
        dialog = picker
        picker.cancel()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(ReflectionHelpers.getField<Boolean>(owner, "pendingWireless"))
        assertEquals(first.address, DiPlayPreferences.phoneAddress(owner))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun singleDeviceLandscapeLargeFontScreenshot() = screenshot("single", DiPlayPalette.DARK)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fullLandscapeLargeFontScreenshot() = screenshot("full", DiPlayPalette.DARK)

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactLandscapeLargeFontScreenshot() = screenshot("compact", DiPlayPalette.DARK)

    @Test @Config(qualifiers = "zh-rCN-w640dp-h240dp-land-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun highDensityLandscapeLargeFontScreenshot() = screenshot("high-density", DiPlayPalette.DARK)


    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun lightLandscapeLargeFontScreenshot() = screenshot("light", DiPlayPalette.LIGHT)

    private fun screenshot(name: String, palette: DiPlayPalette) {
        owner.resources.updateConfiguration(Configuration(owner.resources.configuration).apply {
            fontScale = 1.5f
        }, owner.resources.displayMetrics)
        val choices = if (name == "single") listOf(first) else listOf(first, second) + (3..10).map {
            PhonePickerDialog.Choice("12:34:56:78:AA:${it.toString().padStart(2, '0')}", "家庭成员的 iPhone $it")
        }
        val picker = show(choices, palette)
        val decor = picker.window!!.decorView
        repeat(3) {
            views(decor).forEach { it.forceLayout() }
            decor.measure(View.MeasureSpec.makeMeasureSpec(picker.window!!.attributes.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST))
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        }
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, owner.resources.configuration.orientation)
        assertTrue(decor.width < 1920)
        assertTrue(decor.height <= 720)
        val footerButtons = views(decor).filterIsInstance<Button>().filter { it.tag == null }.toList()
        val pairing = footerButtons.first()
        val cancel = footerButtons.last()
        assertEquals(pairing.height, cancel.height)

        val scroll = views(decor).filterIsInstance<ScrollView>().single()
        val row = views(scroll).filterIsInstance<Button>().first()
        assertTrue("At least one complete device row must fit: list=${scroll.height} row=${row.height}", scroll.height >= row.height)
        if (name != "single") assertTrue(scroll.getChildAt(0).height > scroll.height)
        assertEquals(palette.primaryText,
            views(decor).filterIsInstance<TextView>().first { it !is Button }.currentTextColor)
        val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(12, 17, 27))
        canvas.translate((1920 - decor.width) / 2f, (720 - decor.height) / 2f)
        decor.draw(canvas)
        val directory = File("../agent_docs/screenshots/phone-picker").apply { mkdirs() }
        File(directory, "1920x720-$name-font150.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun button(dialog: PhonePickerDialog, title: Int) = views(dialog.window!!.decorView)
        .filterIsInstance<Button>().single { it.text == owner.getString(title) }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
