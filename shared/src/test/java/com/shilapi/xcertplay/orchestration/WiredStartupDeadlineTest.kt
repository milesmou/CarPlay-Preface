package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.transport.Iap2WiredControlClient.StartAgain

class WiredStartupDeadlineTest {
    private var time = 0L
    private val deadline = WiredStartupDeadline { time }
    private fun advance(ms: Long) { time += ms * 1_000_000L }

    @Test fun authenticationTimeDoesNotConsumeTheAirPlayWindow() {
        advance(300_000)
        assertFalse(deadline.timedOut())
        deadline.startSessionSent()
        advance(29_999)
        assertFalse(deadline.timedOut())
        advance(1)
        assertTrue(deadline.timedOut())
    }

    @Test fun repeatedStartSessionCannotExtendTheDeadline() {
        deadline.startSessionSent()
        advance(20_000)
        deadline.startSessionSent()
        advance(10_000)
        assertTrue(deadline.timedOut())
    }

    @Test fun activeSessionHasNoStartupTimeout() {
        deadline.startSessionSent()
        advance(29_999)
        assertTrue(deadline.sessionEstablished())
        advance(600_000)
        assertFalse(deadline.timedOut())
        assertTrue(deadline.sessionEstablished())
    }

    @Test fun lateSessionCannotWinAgainstAnExpiredWindow() {
        deadline.startSessionSent()
        advance(30_000)
        assertFalse(deadline.sessionEstablished())
        assertTrue(deadline.timedOut())
    }

    @Test fun aPendingLateLinkRetryHoldsTheInitialWindowOnlyTo45Seconds() {
        deadline.startSessionSent()
        advance(30_000)
        assertFalse(deadline.timedOut(secondRequestCanStillGoOut = true))
        advance(14_999)
        assertFalse(deadline.timedOut())
        advance(1)
        assertTrue(deadline.timedOut(secondRequestCanStillGoOut = true))
        deadline.startSessionSentAgain()
        assertFalse(deadline.sessionEstablished(true))
    }

    @Test fun onlyOurOneSuccessfulSecondRequestCanRearmTheDeadline() {
        deadline.startSessionSent()
        advance(29_000)
        deadline.startSessionSentAgain()
        advance(29_999)
        deadline.startSessionSentAgain()
        assertFalse(deadline.timedOut())
        advance(1)
        assertTrue(deadline.timedOut(true))
    }

    @Test fun firstStartNeedsRealLateWriteAndNoPhoneConnectionBeforeRetrying() {
        deadline.startSessionSent()
        advance(20_000)
        assertEquals(StartAgain.WAIT, deadline.startAgainDecision(time, false))
        val opened = time
        advance(2_000)
        assertEquals(StartAgain.SEND, deadline.startAgainDecision(opened, false))
        assertEquals(StartAgain.DONE, deadline.startAgainDecision(opened, true))
        assertTrue(deadline.sessionEstablished())
        assertEquals(StartAgain.DONE, deadline.startAgainDecision(opened, false))
    }
}
