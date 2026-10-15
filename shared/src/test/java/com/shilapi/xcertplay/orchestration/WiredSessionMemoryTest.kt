package com.shilapi.xcertplay.orchestration

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WiredSessionMemoryTest {
    @After fun clear() = WiredSessionMemory.clear()
    @Test fun remembersOnlyTheStartedEnumeration() {
        WiredSessionMemory.clear()
        assertFalse(WiredSessionMemory.startedOn("/dev/bus/usb/001/001"))
        WiredSessionMemory.started("/dev/bus/usb/001/001")
        assertTrue(WiredSessionMemory.startedOn("/dev/bus/usb/001/001"))
        assertFalse(WiredSessionMemory.startedOn("/dev/bus/usb/001/002"))
        WiredSessionMemory.started("/dev/bus/usb/001/002")
        assertFalse(WiredSessionMemory.startedOn("/dev/bus/usb/001/001"))
        WiredSessionMemory.clear()
        assertFalse(WiredSessionMemory.startedOn("/dev/bus/usb/001/002"))
    }
}
