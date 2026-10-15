package com.shilapi.xcertplay.network

import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Optional read-only evidence. Never exports addresses, MACs, USB identities or process output. */
internal class SystemUsbSnapshot(
    private val link: SystemUsbNetwork.Link,
    private val read: (String, Int) -> String = ::readBoundedDiagnosticFile,
    private val neighborRead: (String) -> String = ::readUsbNeighbors,
    private val interfaceRead: () -> String = { interfaceSummary(link) },
    private val listenerPort: () -> Int? = { null },
) {
    private var previousCounters: Map<String, Long> = emptyMap()
    private var previousProtocolCounters: Map<String, Long> = emptyMap()
    private val unavailable = mutableMapOf<String, String>()

    fun sample(): String = listOf(
        source("interface") { interfaceRead() },
        source("addresses") { addressSummary(read("/proc/net/if_inet6", PROC_LIMIT), link.name, link.address.address) },
        source("routes") { routeSummary(read("/proc/net/ipv6_route", PROC_LIMIT), link.name) },
        source("neighbors") { neighborSummary(neighborRead(link.name), link.name) },
        source("tcp6") { tcpSummary(read("/proc/net/tcp6", PROC_LIMIT), link.address.address, listenerPort()) },
        counterSummary(),
        source("linkState") {
            val base = "/sys/class/net/${link.name}"
            val state = read("$base/operstate", 32).trim().takeIf { it in OPERSTATES } ?: "unknown"
            val carrier = read("$base/carrier", 32).trim().takeIf { it == "0" || it == "1" } ?: "unknown"
            "operstate=$state carrier=$carrier"
        },
        source("ipv6ProtocolCounters") {
            val values = protocolCounters(read("/proc/net/dev_snmp6/${link.name}", PROC_LIMIT), link.address.scopeId)
            val lines = values.entries.chunked(4).joinToString("\n") { group ->
                "ipv6ProtocolCounters scope=selected_interface " + group.joinToString(" ") { (name, value) ->
                    val before = previousProtocolCounters[name]
                    "$name=$value ${name}Delta=${when { before == null -> "baseline"; value < before -> "reset"; else -> value - before }}"
                }
            }
            previousProtocolCounters = values
            lines
        },
    ).joinToString("\n")

    private fun source(name: String, action: () -> String): String {
        unavailable[name]?.let { return "$name=unavailable failureClass=$it" }
        return try { action() } catch (error: Exception) {
            unavailable[name] = error.javaClass.simpleName
            "$name=unavailable failureClass=${error.javaClass.simpleName}"
        }
    }

    private fun counterSummary(): String {
        val values = mutableMapOf<String, Long>()
        val fields = COUNTERS.map { key ->
            source(key) {
                val value = read("/sys/class/net/${link.name}/statistics/$key", 32).trim().toLong()
                if (value < 0) throw IOException("Invalid counter")
                values[key] = value
                val before = previousCounters[key]
                "$key=$value ${key}Delta=${when { before == null -> "baseline"; value < before -> "reset"; else -> value - before }}"
            }
        }
        previousCounters = values
        return fields.chunked(4).joinToString("\n") { "interfaceCounters " + it.joinToString(" ") }
    }

    companion object {
        private const val PROC_LIMIT = 64 * 1024
        private val COUNTERS = listOf("rx_packets", "rx_bytes", "rx_errors", "rx_dropped", "tx_packets", "tx_bytes", "tx_errors", "tx_dropped")
        private val PROTOCOL_COUNTERS = setOf("Ip6InReceives", "Ip6InHdrErrors", "Ip6InDiscards", "Ip6InDelivers",
            "Ip6OutRequests", "Ip6OutDiscards", "Ip6OutNoRoutes", "Ip6InAddrErrors", "Icmp6InMsgs", "Icmp6OutMsgs",
            "Icmp6InDestUnreachs", "Icmp6OutDestUnreachs", "Icmp6InNeighborSolicits", "Icmp6InNeighborAdvertisements",
            "Icmp6OutNeighborSolicits", "Icmp6OutNeighborAdvertisements", "Ip6InUnknownProtos", "Ip6InNoRoutes")
        private val OPERSTATES = setOf("unknown", "notpresent", "down", "lowerlayerdown", "testing", "dormant", "up")
        private val HEX_ADDRESS = Regex("[0-9a-fA-F]{32}")
        internal fun protocolCounters(text: String, expectedIndex: Int? = null): Map<String, Long> {
            val result = linkedMapOf<String, Long>()
            var index: Int? = null
            for (line in text.lineSequence()) {
                val columns = line.trim().split(Regex("\\s+"))
                if (columns.size == 2 && columns[0] == "ifIndex") index = columns[1].toIntOrNull()
                if (columns.size != 2 || columns[0] !in PROTOCOL_COUNTERS) continue
                columns[1].toLongOrNull()?.takeIf { it >= 0 }?.let { result[columns[0]] = it }
            }
            if (expectedIndex != null && index != expectedIndex) throw IOException("IPv6 counter interface index unavailable or replaced")
            if (result.isEmpty()) throw IOException("No supported per-interface IPv6 counters")
            return result
        }
        private val STATES = listOf("INCOMPLETE", "FAILED", "REACHABLE", "STALE", "DELAY", "PROBE", "PERMANENT", "NOARP")

        private fun interfaceSummary(link: SystemUsbNetwork.Link): String {
            val nic = NetworkInterface.getByName(link.name) ?: return "interface=missing"
            val usb = File("/sys/class/net/${link.name}/device").canonicalFile
            // Keep access failure distinct from a real device replacement.
            val bus = readBoundedDiagnosticFile(File(usb.parentFile, "busnum").path, 32).trim().toInt()
            val dev = readBoundedDiagnosticFile(File(usb.parentFile, "devnum").path, 32).trim().toInt()
            val number = readBoundedDiagnosticFile(File(usb, "bInterfaceNumber").path, 32).trim().toInt(16)
            val driver = File(usb, "driver").takeIf { it.exists() }?.canonicalFile?.name
                ?.takeIf { Regex("[A-Za-z0-9_.-]{1,48}").matches(it) } ?: "unavailable"
            val addressPresent = Collections.list(nic.inetAddresses).any { it.address.contentEquals(link.address.address) }
            return "iface=${link.name} up=${nic.isUp} mtu=${nic.mtu} indexMatches=${nic.index == link.address.scopeId} " +
                "ownerMatches=${link.identity == (bus to dev)} ncmFunctionMatches=${number in link.interfaceIds} " +
                "driver=$driver advertisedAddressPresent=$addressPresent macMatches=${nic.hardwareAddress?.contentEquals(link.mac)}"
        }

        internal fun addressSummary(text: String, name: String, advertised: ByteArray): String {
            var total = 0; var tentative = 0; var failed = 0; var deprecated = 0
            var advertisedState = "absent"
            val expected = advertised.joinToString("") { "%02x".format(it.toInt() and 255) }
            for (row in text.lineSequence().filter { it.isNotBlank() }) {
                val fields = row.trim().split(Regex("\\s+"))
                if (fields.size != 6 || !HEX_ADDRESS.matches(fields[0])) throw IOException("Invalid IPv6 address row")
                val flags = fields[4].toIntOrNull(16) ?: throw IOException("Invalid IPv6 flags")
                if (fields[5] != name) continue
                total++
                if (flags and 0x40 != 0) tentative++
                if (flags and 0x08 != 0) failed++
                if (flags and 0x20 != 0) deprecated++
                if (fields[0].equals(expected, true)) advertisedState = when {
                    flags and 0x08 != 0 -> "dad_failed"
                    flags and 0x40 != 0 -> "tentative"
                    flags and 0x20 != 0 -> "deprecated"
                    else -> "ready"
                }
            }
            return "ipv6Addresses=$total tentative=$tentative dadFailed=$failed deprecated=$deprecated advertisedState=$advertisedState"
        }

        internal fun routeSummary(text: String, name: String): String {
            var total = 0; var linkLocal = 0; var defaults = 0; var rejected = 0
            for (row in text.lineSequence().filter { it.isNotBlank() }) {
                val fields = row.trim().split(Regex("\\s+"))
                if (fields.size != 10 || !HEX_ADDRESS.matches(fields[0])) throw IOException("Invalid IPv6 route row")
                val prefix = fields[1].toIntOrNull(16)?.takeIf { it in 0..128 } ?: throw IOException("Invalid prefix")
                val flags = fields[8].toLongOrNull(16) ?: throw IOException("Invalid route flags")
                if (fields[9] != name) continue
                total++
                if (prefix == 0) defaults++
                if (flags and 0x200L != 0L) rejected++
                val first = fields[0].substring(0, 4).toInt(16)
                // Count link-local destinations; this is not a policy-routing lookup or reachability proof.
                if (prefix >= 10 && first and 0xffc0 == 0xfe80 && flags and 1L != 0L && flags and 0x200L == 0L) linkLocal++
            }
            return "ipv6Routes=$total linkLocalUpRoutes=$linkLocal defaultRoutes=$defaults rejectedRoutes=$rejected routeScope=proc_all_tables"
        }

        internal fun neighborSummary(text: String, name: String): String {
            val counts = STATES.associateWith { 0 }.toMutableMap()
            var total = 0; var unknown = 0
            for (row in text.lineSequence().filter { it.isNotBlank() }) {
                val fields = row.trim().split(Regex("\\s+"))
                val dev = fields.indexOf("dev")
                if (dev >= 0 && fields.getOrNull(dev + 1) != name) continue
                // ip output is already filtered by interface; some versions omit "dev".
                if (!fields[0].contains(':')) throw IOException("Invalid IPv6 neighbor row")
                total++
                val state = fields.lastOrNull { it in STATES }
                if (state == null) unknown++ else counts[state] = counts.getValue(state) + 1
            }
            return "ipv6Neighbors=$total unknown=$unknown " + counts.entries.joinToString(" ") { "${it.key.lowercase()}=${it.value}" }
        }

        internal fun tcpSummary(text: String, address: ByteArray, port: Int?): String {
            if (port == null) return "tcp6Listener=unknown"
            val expected = address.toList().chunked(4).flatMap { it.reversed() }
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            var listening = 0; var synReceived = 0; var established = 0; var otherAddressRows = 0
            for (row in text.lineSequence().filter { it.isNotBlank() && !it.trim().startsWith("sl") }) {
                val fields = row.trim().split(Regex("\\s+"))
                if (fields.size < 4) throw IOException("Invalid TCP row")
                val local = fields[1].split(':')
                if (local.size != 2 || !HEX_ADDRESS.matches(local[0])) throw IOException("Invalid TCP address")
                if (local[1].toIntOrNull(16) != port) continue
                if (!local[0].equals(expected, true)) { otherAddressRows++; continue }
                when (fields[3]) { "0A" -> listening++; "03" -> synReceived++; "01" -> established++ }
            }
            return "tcp6 port=$port listening=$listening synReceived=$synReceived established=$established " +
                "socketScope=advertised_address otherLocalAddressRowsAtPort=$otherAddressRows otherRowsOwner=unverified"
        }
    }
}

