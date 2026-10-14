package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PrefaceVoiceButtonReceiverTest {
    private fun event(action: Int = 0) = Intent(PrefaceVoiceButtonReceiver.ACTION)
        .addCategory(Intent.CATEGORY_DEFAULT)
        .putExtra(PrefaceVoiceButtonReceiver.KEY_TYPE, 200231)
        .putExtra(PrefaceVoiceButtonReceiver.KEY_ACTION, action)

    @Test fun onlyConfirmedVoiceButtonActionsRequestSiri() {
        var requests = 0
        val receiver = PrefaceVoiceButtonReceiver { requests++; true }
        receiver.onReceive(null, event(0))
        receiver.onReceive(null, event(1))
        receiver.onReceive(null, event(2))
        receiver.onReceive(null, event().putExtra(PrefaceVoiceButtonReceiver.KEY_TYPE, 200087))
        receiver.onReceive(null, Intent(PrefaceVoiceButtonReceiver.ACTION))
        receiver.onReceive(null, event().setAction("unrelated"))
        assertEquals(2, requests)
    }

    @Test fun orderedVoiceEventRemainsVehicleOwnedUntilSiriAcceptsIt() {
        val context = RuntimeEnvironment.getApplication()
        var accepts = false
        var vehicleRequests = 0
        val receiver = PrefaceVoiceButtonReceiver { accepts }
        val vehicle = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { vehicleRequests++ }
        }
        val filter = IntentFilter(PrefaceVoiceButtonReceiver.ACTION).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            priority = 1000
        }
        context.registerReceiver(receiver, filter)
        context.registerReceiver(vehicle, IntentFilter(filter).apply { priority = 0 })
        try {
            context.sendOrderedBroadcast(event(), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, vehicleRequests)
            accepts = true
            context.sendOrderedBroadcast(event(), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, vehicleRequests)
        } finally {
            context.unregisterReceiver(receiver)
            context.unregisterReceiver(vehicle)
        }
    }
}
