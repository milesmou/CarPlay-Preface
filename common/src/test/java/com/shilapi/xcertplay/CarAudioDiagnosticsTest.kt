package com.shilapi.xcertplay

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarAudioDiagnosticsTest {
    @Test fun receiverSidePlayingEventCorrelatesTheSelectedPhoneWithoutExportingIdentity() {
        val app = RuntimeEnvironment.getApplication()
        DiPlayPreferences.savePhone(app, "12:34:56:78:90:AB", "private-phone")
        val lines = mutableListOf<String>()
        val monitor = CarAudioDiagnostics(app, lines::add) { 0L }
        val event = Intent("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED")
            .putExtra(BluetoothProfile.EXTRA_STATE, 10).putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, 11)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, ShadowBluetoothDevice.newInstance("12:34:56:78:90:AB"))
        monitor.receive(event); monitor.receive(event)
        assertEquals(1, lines.count { it.contains("event=a2dp_sink_playing state=10") })
        assertTrue(lines.any { it.contains("selectedPhoneMatch=true") })
        assertTrue(lines.all { !it.contains("12:34") && !it.contains("private") && DiagnosticRedactor.redact(it) != null })
        monitor.close()
        monitor.receive(Intent(event).putExtra(BluetoothProfile.EXTRA_STATE, 11))
        assertEquals(1, lines.count { it.contains("event=") })
    }

    @Test fun receiverClosesAndPollingAndEventsHaveBoundedBudgets() {
        val app = RuntimeEnvironment.getApplication()
        var clock = 0L
        val lines = mutableListOf<String>()
        val monitor = CarAudioDiagnostics(app, lines::add) { clock }
        monitor.start()
        repeat(200) { i ->
            monitor.receive(Intent("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED")
                .putExtra(BluetoothProfile.EXTRA_STATE, 10 + i % 2))
            clock += 1000
            monitor.sample("iphone_play")
        }
        assertEquals(64, lines.count { it.contains("event=") })
        assertEquals(48, lines.count { it.contains("sample=") })
        assertTrue(lines.any { it.contains("oemSelectedSource=unavailable") })
        assertTrue(lines.any { it.contains("playingState=event_only profileAvailability=unverified absenceOfEventsNotProof=true") })
        monitor.close(); val count = lines.size; monitor.sample("after_close")
        assertEquals(count, lines.size)
    }
}
