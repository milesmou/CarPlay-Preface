package com.shilapi.xcertplay.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupGuideTest {
    @Test fun guideOpensByItselfOnlyOnAFreshSetup() {
        assertTrue(SetupGuide.shouldOpenOnLaunch(seen = false, phoneChosen = false))
        assertFalse(SetupGuide.shouldOpenOnLaunch(seen = false, phoneChosen = true))
        assertFalse(SetupGuide.shouldOpenOnLaunch(seen = true, phoneChosen = false))
    }
}
