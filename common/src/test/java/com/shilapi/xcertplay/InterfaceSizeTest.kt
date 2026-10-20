package com.shilapi.xcertplay

import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class InterfaceSizeTest {
    @After fun resetPreference() = InterfaceSize.save(RuntimeEnvironment.getApplication(), InterfaceSize.PREFACE_12_3)

    private fun screen(widthDp: Int, heightDp: Int, densityDpi: Int) = Configuration().apply {
        screenWidthDp = widthDp
        screenHeightDp = heightDp
        smallestScreenWidthDp = minOf(widthDp, heightDp)
        this.densityDpi = densityDpi
        orientation = Configuration.ORIENTATION_LANDSCAPE
    }

    @Test fun prefaceChoiceHasConsistentSizeAcrossReportedDensities() {
        assertNull(InterfaceSize.override(screen(1920, 720, 160), InterfaceSize.PREFACE_12_3))
        for (base in listOf(screen(1280, 480, 240), screen(960, 360, 320))) {
            val scaled = InterfaceSize.override(base, InterfaceSize.PREFACE_12_3)!!
            assertEquals(160, scaled.densityDpi)
            assertEquals(1920, scaled.screenWidthDp)
            assertEquals(720, scaled.screenHeightDp)
        }
    }

    @Test fun savedManualPercentagesStillUseSystemSize() {
        val base = screen(1280, 480, 240)
        assertNull(InterfaceSize.override(base, 100))
        val scaled = InterfaceSize.override(base, 150)!!
        assertEquals(360, scaled.densityDpi)
        assertEquals(853, scaled.screenWidthDp)
        assertEquals(320, scaled.screenHeightDp)
    }

    @Test fun smaller75PercentChoiceUsesSystemSize() {
        val context = RuntimeEnvironment.getApplication()
        InterfaceSize.save(context, 75)
        assertEquals(75, InterfaceSize.preference(context))
        val scaled = InterfaceSize.override(screen(1280, 480, 240), 75)!!
        assertEquals(180, scaled.densityDpi)
        assertEquals(1707, scaled.screenWidthDp)
        assertEquals(640, scaled.screenHeightDp)
    }

    @Test fun defaultsAndRemovedChoicesUsePrefaceWhileValidChoicesRemain() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("diplay", 0)
        preferences.edit().remove("interface_size").commit()
        assertEquals(InterfaceSize.PREFACE_12_3, InterfaceSize.preference(context))
        for (value in listOf(0, 200)) {
            preferences.edit().putInt("interface_size", value).commit()
            assertEquals(InterfaceSize.PREFACE_12_3, InterfaceSize.preference(context))
        }
        assertEquals(listOf(InterfaceSize.PREFACE_12_3, 75, 100, 125, 150), InterfaceSize.CHOICES)
        for (value in listOf(75, 100, 125, 150)) {
            InterfaceSize.save(context, value)
            assertEquals(value, InterfaceSize.preference(context))
        }
        assertEquals("星瑞 12.3 英寸", InterfaceSize.displayName(context, InterfaceSize.PREFACE_12_3))
    }

    @Test fun appOverrideDoesNotFreezeWindowDimensions() {
        val scaled = InterfaceSize.override(screen(1280, 480, 240), InterfaceSize.PREFACE_12_3)!!
        val installed = InterfaceSize.contextOverride(scaled)
        assertEquals(scaled.fontScale, installed.fontScale, 0f)
        assertEquals(0, installed.screenWidthDp)
        assertEquals(0, installed.screenHeightDp)
        val split = screen(640, 480, 240).apply { updateFrom(installed) }
        val next = InterfaceSize.configurationChange(split, 240, InterfaceSize.PREFACE_12_3)!!
        assertEquals(160, next.densityDpi)
        assertEquals(960, next.screenWidthDp)
        assertEquals(720, next.screenHeightDp)
        assertFalse(InterfaceSize.needsRecreate(scaled, next))
    }

    @Test fun systemDensityChangesDoNotChangePrefaceControlSize() {
        val scaled = InterfaceSize.override(screen(1280, 480, 240), InterfaceSize.PREFACE_12_3)!!
        val reported = screen(960, 360, 320).apply { updateFrom(InterfaceSize.contextOverride(scaled)) }
        val next = InterfaceSize.configurationChange(reported, 320, InterfaceSize.PREFACE_12_3)!!
        assertEquals(160, next.densityDpi)
        assertEquals(1920, next.screenWidthDp)
        assertEquals(720, next.screenHeightDp)
        assertFalse(InterfaceSize.needsRecreate(scaled, next))
    }

    @Test fun enforcementRestoresBothMetricsAndAvailableWindow() {
        val resources = RuntimeEnvironment.getApplication().resources
        val scaled = InterfaceSize.override(screen(1280, 480, 240), InterfaceSize.PREFACE_12_3)!!
        InterfaceSize.enforce(resources, scaled)
        resources.configuration.screenWidthDp = 1280
        resources.displayMetrics.densityDpi = 240
        assertTrue(InterfaceSize.enforce(resources, scaled))
        assertEquals(160, resources.displayMetrics.densityDpi)
        assertEquals(1920, resources.configuration.screenWidthDp)
        assertFalse(InterfaceSize.enforce(resources, scaled))
    }

    @Test fun enforcementKeepsTheUsersFontScale() {
        val resources = RuntimeEnvironment.getApplication().resources
        resources.displayMetrics.apply { densityDpi = 240; density = 1.5f; scaledDensity = 2.25f }
        val scaled = InterfaceSize.override(screen(1280, 480, 240), InterfaceSize.PREFACE_12_3)!!
        InterfaceSize.enforce(resources, scaled)
        assertEquals(1.5f, resources.displayMetrics.scaledDensity / resources.displayMetrics.density, 0.001f)
        val base = screen(1280, 480, 240).apply { fontScale = 1.5f }
        assertEquals(1.5f, InterfaceSize.contextOverride(InterfaceSize.override(base, InterfaceSize.PREFACE_12_3)!!).fontScale, 0f)
    }

    @Test fun manualDensityChangesStillNeedRecreation() {
        val base = screen(1280, 480, 240)
        val automatic = InterfaceSize.override(base, InterfaceSize.PREFACE_12_3)
        val manual = InterfaceSize.override(base, 150)
        assertTrue(InterfaceSize.needsRecreate(automatic, manual))
        assertTrue(InterfaceSize.needsRecreate(null, automatic))
        assertFalse(InterfaceSize.needsRecreate(null, null))
    }

    @Test fun overrideKeepsTheBaseLanguage() {
        val base = screen(1280, 480, 240).apply { setLocales(android.os.LocaleList(java.util.Locale("en"))) }
        val scaled = InterfaceSize.override(base, InterfaceSize.PREFACE_12_3)!!
        assertEquals("en", scaled.locales[0].language)
        assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, scaled.layoutDirection)
    }
}