/** A read-only ip query, no shell, root, packet probes or network configuration changes. */
private fun readUsbNeighbors(name: String): String {
    require(Regex("[A-Za-z0-9_.-]{1,15}").matches(name))
    if (Build.VERSION.SDK_INT < 26) throw IOException("Neighbor query not available")
    val process = ProcessBuilder("/system/bin/ip", "-6", "neigh", "show", "dev", name).redirectErrorStream(true).start()
    try {
        if (!process.waitFor(250, TimeUnit.MILLISECONDS)) throw IOException("Neighbor query timed out")
        if (process.exitValue() != 0) throw IOException("Neighbor query unavailable")
        val bytes = ByteArray(16 * 1024 + 1)
        var count = 0
        while (count < bytes.size) {
            val received = process.inputStream.read(bytes, count, bytes.size - count)
            if (received < 0) break
            count += received
        }
        if (count == bytes.size) throw IOException("Neighbor output too large")
        return String(bytes, 0, count, Charsets.UTF_8)
    } finally {
        process.destroy()
        process.inputStream.close()
        process.outputStream.close()
        process.errorStream.close()
    }
}

/** Samples on a dedicated worker, so protocol callbacks never wait for procfs or ip. */
internal class SystemUsbStartupDiagnostics(
    private val sample: () -> String,
    private val log: (String) -> Unit,
    private val intervalMillis: Long = 5_000,
    private val nowNs: () -> Long = System::nanoTime,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val wake = LinkedBlockingQueue<String>(8)
    private val startedNs = nowNs()
    private var firstStartNs: Long? = null
    private var firstTcpMs: Long? = null
    private var startRequests = 0
    private var tcpAccepted = 0
    private var active = false
    @Volatile private var cached = ""
    @Volatile private var sampledNs: Long? = null
    private val worker = Thread(::observe, "diplay-usb-network-diagnostics").apply { isDaemon = true }

    init { require(intervalMillis > 0) }
    @Synchronized fun start() { if (!closed.get() && worker.state == Thread.State.NEW) worker.start() }
    @Synchronized fun startSessionSent() {
        if (closed.get()) return
        startRequests++
        if (firstStartNs == null) firstStartNs = nowNs()
        emit(summary("start_session")); wake.offer("start_session")
    }
    @Synchronized fun tcpAccepted(internal: Boolean = false) {
        if (closed.get() || internal) return
        tcpAccepted++
        if (firstTcpMs == null) firstStartNs?.let { firstTcpMs = millisSince(it) }
        emit(summary("tcp_accepted")); wake.offer("tcp_accepted")
    }
    @Synchronized fun sessionActive() { active = true; finish("session_active") }
    @Synchronized fun summary(event: String): String = "system USB startup event=$event elapsedMs=${millisSince(startedNs)} " +
        "startRequests=$startRequests tcpAccepted=$tcpAccepted sessionActive=$active " +
        "startRequestAgeMs=${firstStartNs?.let(::millisSince) ?: "none"} firstTcpAfterStartMs=${firstTcpMs ?: "none"} " +
        "waitingFor=${when { active -> "none"; tcpAccepted > 0 -> "AirPlay_protocol"; startRequests > 0 -> "AirPlay_TCP"; else -> "iAP2_start_request" }}"

    @Synchronized fun finish(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        worker.interrupt()
        emit(summary(reason) + " observation=ended")
        emitSnapshot(cached, "cached=true sampleAgeMs=${sampledNs?.let(::millisSince) ?: "none"}")
    }
    override fun close() = finish("closed")
    private fun millisSince(since: Long) = (nowNs() - since).coerceAtLeast(0) / 1_000_000
    private fun observe() {
        try {
            var event = "attached"
            while (!closed.get()) {
                val snapshot = try { sample() } catch (error: Exception) { "sampling=unavailable failureClass=${error.javaClass.simpleName}" }
                synchronized(this) {
                    if (closed.get()) return
                    cached = snapshot; sampledNs = nowNs()
                    emit(summary(event)); emitSnapshot(snapshot, "cached=false")
                }
                event = wake.poll(intervalMillis, TimeUnit.MILLISECONDS) ?: "periodic"
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
    private fun emitSnapshot(snapshot: String, flags: String) {
        snapshot.lineSequence().filter { it.isNotBlank() }.take(16).forEach { emit("system USB snapshot $flags $it") }
    }
    private fun emit(message: String) { try { log(message) } catch (_: RuntimeException) { /* Diagnostics cannot fail connection. */ } }
}

/** Preserve normal session callbacks while recording only TCP events belonging to this USB listener. */
internal fun systemUsbStartupListener(
    owner: AirPlayListenerIdentity,
    startup: SystemUsbFallback,
    diagnostics: SystemUsbStartupDiagnostics,
    delegate: AirPlaySessionListener,
): AirPlaySessionListener = object : AirPlaySessionListener by delegate {
    override fun onTcpAccepted(event: AirPlayTcpAccepted) {
        if (event.listener !== owner) return
        diagnostics.tcpAccepted(event.internal)
        delegate.onTcpAccepted(event)
    }

    override fun onSessionActive(session: AirPlaySession) {
        if (startup.sessionEstablished()) {
            diagnostics.sessionActive()
            delegate.onSessionActive(session)
        } else session.close()
    }

    override fun onSessionEnded(session: AirPlaySession) {
        diagnostics.finish("session_ended")
        if (startup.hasEstablishedSession()) delegate.onSessionEnded(session)
    }

    override fun onTransportError(message: String) {
        diagnostics.finish("transport_error")
        delegate.onTransportError(message)
    }
}
