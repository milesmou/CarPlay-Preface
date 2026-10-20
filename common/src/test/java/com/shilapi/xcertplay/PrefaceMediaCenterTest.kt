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

    @Test fun initialNotReadyRetainsTheRealPlaybackRequestUntilReady() {
        center.start(); drain(); play()
        api.ready(false); drain()
        api.ready(false); drain()
        api.ready(true); drain()
        assertEquals(1, api.registrations)
        assertEquals(1, api.requests)
        assertTrue(api.command(4))
        api.ready(true); drain()
        center.update(song.copy(elapsedMillis = 1000L), true); drain()
        assertEquals(1, api.requests)
    }

    @Test fun apiLossRecoversUninterruptedPlaybackOnceWithANewToken() {
        ready(); play()
        val oldToken = api.token
        api.ready(false); drain()
        assertFalse(api.command(4))
        assertEquals(1, api.unregistrations)
        api.ready(false); drain()
        center.update(song.copy(elapsedMillis = 1000L), true); drain()
        api.token = Any()
        api.ready(true); drain()
        assertEquals(2, api.registrations)
        assertEquals(2, api.requests)
        assertTrue(api.command(4))
        assertSame(oldToken, api.requestTokens.first())
        assertSame(api.token, api.requestTokens.last())
        assertNotSame(api.requestTokens.first(), api.requestTokens.last())
        api.ready(true); drain()
        assertEquals(2, api.requests)
    }

    @Test fun pausingWhileTheApiIsUnavailableCancelsRecoveryEvenIfMetadataLaterSaysPlaying() {
        ready(); play()
        api.ready(false); drain()
        center.update(song.copy(playing = false), true); drain()
        center.update(song, true); drain()
        api.ready(true); drain()
        assertEquals(1, api.requests)
        assertFalse(api.command(4))
    }

    @Test fun losingFocusWhileTheApiIsUnavailableCancelsRecoveryAfterEligibilityReturns() {
        ready(); play()
        api.ready(false); drain()
        center.update(song, false); drain()
        center.update(song, true); drain()
        api.ready(true); drain()
        assertEquals(1, api.requests)
        assertFalse(api.command(4))
        play()
        assertEquals(2, api.requests)
        assertTrue(api.command(4))
    }

    @Test fun commandSuppressionWhileTheApiIsUnavailableCancelsTheOldRecovery() {
        ready(); play()
        api.ready(false); drain()
        center.update(song, true, commandsAllowed = false); drain()
        center.update(song, true, commandsAllowed = true); drain()
        api.ready(true); drain()
        assertEquals(1, api.requests)
        assertFalse(api.command(4))
    }

    @Test fun disconnectWhileTheApiIsUnavailableDoesNotRecoverForTheReplacementSession() {
        ready(); play()
        api.ready(false); drain()
        center.disconnect(); drain()
        center.update(song, true); drain()
        api.ready(true); drain()
        assertEquals(1, api.requests)
        assertFalse(api.command(4))
    }

    @Test fun rejectedPlaybackIsNotRetriedMerelyBecauseTheApiReconnected() {
        api.acceptPlay = false
        ready(); play()
        api.ready(false); drain()
        api.acceptPlay = true
        api.ready(true); drain()
        assertEquals(1, api.requests)
        assertFalse(api.command(4))
    }

    @Test fun eligibilityChangesDuringRecoveryIpcRejectTheStaleClaim() {
        ready(); play()
        api.ready(false); drain()
        api.onRequest = {
            center.update(song, false)
            center.update(song, true)
        }
        api.ready(true); drain()
        assertEquals(2, api.requests)
        assertFalse(api.command(4))
    }

    @Test fun pauseDuringRecoveryIpcRejectsTheStaleClaimEvenIfPlayingMetadataReturns() {
        ready(); play()
        api.ready(false); drain()
        api.onRequest = {
            center.update(song.copy(playing = false), true)
            center.update(song, true)
        }
        api.ready(true); drain()
        assertEquals(2, api.requests)
        assertFalse(api.command(4))
    }

    @Test fun explicitRetryReplacesTheFailedBackendAndInvalidatesItsReadyAndCommandCallbacks() {
        val next = FakeBackend()
        val backends = listOf(api, next)
        var creations = 0
        val owner = Any()
        val owned = PrefaceMediaCenter(Executor { tasks.add(it) }, { backends[creations++] },
            { sent.add(it) }, log::add)
        owned.start(); drain()
        val oldReady = api.ready
        val oldCommand = api.command
        api.ready(false); drain()
        owned.retryInitialization(owner); drain()
        assertEquals(2, creations)
        oldReady(true); oldReady(false); drain()
        assertEquals(0, api.registrations)
        assertEquals(0, next.registrations)
        next.ready(true); drain()
        owned.update(song, true, requestSource = true); drain()
        assertTrue(next.command(4))
        assertFalse(oldCommand(4))
        assertEquals(1, next.requests)
        next.ready(false); drain()
        assertEquals(1, next.unregistrations)
        owned.retryInitialization(owner); drain()
        assertEquals(2, creations)
    }

    @Test fun onlyFailedInitializationCanBeRetriedAndClosedCentersCannotRetry() {
        val owner = Any()
        center.start(); drain()
        center.retryInitialization(owner); drain()
        assertEquals(1, api.initializations)
        api.ready(true); drain()
        center.retryInitialization(owner); drain()
        assertEquals(1, api.initializations)
        center.close(); drain()
        center.retryInitialization(Any()); drain()
        assertEquals(1, api.initializations)
        assertEquals(1, api.unregistrations)
    }

    @Test fun aQueuedRetryCannotInitializeAfterItsControllerWasReplaced() {
        center.start(); drain()
        api.ready(false); drain()
        var current = true
        val owner = Any()
        center.retryInitialization(owner) { current }
        current = false
        drain()
        assertEquals(1, api.initializations)
        // A skipped stale callback does not use this owner's one permitted attempt.
        current = true
        center.retryInitialization(owner) { current }; drain()
        assertEquals(2, api.initializations)
    }

    @Test fun initializationExceptionsAndMissingSdkCanBeRetriedOncePerOwner() {
        api.initializationFailure = IllegalStateException("private details")
        center.start(); drain()
        val first = Any()
        center.retryInitialization(first); drain()
        center.retryInitialization(first); drain()
        assertEquals(2, api.initializations)
        api.initializationFailure = NoClassDefFoundError("missing SDK")
        val second = Any()
        center.retryInitialization(second); drain()
        assertEquals(3, api.initializations)
        assertTrue(center.report().contains("init-unavailable:NoClassDefFoundError"))
        api.initializationFailure = null
        center.retryInitialization(Any()); drain()
        assertEquals(4, api.initializations)
        api.ready(true); drain()
        assertEquals(1, api.registrations)
        assertEquals(0, api.requests)
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

    @Test fun pauseDuringAnInitialPlaybackClaimInvalidatesTheReturnedGrant() {
        ready()
        api.onRequest = {
            center.update(song.copy(playing = false), true)
            center.update(song, true)
        }
        play()
        assertFalse(center.status().ownsSource)
        assertFalse(api.command(4))
        assertTrue(api.publications.isEmpty())
        assertTrue(log.any { it.contains("request-play-invalidated") })
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
        var initializationFailure: Throwable? = null
        var onRegister: () -> Unit = {}
        var onRequest: () -> Unit = {}
        var acceptPlay = true
        var registrations = 0
        var requests = 0
        var unregistrations = 0
        var disconnections = 0
        var initializations = 0
        var closes = 0
        var asynchronousPlay = false
        var asynchronousPublish = false
        val playReplies = ArrayDeque<() -> Unit>()
        val publishReplies = ArrayDeque<() -> Unit>()
        val publications = mutableListOf<CarPlayNowPlaying>()
        val requestTokens = mutableListOf<Any>()
        override fun init(snapshot: () -> PrefaceMediaCenter.Snapshot, command: (Int) -> Boolean,
            ready: (Boolean) -> Unit) {
            initializations++
            initializationFailure?.let { throw it }
            this.command = command; this.ready = ready
        }
        override fun register(): Any? { registrations++; failure?.let { throw it }; onRegister(); return token }
        override fun requestPlay(token: Any): Boolean { requests++; requestTokens.add(token); onRequest(); return acceptPlay }
        override fun publish(token: Any, info: CarPlayNowPlaying): Boolean { publications.add(info); return true }
        override fun requestPlayAsync(token: Any, completed: (Boolean) -> Unit) {
            val accepted = requestPlay(token)
            if (asynchronousPlay) playReplies.add { completed(accepted) } else completed(accepted)
        }
        override fun publishAsync(token: Any, info: CarPlayNowPlaying, completed: (Boolean) -> Unit) {
            val accepted = publish(token, info)
            if (asynchronousPublish) publishReplies.add { completed(accepted) } else completed(accepted)
        }
        override fun disconnect() { disconnections++ }
        override fun unregister(token: Any): Boolean { unregistrations++; return true }
        override fun close() { closes++ }
    }

    @Test fun asynchronousPlayIsNotOwnedBeforeTheRealReplyAndPauseInvalidatesIt() {
        api.asynchronousPlay = true
        ready(); play()
        assertFalse(center.status().ownsSource); assertFalse(api.command(4))
        center.update(song.copy(playing = false), true); drain()
        api.playReplies.removeFirst().invoke(); drain()
        assertFalse(center.status().ownsSource); assertTrue(api.publications.isEmpty())
        assertEquals(1, api.disconnections)
    }

    @Test fun oldRemoteGrantCannotAffectAReplacementSession() {
        api.asynchronousPlay = true
        ready(); play(); center.disconnect()
        center.update(song, true, requestSource = true); drain()
        api.playReplies.removeFirst().invoke(); drain()
        assertFalse(center.status().ownsSource)
        api.playReplies.removeFirst().invoke(); drain()
        assertTrue(center.status().ownsSource); assertEquals(listOf(song), api.publications)
    }

    @Test fun apiLossDuringAPendingRemoteClaimRetainsOnlyUninterruptedPlaybackIntent() {
        api.asynchronousPlay = true
        ready(); play()
        api.ready(false); drain()
        api.ready(true); drain()
        assertEquals(2, api.requests)
        api.playReplies.removeFirst().invoke(); drain()
        assertFalse(center.status().ownsSource)
        api.playReplies.removeFirst().invoke(); drain()
        assertTrue(center.status().ownsSource)
    }

    @Test fun pendingRemotePublicationCoalescesToTheLatestMetadataWithoutReclaiming() {
        api.asynchronousPublish = true
        ready(); play()
        center.update(song.copy(elapsedMillis = 1200L), true); drain()
        assertEquals(1, api.publications.size)
        api.publishReplies.removeFirst().invoke(); drain()
        assertEquals(2, api.publications.size)
        assertEquals(1200L, api.publications.last().elapsedMillis)
        assertEquals(1, api.requests)
        center.close(); drain()
        api.publishReplies.removeFirst().invoke(); drain()
        assertFalse(center.status().ownsSource)
    }

    @Test fun closingBeforeSdkReadyReleasesTheBackendWithoutARegistrationToken() {
        center.start(); drain(); center.close(); drain()
        assertEquals(1, api.closes); assertEquals(0, api.unregistrations)
    }
}
