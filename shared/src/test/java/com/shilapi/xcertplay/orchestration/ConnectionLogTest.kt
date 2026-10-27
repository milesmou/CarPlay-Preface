package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test

class ConnectionLogTest {
    @Test fun aFailedStateKeepsItsActionableReasonAndRetryPolicy() {
        assertEquals("Connection state=Failed retryable=false reason=VPN authorization denied",
            CarPlayStatus.Failed("VPN authorization denied", retryable = false).retainedDiagnosticMessage())
    }

    @Test fun successfulStatesDoNotDumpHotspotCredentialsIntoRetainedEvents() {
        assertEquals("Connection state=HotspotReady", CarPlayStatus.HotspotReady(
            ssid = "private-network", band = "5GHz", channel = 149, bssid = "private-bssid",
            address = "192.0.2.1", backend = "LocalOnlyHotspot", passphrase = "private-password",
        ).retainedDiagnosticMessage())
        assertEquals("Connection state=RunningControl", CarPlayStatus.RunningControl.retainedDiagnosticMessage())
    }
}
