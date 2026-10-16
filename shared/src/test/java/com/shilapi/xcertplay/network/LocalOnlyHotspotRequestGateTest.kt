package com.shilapi.xcertplay.network

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LocalOnlyHotspotRequestGateTest {
    private fun deadline() = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)

    @Test fun cancellationCannotOverlapAStillPendingFrameworkCallback() {
        val gate = LocalOnlyHotspotRequestGate()
        val old = gate.acquire(deadline()) { false }
        old.retire()
        assertThrows(IOException::class.java) { gate.acquire(System.nanoTime()) { false } }
        old.callbackFinished() // rejected reservation has now been closed
        val next = gate.acquire(deadline()) { false }
        old.callbackFinished(); old.retire() // stale callbacks cannot release the new owner
        assertThrows(IOException::class.java) { gate.acquire(System.nanoTime()) { false } }
        next.callbackFinished(); next.retire()
    }

    @Test fun aLiveOrRetainedReservationKeepsItsLeaseUntilExplicitRelease() {
        val gate = LocalOnlyHotspotRequestGate()
        val old = gate.acquire(deadline()) { false }
        old.callbackFinished()
        assertThrows(IOException::class.java) { gate.acquire(System.nanoTime()) { false } }
        old.retire()
        gate.acquire(deadline()) { false }.also { it.callbackFinished(); it.retire() }
    }

    @Test fun cancellationAndInterruptionNeverIssueANewRequest() {
        val gate = LocalOnlyHotspotRequestGate()
        assertThrows(InterruptedIOException::class.java) { gate.acquire(deadline()) { true } }
        val old = gate.acquire(deadline()) { false }
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { gate.acquire(deadline()) { false } }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted(); old.callbackFinished(); old.retire() }
    }
}
