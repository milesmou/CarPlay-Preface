package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WirelessHotspotLeaseTest {
    private var cancelled = false
    private var starts = 0
    private var closes = 0
    private var valid = true
    private var onStart: () -> Unit = {}
    private val info = WirelessHotspotInfo("temporary-test", "test12345", Iap2WirelessSecurity.WPA_WPA2,
        0, null, null, "ap0", InetAddress.getByName("192.0.2.1"), "2.4 GHz", WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT)
    private val manager = object : WirelessHotspotManager {
        override fun start(timeoutMillis: Long): WirelessHotspotInfo { starts++; onStart(); return info }
        override fun validateReady() { if (!valid) throw IOException("interface disappeared") }
        override fun close() { closes++ }
    }
    private fun lease() = WirelessHotspotLease(
        RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
        Any(), { cancelled }, { true }, { true }, {}, { _, close -> close() }, { manager },
    )

    @Test fun retryRevalidatesAndReusesReservation() {
        val lease = lease()
        assertSame(info, lease.start(1))
        lease.release(keepHotspot = true, closed = false, retainTemporaryHotspotOnClose = false)
        assertSame(info, lease.start(2))
        assertEquals(1, starts)
        assertEquals(0, closes)
        lease.release(false, true, false)
        lease.release(false, true, false)
        assertEquals(1, closes)
        assertNull(lease.currentManager)
    }

    @Test fun lostInterfaceClosesOldReservationBeforeRecreating() {
        val lease = lease()
        lease.start(1)
        valid = false
        lease.start(2)
        assertEquals(2, starts)
        assertEquals(1, closes)
        lease.release(false, true, false)
    }

    @Test fun cancellationBeforeStartDoesNotAcquireResource() {
        val lease = lease()
        cancelled = true
        assertThrows(IOException::class.java) { lease.start(1) }
        assertEquals(0, starts)
        assertNull(lease.currentManager)
    }

    @Test fun cancellationDuringStartReleasesResourceAndCannotPublishReadiness() {
        val lease = lease()
        onStart = { cancelled = true }
        assertThrows(IOException::class.java) { lease.start(1) }
        assertEquals(1, closes)
        assertNull(lease.currentManager)
        assertNull(lease.retainedInfo)
    }
}
