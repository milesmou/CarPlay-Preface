package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PrefaceTrackKeyInterceptionTest {
    @Test fun requestReceiptAndExclusivityAreSeparateAndRetiredCallbacksDoNotConfirmANewRequest() {
        val input = Input()
        val wheel = PrefaceTrackKeyInterception(input) {}
        wheel.update(true)
        assertTrue(wheel.status().accepted)
        assertEquals(0L, wheel.status().receivedEvents)
        assertFalse(wheel.status().exclusiveConfirmed)
        input.callback(true, 305)
        assertEquals(1L, wheel.status().receivedEvents)
        assertFalse(wheel.status().exclusiveConfirmed)
        val retired = input.callback
        wheel.close(); wheel.update(true)
        retired(true, 305)
        assertEquals(0L, wheel.status().receivedEvents)
        input.callback(true, 87)
        assertEquals(1L, wheel.status().receivedEvents)
        assertTrue(wheel.report().contains("exclusiveHandling=unconfirmed"))
        wheel.close()
    }

    @Test fun failedRequestCannotBeReportedAsAcceptedEvenIfReleaseSucceeds() {
        val input = Input().apply { denied = true }
        val wheel = PrefaceTrackKeyInterception(input) {}
        wheel.update(true)
        assertFalse(wheel.status().accepted)
        assertFalse(wheel.status().requested)
        assertTrue(wheel.status().unavailable)
        assertFalse(wheel.status().exclusiveConfirmed)
    }
    @Test fun volumePermissionDenialRetriesMediaOnlyAndOldCallbacksCannotAdjustVolume() {
        val callbacks = mutableListOf<(Boolean, Int) -> Unit>()
        val requests = mutableListOf<Boolean>()
        var releases = 0
        val backend = object : PrefaceTrackKeyInterception.Backend {
            override fun request(callback: (Boolean, Int) -> Unit) = request(callback, false)
            override fun request(callback: (Boolean, Int) -> Unit, volumeKeys: Boolean) {
                requests += volumeKeys
                callbacks += callback
                if (volumeKeys) throw SecurityException("volume denied")
            }
            override fun release() { releases++ }
        }
        val volume = mutableListOf<Int>()
        val media = mutableListOf<Int>()
        val wheel = PrefaceTrackKeyInterception(backend, volume = { _, key -> volume += key }) { media += it }
        wheel.update(true, true)
        callbacks.first()(true, 301)
        callbacks.last()(true, 301)
        callbacks.last()(true, KeyEvent.KEYCODE_MEDIA_NEXT)
        wheel.update(true, true)
        assertEquals(listOf(true, false), requests)
        assertTrue(volume.isEmpty())
        assertEquals(listOf(CarPlayMediaButton.NEXT), media)
        assertEquals(1, releases)
    }

    @Test fun volumeOwnershipCanBeReleasedWithoutLosingMediaKeySupport() {
        val requests = mutableListOf<Boolean>()
        lateinit var callback: (Boolean, Int) -> Unit
        val backend = object : PrefaceTrackKeyInterception.Backend {
            override fun request(next: (Boolean, Int) -> Unit) = request(next, false)
            override fun request(next: (Boolean, Int) -> Unit, volumeKeys: Boolean) {
                requests += volumeKeys
                callback = next
            }
            override fun release() = Unit
        }
        val volume = mutableListOf<Int>()
        val media = mutableListOf<Int>()
        val wheel = PrefaceTrackKeyInterception(backend, volume = { _, key -> volume += key }) { media += it }
        wheel.update(true, true)
        val old = callback
        callback(true, 301)
        wheel.update(true, false)
        old(true, 302)
        callback(true, 302)
        callback(true, KeyEvent.KEYCODE_MEDIA_NEXT)
        assertEquals(listOf(301), volume)
        assertEquals(listOf(CarPlayMediaButton.NEXT), media)
        assertEquals(listOf(true, false), requests)
    }
    private class Input : PrefaceTrackKeyInterception.Backend {
        var requests = 0
        var releases = 0
        var denied = false
        lateinit var callback: (Boolean, Int) -> Unit
        override fun request(callback: (Boolean, Int) -> Unit) {
            requests++
            this.callback = callback
            if (denied) throw SecurityException("System denies track interception")
        }
        override fun release() { releases++ }
    }

    @Test fun trackPressesDispatchOnceAndLeaveOtherKeysAlone() {
        val input = Input()
        val sent = mutableListOf<Int>()
        val wheel = PrefaceTrackKeyInterception(input) { sent += it }
        wheel.update(true)
        wheel.update(true)
        input.callback(true, KeyEvent.KEYCODE_MEDIA_NEXT)
        input.callback(true, KeyEvent.KEYCODE_MEDIA_NEXT)
        input.callback(false, KeyEvent.KEYCODE_MEDIA_NEXT)
        input.callback(true, KeyEvent.KEYCODE_MEDIA_NEXT)
        input.callback(true, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        input.callback(true, KeyEvent.KEYCODE_VOLUME_UP)
        input.callback(true, KeyEvent.KEYCODE_VOICE_ASSIST)
        assertEquals(listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS), sent)
        assertEquals(1, input.requests)
    }

    @Test fun physicalE02CodesAndStandardCodesShareTheSameHeldTrack() {
        val input = Input()
        val sent = mutableListOf<Int>()
        val wheel = PrefaceTrackKeyInterception(input) { sent += it }
        wheel.update(true)
        input.callback(true, 305)
        input.callback(true, KeyEvent.KEYCODE_MEDIA_NEXT)
        input.callback(false, KeyEvent.KEYCODE_MEDIA_NEXT)
        input.callback(true, 304)
        input.callback(false, 304)
        input.callback(true, 305)
        assertEquals(listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.NEXT), sent)
    }

    @Test fun playPauseAndVoicePressesDoNotRepeatOrFireAfterOwnershipEnds() {
        val input = Input()
        val sent = mutableListOf<Int>()
        var siriRequests = 0
        val wheel = PrefaceTrackKeyInterception(input, siri = { siriRequests++ }) { sent += it }
        wheel.update(true)
        input.callback(true, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        input.callback(true, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        input.callback(false, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        input.callback(true, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        input.callback(false, 298)
        assertEquals(0, siriRequests)
        input.callback(true, 298)
        input.callback(true, 298)
        input.callback(false, 298)
        input.callback(false, 298)
        assertEquals(1, siriRequests)
        input.callback(true, 298)
        wheel.update(false)
        input.callback(false, 298)
        assertEquals(1, siriRequests)
        assertEquals(listOf(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.PLAY_PAUSE), sent)
    }

    @Test fun lostOwnershipReleasesAndQueuedCallbacksCannotControlANewOwner() {
        val input = Input()
        val sent = mutableListOf<Int>()
        val wheel = PrefaceTrackKeyInterception(input) { sent += it }
        wheel.update(false)
        assertEquals(0, input.requests)
        wheel.update(true)
        val oldCallback = input.callback
        wheel.update(false)
        oldCallback(true, KeyEvent.KEYCODE_MEDIA_NEXT)
        wheel.update(true)
        oldCallback(true, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        input.callback(true, KeyEvent.KEYCODE_MEDIA_NEXT)
        wheel.close()
        wheel.close()
        input.callback(true, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        assertEquals(listOf(CarPlayMediaButton.NEXT), sent)
        assertEquals(2, input.requests)
        assertEquals(2, input.releases)
    }

    @Test fun permissionDenialCleansUpWithoutRetryingOrDispatching() {
        val input = Input().apply { denied = true }
        val sent = mutableListOf<Int>()
        val wheel = PrefaceTrackKeyInterception(input) { sent += it }
        wheel.update(true)
        input.callback(true, KeyEvent.KEYCODE_MEDIA_NEXT)
        wheel.update(false)
        wheel.update(true)
        assertTrue(sent.isEmpty())
        assertEquals(1, input.requests)
        assertEquals(1, input.releases)
    }
}
