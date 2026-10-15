package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class PrefaceMediaCenterTest {
    private val tasks = ArrayDeque<Runnable>()
    private val log = mutableListOf<String>()
    private val sent = mutableListOf<Int>()
    private val api = FakeBackend()
    private val center = PrefaceMediaCenter(Executor { tasks.add(it) }, { api }, { sent.add(it) }, log::add)
    private val song = CarPlayNowPlaying(title = "Test", playing = true, elapsedMillis = 500L)
    private fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    private fun ready() { center.start(); drain(); api.ready(true); drain() }
    private fun play() { center.update(song, true, requestSource = true); drain() }

    @Test fun waitsForReadyAndRetainsTheInitialPlayRequest() {
        center.start(); drain()
        play()
        assertEquals(0, api.registrations)
        assertFalse(api.command(4))
        api.ready(true); drain()
        assertEquals(1, api.registrations)
        assertEquals(1, api.requests)
        assertEquals(listOf(song), api.publications)
        assertTrue(api.command(4))
        assertEquals(listOf(4), sent)
        api.ready(true); drain()
        assertEquals(1, api.registrations)
    }

    @Test fun metadataAndPauseDoNotReclaimTheSource() {
        ready(); play()
        center.update(song.copy(elapsedMillis = 1000), true); drain()
        center.update(song.copy(playing = false), true); drain()
        assertFalse(api.publications.last().playing)
        assertTrue(api.command(1))
        center.update(song, true, requestSource = true); drain()
        assertEquals(1, api.requests)
        assertEquals(listOf(1), sent)
    }

    @Test fun deniedRegistrationOrPlayLeavesCallbacksUnconsumed() {
        api.token = null
        ready(); play()
        assertEquals(0, api.requests)
        assertFalse(api.command(4))
        assertTrue(center.report().contains("registered=false"))
    }

    @Test fun rejectedPlayIsNotRetriedForPositionUpdates() {
        api.acceptPlay = false
        ready(); play()
        center.update(song.copy(elapsedMillis = 1000), true); drain()
        assertEquals(1, api.requests)
        assertFalse(api.command(4))
        assertTrue(api.publications.isEmpty())
        api.acceptPlay = true
        center.update(song, true, requestSource = true); drain()
        assertEquals(2, api.requests)
        assertTrue(api.command(4))
    }

    @Test fun losingEligibilityCancelsPendingClaimAndBlocksCallbacksImmediately() {
        ready(); play()
        center.update(song, false)
        assertFalse(api.command(4))
        drain()
        center.update(song, true); drain()
        assertEquals(1, api.requests)
        assertFalse(api.command(4))
        center.update(song, true, requestSource = true); drain()
        assertEquals(2, api.requests)
        assertTrue(api.command(4))
    }

    @Test fun sourceLossBeforeReadyCancelsTheQueuedStart() {
        center.start(); drain(); play()
        center.update(song, false); drain()
        api.ready(true); drain()
        center.update(song, true); drain()
        assertEquals(0, api.requests)
    }

    @Test fun temporaryCommandSuppressionRetainsTheSourceAndResumesWithoutRequestingPlay() {
        ready(); play()
        center.update(song, true, commandsAllowed = false)
        assertTrue(center.status().ownsSource)
        assertFalse(api.command(4))
        drain()
        center.update(song, true, commandsAllowed = true); drain()
        assertTrue(api.command(4))
        assertEquals(1, api.requests)
    }

    @Test fun suppressionBeforeApiReadyDoesNotStartPlaybackAfterThePriorityStateEnds() {
        center.start(); drain(); play()
        center.update(song, true, commandsAllowed = false); drain()
        api.ready(true); drain()
        center.update(song, true, commandsAllowed = true); drain()
        assertEquals(0, api.requests)
        assertFalse(api.command(4))
        play()
        assertEquals(1, api.requests)
        assertTrue(api.command(4))
    }

    @Test fun closeRejectsLateCallbacksAndUnregistersExactlyOnce() {
        ready(); play()
        center.close()
        assertFalse(api.command(5))
        api.ready(true)
        drain()
        assertEquals(1, api.registrations)
        assertEquals(1, api.unregistrations)
        center.close(); drain()
        assertEquals(1, api.unregistrations)
    }

    @Test fun closeDuringRegistrationStillReleasesTheReturnedToken() {
        center.start(); drain()
        api.onRegister = { center.close() }
        api.ready(true); drain()
        assertEquals(1, api.unregistrations)
        assertEquals(0, api.requests)
        assertFalse(api.command(4))
    }

    @Test fun apiLossReleasesTheOldTokenAndDoesNotAutomaticallyStealFocusWhenReadyAgain() {
        ready(); play()
        api.ready(false); drain()
        assertFalse(api.command(4))
        assertEquals(1, api.unregistrations)
        api.ready(true); drain()
        assertEquals(2, api.registrations)
        assertEquals(1, api.requests)
        assertFalse(api.command(4))
    }

    @Test fun permissionFailureIsReportedWithoutExceptionMessageOrToken() {
        api.failure = SecurityException("private token / identity")
        ready(); play()
        assertTrue(log.any { it.contains("register-failed:SecurityException") })
        assertFalse(log.any { it.contains("private token") })
        assertFalse(api.command(4))
    }

    @Test fun losingAndRegainingEligibilityDuringIpcDoesNotAcceptTheStaleClaim() {
        ready()
        api.onRequest = {
            center.update(song, false)
            center.update(song, true)
        }
        play()
        assertEquals(1, api.requests)
        assertFalse(api.command(4))
    }

    @Test fun disconnectKeepsRegistrationButRevokesPlaybackAndReusesItOnlyAfterAnotherRealStart() {
        ready(); play()
        center.disconnect()
        assertFalse(api.command(4))
        drain()
        assertEquals(1, api.disconnections)
        assertEquals(0, api.unregistrations)
        assertEquals(1, api.registrations)
        center.update(song, true); drain()
        assertFalse(api.command(4))
        play()
        assertEquals(2, api.requests)
        assertEquals(1, api.registrations)
        assertTrue(api.command(4))
    }

    @Test fun aQueuedDisconnectCannotResetTheReplacementPlaybackRecord() {
        ready(); play()
        center.disconnect()
        center.update(song, true, requestSource = true)
        drain()
        assertEquals(0, api.disconnections)
        assertEquals(2, api.requests)
        assertTrue(api.command(4))
    }

    private class FakeBackend : PrefaceMediaCenter.Backend {
        lateinit var ready: (Boolean) -> Unit
        lateinit var command: (Int) -> Boolean
        var token: Any? = Any()
        var failure: Exception? = null
        var onRegister: () -> Unit = {}
        var onRequest: () -> Unit = {}
        var acceptPlay = true
        var registrations = 0
        var requests = 0
        var unregistrations = 0
        var disconnections = 0
        val publications = mutableListOf<CarPlayNowPlaying>()
        override fun init(snapshot: () -> PrefaceMediaCenter.Snapshot, command: (Int) -> Boolean,
            ready: (Boolean) -> Unit) { this.command = command; this.ready = ready }
        override fun register(): Any? { registrations++; failure?.let { throw it }; onRegister(); return token }
        override fun requestPlay(token: Any): Boolean { requests++; onRequest(); return acceptPlay }
        override fun publish(token: Any, info: CarPlayNowPlaying): Boolean { publications.add(info); return true }
        override fun disconnect() { disconnections++ }
        override fun unregister(token: Any): Boolean { unregistrations++; return true }
    }
}
