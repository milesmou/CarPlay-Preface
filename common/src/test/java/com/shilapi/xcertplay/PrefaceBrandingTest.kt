package com.shilapi.xcertplay

import android.graphics.BitmapFactory
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrefaceBrandingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit()
            .remove("manufacturer").remove("model").remove("oem_label").commit()
    }

    @Test fun defaultVehicleNameUsesXingrui() {
        clearPreferences()
        assertEquals("CarPlay", context.getString(R.string.app_name))
        assertEquals("吉利", AirPlayPersistence.loadManufacturer(context))
        assertEquals("星瑞", AirPlayPersistence.loadModel(context))
        assertEquals("Preface", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun previouslySavedBundledIdentityUsesUpdatedDefaults() {
        AirPlayPersistence.saveManufacturer(context, "DiPlay")
        AirPlayPersistence.saveModel(context, "DiPlay")
        AirPlayPersistence.saveOemLabel(context, "星瑞")
        assertEquals("吉利", AirPlayPersistence.loadManufacturer(context))
        assertEquals("星瑞", AirPlayPersistence.loadModel(context))
        assertEquals("Preface", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun customVehicleNameIsPreserved() {
        AirPlayPersistence.saveManufacturer(context, "我的制造商")
        AirPlayPersistence.saveModel(context, "我的车型")
        AirPlayPersistence.saveOemLabel(context, "我的星瑞")
        assertEquals("我的制造商", AirPlayPersistence.loadManufacturer(context))
        assertEquals("我的车型", AirPlayPersistence.loadModel(context))
        assertEquals("我的星瑞", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun packagedVehicleIconIsAValidSquareImage() {
        val icon = BitmapFactory.decodeResource(context.resources, R.raw.ic_xingrui)
        assertNotNull(icon)
        try {
            assertEquals(icon.width, icon.height)
            assertTrue(icon.width >= 192)
        } finally { icon.recycle() }
    }

    @Test fun generatedIconFitsTheCarPlayConnectionPayload() {
        val original = context.resources.openRawResource(R.raw.ic_xingrui).use { it.readBytes() }
        val (size, bytes) = AirPlayIconSizing.fit(original)!!
        assertEquals(AirPlayIconSizing.MAX_PIXELS, size)
        assertTrue(bytes.size < original.size)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(size, bounds.outWidth)
        assertEquals(size, bounds.outHeight)
    }
}
