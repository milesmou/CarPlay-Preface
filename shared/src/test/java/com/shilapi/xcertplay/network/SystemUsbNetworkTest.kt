package com.shilapi.xcertplay.network

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SystemUsbNetworkTest {
    @Test fun usbAddressMustFinishDadBeforeItCanBeAnnounced() {
        val address = java.net.InetAddress.getByName("fe80::1234").address
        val hex = "fe800000000000000000000000001234"
        fun state(flags: String) = SystemUsbNetwork.addressState("$hex 03 40 20 $flags usb0", "usb0", 3, address)
        assertEquals("tentative", state("40"))
        assertEquals("tentative", state("44"))
        assertEquals("dad_failed", state("48"))
        assertEquals("deprecated", state("20"))
        assertEquals("ready", state("80"))
        assertEquals("ready", state("82"))
        assertEquals("invalid", state("bad-flags"))
        assertEquals("absent", SystemUsbNetwork.addressState("$hex 04 40 20 80 usb0", "usb0", 3, address))
        assertEquals("absent", SystemUsbNetwork.addressState("$hex 03 40 20 80 usb1", "usb0", 3, address))
        assertEquals("absent", SystemUsbNetwork.addressState("", "usb0", 3, address))
        assertEquals("unavailable", SystemUsbNetwork.addressState(null, "usb0", 3, address))
    }
    @get:Rule val temporary = TemporaryFolder()

    private fun usbInterface(bus: String = "1", device: String = "7", number: String = "03"): File {
        val parent = temporary.newFolder()
        File(parent, "busnum").writeText(bus)
        File(parent, "devnum").writeText(device)
        return File(parent, "usb-function-3").apply {
            mkdir()
            File(this, "bInterfaceNumber").writeText(number)
        }
    }

    @Test fun onlyTheCurrentPhonesNcmFunctionMatches() {
        val identity = SystemUsbNetwork.usbIdentity("/dev/bus/usb/001/007")!!
        assertTrue(SystemUsbNetwork.ownsUsbFunction(usbInterface(), identity, setOf(2, 3)))
        assertFalse(SystemUsbNetwork.ownsUsbFunction(usbInterface(device = "8"), identity, setOf(2, 3)))
        assertFalse(SystemUsbNetwork.ownsUsbFunction(usbInterface(bus = "2"), identity, setOf(2, 3)))
        assertFalse(SystemUsbNetwork.ownsUsbFunction(usbInterface(number = "01"), identity, setOf(2, 3)))
    }

    @Test fun unreadableOrMalformedIdentityDoesNotSelectAnotherUsbNic() {
        assertFalse(SystemUsbNetwork.ownsUsbFunction(temporary.newFolder(), 1 to 7, setOf(2, 3)))
        assertFalse(SystemUsbNetwork.ownsUsbFunction(usbInterface(bus = "bad"), 1 to 7, setOf(2, 3)))
        assertFalse(SystemUsbNetwork.ownsUsbFunction(usbInterface(number = "bad"), 1 to 7, setOf(2, 3)))
        assertNull(SystemUsbNetwork.usbIdentity("usb0"))
        assertNull(SystemUsbNetwork.usbIdentity("/dev/bus/usb/001/000"))
    }

    @Test fun authenticationDelayDoesNotConsumeTheNetworkStartupBudget() {
        var now = 0L
        val fallback = SystemUsbFallback { now }
        now = 60_000_000_000L
        assertFalse(fallback.claimFallback())
        fallback.startSessionSent()
        now += 29_999_000_000L
        assertFalse(fallback.claimFallback())
        // Repeated availability messages must not postpone an already failing startup.
        fallback.startSessionSent()
        now += 1_000_000L
        assertTrue(fallback.claimFallback())
        assertFalse(fallback.claimFallback())
        assertFalse(fallback.sessionEstablished())
    }

    @Test fun establishedSessionNeverFallsBackEvenAfterItEnds() {
        var now = 0L
        val fallback = SystemUsbFallback { now }
        fallback.startSessionSent()
        assertTrue(fallback.sessionEstablished())
        now = 120_000_000_000L
        assertFalse(fallback.claimFallback())
        assertTrue(fallback.hasEstablishedSession())
    }
}
