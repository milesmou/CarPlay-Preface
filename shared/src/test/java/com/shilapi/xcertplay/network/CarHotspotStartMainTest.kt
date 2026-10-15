package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class CarHotspotStartMainTest {
    @Test fun acceptsOnlyProtocolSuccessReplies() {
        assertEquals("result 0", CarHotspotStartMain.parse("other output\nDIPLAY_HOTSPOT_START_V1|result 0\n"))
        assertTrue(CarHotspotStartMain.accepted("result 0"))
        assertTrue(CarHotspotStartMain.accepted("requested"))
        for (reply in listOf(null, "", "result 1", "error SecurityException", "other result 0")) {
            assertFalse(CarHotspotStartMain.accepted(reply))
        }
        assertNull(CarHotspotStartMain.parse("result 0"))
    }
    @Test fun apkPathIsShellQuotedWithoutChangingSavedHotspotConfiguration() {
        val command = CarHotspotStartMain.command("/data/app/a'b/base.apk")
        assertTrue(command.startsWith("CLASSPATH='/data/app/a'\"'\"'b/base.apk' app_process "))
        assertTrue(command.endsWith("com.shilapi.xcertplay.network.CarHotspotStartMain"))
    }
}
