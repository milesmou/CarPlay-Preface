package com.shilapi.xcertplay.airplay

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaButtonTest {
    @Test
    fun steeringWheelKeysMapToCarPlayMediaPresses() {
        assertEquals(CarPlayMediaButton.NEXT, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(CarPlayMediaButton.PLAY, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(CarPlayMediaButton.PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_HEADSETHOOK))
        assertNull(CarPlayMediaButton.forKeyCode(353))
        assertNull(CarPlayMediaButton.forKeyCode(331))
    }

    @Test
    fun theVoiceKeyOpensSiri() {
        // E02's physical previous-track key is not a standard voice-assistant key.
        assertFalse(CarPlayMediaButton.opensSiri(304))
        assertFalse(CarPlayMediaButton.opensSiri(312))
        assertTrue(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_VOICE_ASSIST))
        assertFalse(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertNull(CarPlayMediaButton.forKeyCode(304))
    }

    @Test
    fun otherKeysAreLeftToTheSystem() {
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_STOP))
    }

    @Test
    fun indicesMatchTheAdvertisedMediaHidReport() {
        // Media report usages: 0 none, 1 play, 2 pause, 3 play/pause, 4 next, 5 previous.
        assertEquals(3, CarPlayMediaButton.PLAY_PAUSE)
        assertEquals(4, CarPlayMediaButton.NEXT)
        assertEquals(5, CarPlayMediaButton.PREVIOUS)
    }

    @Test
    fun theWindowClaimsTrackKeysOnlyWhileCarPlayIsRunning() {
        fun claim(keyCode: Int, sessionActive: Boolean = true, inCall: Boolean = false) =
            CarPlayMediaButton.forCarPlayWindow(keyCode, sessionActive, inCall)

        assertEquals(CarPlayMediaButton.NEXT, claim(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarPlayMediaButton.PREVIOUS, claim(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        // Without a session, or during a call, the wheel keeps the car's own action.
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_NEXT, sessionActive = false))
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_PREVIOUS, sessionActive = false))
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_NEXT, inCall = true))
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_PREVIOUS, inCall = true))
    }

    @Test
    fun theWindowHandlesPlayPauseWithoutBluetoothAndLeavesUnrelatedKeysAlone() {
        for ((keyCode, press) in listOf(KeyEvent.KEYCODE_MEDIA_PLAY to CarPlayMediaButton.PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE to CarPlayMediaButton.PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to CarPlayMediaButton.PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK to CarPlayMediaButton.PLAY_PAUSE)) {
            assertEquals(press, CarPlayMediaButton.forCarPlayWindow(keyCode, true, false))
            assertNull(CarPlayMediaButton.forCarPlayWindow(keyCode, true, false, bluetoothAudio = true))
        }
        for (keyCode in listOf(353, 331, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_VOLUME_UP)) {
            assertNull("keyCode=$keyCode", CarPlayMediaButton.forCarPlayWindow(keyCode, true, false))
        }
    }

    @Test fun bluetoothAudioDoesNotForwardTrackKeysTwice() {
        assertNull(CarPlayMediaButton.forCarPlayWindow(KeyEvent.KEYCODE_MEDIA_NEXT, true, false, true))
        assertNull(CarPlayMediaButton.forCarPlayWindow(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true, false, true))
    }
}
