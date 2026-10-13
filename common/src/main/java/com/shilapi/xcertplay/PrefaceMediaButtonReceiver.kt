package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/** IHU519G's input service broadcasts ordinary Android media events instead of window events. */
internal class PrefaceMediaButtonReceiver(
    private val eligible: () -> Boolean,
    private val send: (Int) -> Boolean,
) : BroadcastReceiver() {
    private data class Press(val device: Int, val key: Int, val downTime: Long)
    private val recent = ArrayDeque<Press>()

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        handle(event)
    }

    /** Shared by broadcasts, MediaSession and the foreground window. */
    fun handle(event: KeyEvent): Boolean {
        if (!eligible()) return false
        val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val press = Press(event.deviceId, event.keyCode, event.downTime)
            if (press !in recent) {
                // A failed dispatch must not suppress a later delivery through another path.
                if (!send(index)) return false
                recent.addLast(press)
                if (recent.size > 32) recent.removeFirst()
            }
        }
        return true
    }
}
