package com.shilapi.xcertplay.network

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SystemUsbDiagnosticsTest {
    private val address = InetAddress.getByName("fe80::1234:5678:9abc:def0").address
    private val hex = "fe80000000000000123456789abcdef0"
    private val link get() = SystemUsbNetwork.Link("usb0", Inet6Address.getByAddress(null, address, 3),
        byteArrayOf(2, 1, 2, 3, 4, 5), 1 to 7, setOf(2, 3))

    @Test fun addressFlagsAreScopedToTheSelectedNicAndNeverExportAddresses() {
        for ((flags, state) in listOf("80" to "ready", "40" to "tentative", "48" to "dad_failed", "20" to "deprecated")) {
            val text = "$hex 03 40 20 $flags usb0\n$hex 04 40 20 40 usb1\n"
            val result = SystemUsbSnapshot.addressSummary(text, "usb0", address)
            assertTrue(result, result.contains("ipv6Addresses=1"))
            assertTrue(result, result.contains("advertisedState=$state"))
            assertFalse(result.contains(hex))
        }
        assertTrue(SystemUsbSnapshot.addressSummary("", "usb0", address).contains("advertisedState=absent"))
    }

    @Test fun routesExcludeOtherNicsAndRejectedLinkLocalDestinations() {
        fun route(dest: String, prefix: String, flags: String, nic: String) =
            "$dest $prefix ${"0".repeat(32)} 00 ${"0".repeat(32)} 00000100 00000000 00000000 $flags $nic"
        val text = listOf(route(hex, "40", "00000001", "usb0"),
            route(hex, "40", "00000201", "usb0"), route(hex, "40", "00000001", "usb1"),
            route("0".repeat(32), "00", "00000001", "usb0")).joinToString("\n")
        val result = SystemUsbSnapshot.routeSummary(text, "usb0")
        assertTrue(result, result.contains("ipv6Routes=3 linkLocalUpRoutes=1 defaultRoutes=1 rejectedRoutes=1"))
        assertFalse(result.contains(hex))
    }

    @Test fun neighborsCountFailureAndStaleStatesWithoutLeakingPeerOrMac() {
        val result = SystemUsbSnapshot.neighborSummary(
            "fe80::11 dev usb0 lladdr aa:bb:cc:dd:ee:ff STALE\n" +
                "fe80::12 dev usb0 FAILED\nfe80::13 INCOMPLETE\nfe80::14 dev usb1 REACHABLE\n", "usb0")
        assertTrue(result, result.contains("ipv6Neighbors=3"))
        assertTrue(result.contains("stale=1")); assertTrue(result.contains("failed=1")); assertTrue(result.contains("incomplete=1"))
        assertFalse(result.contains("fe80")); assertFalse(result.contains("aa:bb"))
    }

    @Test fun kernelTcpStatesMatchBothAdvertisedAddressAndBoundPort() {
        val local = "000080FE0000000078563412F0DEBC9A"
        fun tcp(addr: String, port: String, state: String) = "0: $addr:$port ${"0".repeat(32)}:0000 $state 0"
        val text = " sl local_address remote_address st\n" + listOf(tcp(local, "1B58", "0A"),
            tcp(local, "1B58", "03"), tcp(local, "1B58", "01"), tcp(local, "1B59", "01"),
            tcp("0".repeat(32), "1B58", "01")).joinToString("\n")
        val result = SystemUsbSnapshot.tcpSummary(text, address, 7000)
        assertTrue(result, result.contains("listening=1 synReceived=1 established=1"))
        assertFalse(result.contains(local))
    }

    @Test fun inaccessibleSourcesStayUnavailableWhileCountersContinueAndResetSafely() {
        var value = 100L
        var addressReads = 0
        val sample = SystemUsbSnapshot(link, read = { path, _ ->
            when {
                path == "/proc/net/if_inet6" -> { addressReads++; throw IOException("private details must not reach report") }
                path.endsWith("/operstate") -> "up"
                path.endsWith("/carrier") -> "1"
                path.contains("/statistics/") -> value.toString()
                else -> ""
            }
        }, neighborRead = { throw SecurityException("private network output") }, interfaceRead = { "iface=usb0 up=true" })
        assertTrue(sample.sample().contains("rx_packetsDelta=baseline"))
        value = 110L
        val second = sample.sample()
        assertTrue(second.contains("rx_packetsDelta=10")); assertTrue(second.contains("tx_bytesDelta=10"))
        assertTrue(second.contains("addresses=unavailable failureClass=IOException"))
        assertTrue(second.contains("neighbors=unavailable failureClass=SecurityException"))
        assertFalse(second.contains("private")); assertEquals(1, addressReads)
        value = 1L
        assertTrue(sample.sample().contains("rx_packetsDelta=reset"))
    }

    @Test fun malformedKernelRowsAreNotReportedAsZeroTraffic() {
        val snapshot = SystemUsbSnapshot(link, read = { _, _ -> "not valid" }, neighborRead = { "Permission denied" },
            interfaceRead = { "iface=usb0" })
        val result = snapshot.sample()
        assertTrue(result.contains("addresses=unavailable")); assertTrue(result.contains("routes=unavailable"))
        assertTrue(result.contains("neighbors=unavailable")); assertTrue(result.contains("rx_packets=unavailable"))
        assertFalse(result.contains("ipv6Neighbors=0"))
    }

    @Test fun startupDistinguishesTcpFromSessionAndIgnoresInternalProbes() {
        var now = 0L
        val lines = CopyOnWriteArrayList<String>()
        val observer = SystemUsbStartupDiagnostics({ "iface=usb0" }, lines::add, nowNs = { now })
        observer.startSessionSent()
        now = 2_000_000_000L
        observer.tcpAccepted(internal = true)
        assertTrue(observer.summary("test").contains("tcpAccepted=0"))
        observer.tcpAccepted()
        assertTrue(observer.summary("test").contains("firstTcpAfterStartMs=2000 waitingFor=AirPlay_protocol"))
        observer.sessionActive()
        observer.close(); observer.startSessionSent(); observer.tcpAccepted()
        assertEquals(1, lines.count { it.contains("observation=ended") })
        assertTrue(lines.last().contains("sessionActive=true"))
    }

    @Test fun protocolCallbacksAndCloseNeverWaitForABlockedSnapshot() {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val lines = CopyOnWriteArrayList<String>()
        val observer = SystemUsbStartupDiagnostics({
            entered.countDown()
            try { CountDownLatch(1).await(5, TimeUnit.SECONDS); "unused" }
            finally { stopped.countDown() }
        }, lines::add)
        observer.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        observer.startSessionSent(); observer.tcpAccepted(); observer.finish("startup_timeout")
        assertTrue(observer.summary("test").contains("tcpAccepted=1"))
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertEquals(1, lines.count { it.contains("observation=ended") })
    }
}
