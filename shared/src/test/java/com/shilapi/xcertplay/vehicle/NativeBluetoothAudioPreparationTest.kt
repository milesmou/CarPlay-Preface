package com.shilapi.xcertplay.vehicle

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeBluetoothAudioPreparationTest {
    private var eligible = true
    private var available = true
    private val log = mutableListOf<String>()
    private lateinit var connection: NativeBluetoothAudioPreparation.Connection
    private val browser = object : NativeBluetoothAudioPreparation.Browser {
        var connects = 0
        var prepares = 0
        var closes = 0
        override fun connect() { connects++ }
        override fun prepare() { prepares++ }
        override fun close() { closes++ }
    }
    private fun preparation() = NativeBluetoothAudioPreparation(RuntimeEnvironment.getApplication(),
        { eligible }, log::add, { available }, { callback -> connection = callback; browser })

    @Test fun preparesNativePlayerOnceAndReleasesBindingWithoutClaimingPlayback() {
        val operation = preparation()
        operation.start()
        assertEquals(1, browser.connects)
        assertEquals(0, browser.prepares)
        connection.connected()
        assertEquals(1, browser.prepares)
        assertEquals(1, browser.closes)
        assertTrue(log.single().contains("focusConfirmed=false playbackConfirmed=false"))
        operation.start(); connection.connected(); connection.failed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(1, browser.prepares)
        assertEquals(1, log.size)
    }

    @Test fun cancellingBeforeConnectionInvalidatesLateCallbacks() {
        val operation = preparation()
        operation.start(); operation.close(); connection.connected()
        assertEquals(0, browser.prepares)
        assertEquals(1, browser.closes)
        assertTrue(log.isEmpty())
    }

    @Test fun phoneOrCallChangeBeforeConnectedPreventsPreparation() {
        val operation = preparation()
        operation.start(); eligible = false; connection.connected()
        assertEquals(0, browser.prepares)
        assertEquals(1, browser.closes)
        assertTrue(log.single().contains("eligibility_changed"))
    }

    @Test fun nativeServiceIdentityIsRecheckedBeforeDispatch() {
        val operation = preparation()
        operation.start(); available = false; connection.connected()
        assertEquals(0, browser.prepares)
        assertEquals(1, browser.closes)
    }

    @Test fun missingNativeServiceDoesNotConnectOrRetry() {
        available = false
        val operation = preparation()
        operation.start(); available = true; operation.start()
        assertEquals(0, browser.connects)
        assertEquals(0, browser.prepares)
        assertTrue(log.single().contains("unavailable_or_ineligible"))
    }

    @Test fun stalledConnectionTimesOutAndCannotPrepareLater() {
        val operation = preparation()
        operation.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        connection.connected(); operation.start()
        assertEquals(1, browser.connects)
        assertEquals(0, browser.prepares)
        assertEquals(1, browser.closes)
        assertTrue(log.single().contains("timed_out"))
    }
}
