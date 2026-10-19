package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ConnectionRequestStateTest {
    @Test fun resumingAfterHomeDoesNotReviveOldAuthorizationResults() {
        val requests = ConnectionRequestGate()
        val oldPermission = requests.generation
        requests.cancel()
        assertFalse(requests.accepts(oldPermission))
        requests.resume()
        assertFalse(requests.accepts(oldPermission))
        assertTrue(requests.accepts(requests.generation))
    }

    @Test fun failedUsbAllowsExplicitWirelessAndRejectsLateUsbSuccess() {
        val priority = UsbConnectionPriority()
        val attempt = priority.beginWiredAttempt("iphone")
        assertFalse(priority.canChooseWireless("iphone", activeWired = false))
        priority.wiredFailed("iphone", attempt)
        assertTrue(priority.canChooseWireless("iphone", activeWired = false))
        priority.chooseWireless("iphone")
        priority.wiredConnected(attempt)
        assertTrue(priority.wirelessSelected("iphone"))
        assertNotEquals(attempt, priority.generation)
    }

    @Test fun anActiveWiredSessionRetainsPriorityEvenAfterAFailureReport() {
        val priority = UsbConnectionPriority()
        val attempt = priority.beginWiredAttempt("iphone")
        priority.wiredFailed("iphone", attempt)
        assertFalse(priority.canChooseWireless("iphone", activeWired = true))
    }

    @Test fun returningHomeBlocksDuplicateCableEventsUntilPhysicalReplug() {
        val priority = UsbConnectionPriority()
        priority.beginWiredAttempt("iphone")
        priority.cancelForHome("iphone")
        assertTrue(priority.wasCancelled("iphone"))
        assertTrue(priority.alreadyAttempted("iphone"))
        priority.detached("another-phone")
        assertTrue(priority.wasCancelled("iphone"))
        priority.detached("iphone")
        assertFalse(priority.wasCancelled("iphone"))
        assertFalse(priority.alreadyAttempted("iphone"))
        priority.beginWiredAttempt("iphone")
        assertFalse(priority.wirelessSelected("iphone"))
    }

    @Test fun manualWiredRetryReclaimsPriorityAndIgnoresTheEarlierFailure() {
        val priority = UsbConnectionPriority()
        val first = priority.beginWiredAttempt("iphone")
        priority.wiredFailed("iphone", first)
        priority.chooseWireless("iphone")
        val retry = priority.beginWiredAttempt("iphone")
        priority.wiredFailed("iphone", first)
        assertFalse(priority.wirelessSelected("iphone"))
        assertFalse(priority.canChooseWireless("iphone", activeWired = false))
        priority.wiredFailed("iphone", retry)
        assertTrue(priority.canChooseWireless("iphone", activeWired = false))
    }
}
