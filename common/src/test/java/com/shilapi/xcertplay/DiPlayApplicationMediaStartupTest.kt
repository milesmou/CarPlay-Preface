package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = DiPlayApplication::class)
class DiPlayApplicationMediaStartupTest {
    @After fun cleanup() {
        CarPlayMediaKeys.javaClass.getDeclaredMethod("releaseLocked", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(CarPlayMediaKeys, false)
    }

    @Test fun applicationOnCreateStartsMediaRegistrationWithoutOpeningCarPlay() {
        assertTrue(RuntimeEnvironment.getApplication() is DiPlayApplication)
        assertNotNull(ReflectionHelpers.getField<PrefaceMediaCenter?>(CarPlayMediaKeys, "mediaCenter"))
        assertTrue(CarPlayMediaKeys.report().contains("controllerAttached=false"))
        assertTrue(CarPlayMediaKeys.report().contains("focusRequested=false"))
        assertFalse(CarPlayBackgroundSession.hasSession())
    }
}
