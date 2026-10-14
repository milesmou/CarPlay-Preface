package com.shilapi.xcertplay.network

import android.hardware.usb.UsbDevice
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections

/** Only a NIC whose sysfs USB identity matches this iPhone's NCM function is eligible. */
internal object SystemUsbNetwork {
    data class Link(val name: String, val address: Inet6Address, val mac: ByteArray,
        val identity: Pair<Int, Int>, val interfaceIds: Set<Int>)

    fun find(device: UsbDevice, onDiagnostic: (String) -> Unit = {}): Link? = runCatching {
        val identity = usbIdentity(device.deviceName) ?: return null
        val configuration = IphoneCarPlayConfiguration.find(device) ?: return null
        val function = NcmFunctionDiscovery.find(configuration) ?: return null
        val interfaceIds = setOf(function.control.id, function.data.id)
        // NetworkInterface includes tentative addresses. Do not announce them before DAD completes.
        // Some Android builds restrict procfs; retain discovery there and report the missing evidence.
        val addressFlags = runCatching { readBoundedDiagnosticFile("/proc/net/if_inet6", 64 * 1024) }.getOrNull()
        val links = Collections.list(NetworkInterface.getNetworkInterfaces()).mapNotNull { nic ->
            runCatching {
                val usbInterface = File("/sys/class/net/${nic.name}/device").canonicalFile
                val owned = ownsUsbFunction(usbInterface, identity, interfaceIds)
                val addresses = Collections.list(nic.inetAddresses).filterIsInstance<Inet6Address>()
                    .filter { it.isLinkLocalAddress }
                val readyAddresses = addresses.filter {
                    addressState(addressFlags, nic.name, nic.index, it.address) in setOf("ready", "unavailable")
                }
                val mac = nic.hardwareAddress
                val reason = when {
                    !nic.isUp || nic.isLoopback || nic.index <= 0 -> "interface_not_ready"
                    !owned -> "ownership_not_confirmed"
                    addresses.isEmpty() -> "no_ipv6_link_local"
                    readyAddresses.isEmpty() -> "ipv6_address_not_ready"
                    mac == null || mac.size != 6 || mac.all { it == 0.toByte() } -> "no_usable_mac"
                    else -> "eligible"
                }
                if (owned || nic.name.startsWith("usb") || File(usbInterface.parentFile, "busnum").exists()) {
                    runCatching { onDiagnostic("system USB candidate iface=${nic.name} up=${nic.isUp} " +
                        "ownerMatches=$owned ipv6LinkLocal=${addresses.size} " +
                        "ipv6Ready=${readyAddresses.size} addressFlagsAvailable=${addressFlags != null} decision=$reason") }
                }
                if (reason != "eligible") return@mapNotNull null
                Link(nic.name, Inet6Address.getByAddress(null, readyAddresses.first().address, nic.index), mac!!.copyOf(),
                    identity, interfaceIds)
            }.getOrNull()
        }
        runCatching { onDiagnostic("system USB discovery eligibleCount=${links.size} " +
            "decision=${when (links.size) { 0 -> "no_eligible_interface"; 1 -> "selected"; else -> "ambiguous" }}") }
        links.singleOrNull()
    }.getOrNull()

    internal fun addressState(text: String?, name: String, index: Int, address: ByteArray): String {
        if (text == null) return "unavailable"
        val expected = address.joinToString("") { "%02x".format(it.toInt() and 255) }
        val matches = text.lineSequence().filter { it.isNotBlank() }.map { it.trim().split(Regex("\\s+")) }
            .filter { it.size == 6 && it[5] == name && it[0].equals(expected, true) }.toList()
        if (matches.size != 1) return "absent"
        val row = matches.single()
        if (row[1].toIntOrNull(16) != index) return "absent"
        val flags = row[4].toIntOrNull(16) ?: return "invalid"
        return when {
            flags and 0x08 != 0 -> "dad_failed"
            flags and 0x40 != 0 -> "tentative"
            flags and 0x20 != 0 -> "deprecated"
            else -> "ready"
        }
    }

    internal fun ownsUsbFunction(usbInterface: File, identity: Pair<Int, Int>, interfaceIds: Set<Int>): Boolean =
        runCatching {
            val number = File(usbInterface, "bInterfaceNumber").readText().trim().toIntOrNull(16)
            val parent = usbInterface.parentFile ?: return false
            val bus = File(parent, "busnum").readText().trim().toIntOrNull()
            val dev = File(parent, "devnum").readText().trim().toIntOrNull()
            number in interfaceIds && identity == (bus to dev)
        }.getOrDefault(false)

    internal fun usbIdentity(path: String): Pair<Int, Int>? {
        val match = Regex("^/dev/bus/usb/(\\d+)/(\\d+)$").matchEntire(path) ?: return null
        val bus = match.groupValues[1].toIntOrNull() ?: return null
        val dev = match.groupValues[2].toIntOrNull() ?: return null
        return if (bus > 0 && dev > 0) bus to dev else null
    }
}

/** A failed startup may switch transports once; an established drive never does. */
internal class SystemUsbFallback(
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private var startedNanos: Long? = null
    private var established = false
    private var fallingBack = false

    @Synchronized fun startSessionSent() {
        if (startedNanos == null) startedNanos = clockNanos()
    }

    @Synchronized fun sessionEstablished(): Boolean {
        if (fallingBack) return false
        established = true
        return true
    }

    @Synchronized fun hasEstablishedSession(): Boolean = established

    @Synchronized fun claimFallback(): Boolean {
        val start = startedNanos ?: return false
        if (established || fallingBack || clockNanos() - start < STARTUP_TIMEOUT_MILLIS * 1_000_000L) return false
        fallingBack = true
        return true
    }

    companion object { const val STARTUP_TIMEOUT_MILLIS = 30_000L }
}
