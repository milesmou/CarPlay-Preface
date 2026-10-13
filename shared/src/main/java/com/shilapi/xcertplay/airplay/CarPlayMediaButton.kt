package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

/**
 * Hardware media keys → CarPlay media HID presses (indices into [AirPlayHid]'s media report).
 *
 * Hardware play and pause keys map to the toggle; media controllers use explicit [PLAY] and [PAUSE].
 */
object CarPlayMediaButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    /** Standard Android voice-assistant key. Custom vehicle keys can be learned in settings. */
    fun opensSiri(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST

    /** The CarPlay press for [keyCode], or null when the key is not a media key CarPlay handles. */
    fun forKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK -> PLAY_PAUSE
        else -> null
    }

    /**
     * The CarPlay press the CarPlay screen itself should claim for [keyCode], or null to let the key
     * go on to the car.
     *
     * Claims standard play/pause and track keys while a session is running. During calls, outside
     * a session, and when Bluetooth AVRCP controls the iPhone, keys retain the car's own action.
     */
    fun forCarPlayWindow(
        keyCode: Int,
        sessionActive: Boolean,
        inCall: Boolean,
        bluetoothAudio: Boolean = false,
    ): Int? {
        if (!sessionActive || inCall || bluetoothAudio) return null
        return forKeyCode(keyCode)
    }
}
