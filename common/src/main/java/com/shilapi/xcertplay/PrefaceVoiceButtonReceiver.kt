package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** E02 input service's ordered voice-key broadcast, also usable when input interception is denied. */
internal class PrefaceVoiceButtonReceiver(private val requestSiri: () -> Boolean) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION || intent.getIntExtra(KEY_TYPE, -1) != 200231 ||
            intent.getIntExtra(KEY_ACTION, -1) !in 0..1) return
        // Only consume the OEM assistant's ordered event once CarPlay accepts this request.
        if (requestSiri() && isOrderedBroadcast) abortBroadcast()
    }

    companion object {
        const val ACTION = "ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT"
        const val KEY_TYPE = "ecarx.extra.ECARX_KEY_EVENT_TYPE"
        const val KEY_ACTION = "ecarx.extra.ECARX_KEY_ACTION_TYPE"
    }
}
