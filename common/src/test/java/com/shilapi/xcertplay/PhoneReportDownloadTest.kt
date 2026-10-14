package com.shilapi.xcertplay

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.backup.SettingsBackupEndpoint
import com.shilapi.xcertplay.host.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneReportDownloadTest {
    @Test fun diagnosticEntryDownloadsCurrentRedactedReportAndBackStopsServer() = verify("full")
    @Test @Config(qualifiers = "zh-rCN-w640dp-h240dp-land-xxhdpi")
    fun highDensityLandscapeReportPage() = verify("high-density")

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun verify(name: String) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val activity = controller.get()
        val log = File(app.filesDir, "logs/diplay.log")
        val previousLog = log.takeIf { it.exists() }?.readBytes()
        try {
            activity.backupAddresses = { listOf("127.0.0.1") }
            activity.resources.updateConfiguration(Configuration(activity.resources.configuration).apply {
                fontScale = 1.5f
            }, activity.resources.displayMetrics)
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.DIAGNOSTICS)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            views(activity.window.decorView).filterIsInstance<TextView>().single {
                it.text == activity.getString(R.string.settings_report_on_phone)
            }.performClick()
            val endpoint = ReflectionHelpers.getField<SettingsBackupEndpoint>(activity, "backupEndpoint")
            log.parentFile!!.mkdirs()
            log.writeText("CONNECTION_DIAGNOSTIC packetCount=42\nMicrophone: payload=private-audio\n")
            val connection = URL(endpoint.url("127.0.0.1") + "report").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                assertEquals(200, connection.responseCode)
                assertTrue(connection.getHeaderField("Content-Disposition").contains("diplay-report.txt"))
                val report = connection.inputStream.bufferedReader().readText()
                assertTrue(report.contains("packetCount=42"))
                assertFalse(report.contains("private-audio"))
            } finally { connection.disconnect() }
            val decor = activity.window.decorView
            repeat(3) {
                views(decor).forEach { it.forceLayout() }
                decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, 1920, 720)
            }
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)
            assertTrue(views(decor).filterIsInstance<TextView>().any { it.text.contains(endpoint.code) })
            val qr = views(decor).filterIsInstance<android.widget.ImageView>().single {
                it.contentDescription == activity.getString(R.string.settings_report_on_phone)
            }
            val qrPosition = IntArray(2).also(qr::getLocationOnScreen)
            assertTrue("Whole QR code must fit on the first landscape screen", qrPosition[1] >= 0 && qrPosition[1] + qr.height <= 720)
            val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            val directory = File("../agent_docs/screenshots/phone-report").apply { mkdirs() }
            File(directory, "1920x720-$name-font150.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "navigateBack")
            assertEquals(SettingsCategory.DIAGNOSTICS, ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"))
            assertNull(ReflectionHelpers.getField<Any?>(activity, "backupServer"))
        } finally {
            if (previousLog != null) log.writeBytes(previousLog) else log.delete()
            controller.pause().stop().destroy()
            AppAppearanceRuntime.resetForTest()
        }
    }
}
