package com.shilapi.xcertplay.transport

import java.security.SecureRandom

/** Diagnostic ICMPv6 only: no sockets, TCP/IP stack, VPN, or packet payload logging. */
internal class NcmFrameProbe(
    private val hostMac: ByteArray,
    private val hostAddress: ByteArray,
    private val send: (ByteArray) -> Boolean,
    private val nonce: ByteArray = ByteArray(18).also { SecureRandom().nextBytes(it) },
) {
    init {
        require(unicastMac(hostMac))
        require(linkLocal(hostAddress))
        require(nonce.size == 18)
    }

    var received = 0; private set
    var ipv6 = 0; private set
    var rejected = 0; private set
    var neighborSolicitations = 0; private set
    var tcp = 0; private set
    var udp = 0; private set
    var unsupported = 0; private set
    var writes = 0; private set
    var acceptedWrites = 0; private set
    var echoReplies = 0; private set
    private var peerAddress: ByteArray? = null
    private var peerMac: ByteArray? = null
    private val pending = mutableMapOf<Int, ByteArray>()
    private var probes = 0

    fun summary(): String = "rxFrames=$received ipv6=$ipv6 rejected=$rejected ns=$neighborSolicitations " +
        "tcp=$tcp udp=$udp unsupported=$unsupported txAttempts=$writes txAccepted=$acceptedWrites " +
        "echoReplies=$echoReplies bidirectionalConfirmed=${echoReplies > 0}"

    /** At most three probes; multicast starts OUT testing even before any peer has been seen. */
    fun probe() {
        if (probes >= 3) return
        val sequence = probes++
        val destination = peerAddress?.copyOf() ?: ALL_NODES
        val body = ByteArray(24)
        body[0] = 128.toByte()
        nonce.copyInto(body, 4, 0, 2)
        put16(body, 6, sequence)
        nonce.copyInto(body, 8, 2)
        if (transmit(destination, peerMac ?: ALL_NODES_MAC, body, 64)) pending[sequence] = destination
    }

    fun receive(frame: ByteArray) {
        received++
        val ethernet = EthernetIpv6Codec.parseIpv6(frame) ?: run { unsupported++; return }
        val packet = ethernet.ipv6
        if (packet.size < 40 || u8(packet, 0) shr 4 != 6) { rejected++; return }
        val length = u16(packet, 4)
        if (length == 0 || 40 + length > packet.size || !unicastMac(ethernet.sourceMac) ||
            ethernet.sourceMac.contentEquals(hostMac)) { rejected++; return }
        val source = packet.copyOfRange(8, 24)
        val destination = packet.copyOfRange(24, 40)
        if (source.contentEquals(hostAddress)) { rejected++; return }
        ipv6++
        val next = u8(packet, 6)
        if (next == 6 && length >= 20) tcp++
        if (next == 17 && length >= 8) udp++
        if (next != 58) { if (next != 6 && next != 17) unsupported++; return }
        val body = packet.copyOfRange(40, 40 + length)
        if (body.size < 4 || checksum(source, destination, body) != 0) { rejected++; return }
        when (u8(body, 0)) {
            135 -> {
                val dad = source.all { it == 0.toByte() }
                if (u8(packet, 7) != 255 || u8(body, 1) != 0 || body.size < 24 ||
                    !body.copyOfRange(8, 24).contentEquals(hostAddress) ||
                    !(destination.contentEquals(hostAddress) || destination.contentEquals(solicitedNode(hostAddress))) ||
                    (dad && !destination.contentEquals(solicitedNode(hostAddress))) ||
                    !validOptions(body, dad)) { rejected++; return }
                neighborSolicitations++
                if (!dad && !linkLocal(source)) { rejected++; return }
                if (!dad) learn(source, ethernet.sourceMac)
                val advertisement = ByteArray(32)
                advertisement[0] = 136.toByte()
                advertisement[4] = if (dad) 0x20 else 0x60 // Override; Solicited only for unicast.
                hostAddress.copyInto(advertisement, 8)
                advertisement[24] = 2
                advertisement[25] = 1
                hostMac.copyInto(advertisement, 26)
                transmit(if (dad) ALL_NODES else source,
                    if (dad) ALL_NODES_MAC else ethernet.sourceMac, advertisement, 255)
            }
            129 -> {
                if (u8(body, 1) != 0 || body.size != 24 || !destination.contentEquals(hostAddress) ||
                    !linkLocal(source) || !ethernet.destinationMac.contentEquals(hostMac) ||
                    !body.copyOfRange(4, 6).contentEquals(nonce.copyOfRange(0, 2)) ||
                    !body.copyOfRange(8, 24).contentEquals(nonce.copyOfRange(2, 18))) { rejected++; return }
                val sequence = u16(body, 6)
                val target = pending[sequence]
                if (target == null || !(target.contentEquals(ALL_NODES) || target.contentEquals(source))) {
                    rejected++; return
                }
                pending.remove(sequence)
                echoReplies++
                learn(source, ethernet.sourceMac)
            }
            // A valid ICMPv6 source on this point-to-point USB pipe is a candidate for unicast probes.
            else -> if (linkLocal(source)) learn(source, ethernet.sourceMac)
        }
    }

    private fun learn(address: ByteArray, mac: ByteArray) {
        if (peerAddress == null) { peerAddress = address.copyOf(); peerMac = mac.copyOf() }
    }

    private fun transmit(destination: ByteArray, mac: ByteArray, body: ByteArray, hops: Int): Boolean {
        writes++
        val accepted = send(icmpFrame(hostMac, mac, hostAddress, destination, body, hops))
        if (accepted) acceptedWrites++
        return accepted
    }

    private fun validOptions(body: ByteArray, dad: Boolean): Boolean {
        var offset = 24
        while (offset < body.size) {
            if (offset + 2 > body.size) return false
            val size = u8(body, offset + 1) * 8
            if (size == 0 || offset + size > body.size || (dad && u8(body, offset) == 1)) return false
            if (u8(body, offset) == 1 && size != 8) return false
            offset += size
        }
        return true
    }

    companion object {
        private val ALL_NODES = byteArrayOf(0xff.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        private val ALL_NODES_MAC = byteArrayOf(0x33, 0x33, 0, 0, 0, 1)
        private fun linkLocal(address: ByteArray) = address.size == 16 &&
            u8(address, 0) == 0xfe && u8(address, 1) and 0xc0 == 0x80
        private fun unicastMac(mac: ByteArray) = mac.size == 6 &&
            u8(mac, 0) and 1 == 0 && mac.any { it != 0.toByte() }
        private fun u8(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 255
        private fun u16(bytes: ByteArray, offset: Int) = (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)
        private fun put16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte(); bytes[offset + 1] = value.toByte()
        }
        internal fun solicitedNode(address: ByteArray) = byteArrayOf(
            0xff.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0xff.toByte(), address[13], address[14], address[15])

        internal fun icmpFrame(sourceMac: ByteArray, destinationMac: ByteArray, source: ByteArray,
            destination: ByteArray, payload: ByteArray, hops: Int): ByteArray {
            val packet = ByteArray(40 + payload.size)
            packet[0] = 0x60
            put16(packet, 4, payload.size)
            packet[6] = 58; packet[7] = hops.toByte()
            source.copyInto(packet, 8); destination.copyInto(packet, 24)
            val body = payload.copyOf()
            body[2] = 0; body[3] = 0
            put16(body, 2, checksum(source, destination, body))
            body.copyInto(packet, 40)
            return EthernetIpv6Codec.build(sourceMac, destinationMac, packet)
        }

        private fun checksum(source: ByteArray, destination: ByteArray, payload: ByteArray): Int {
            var sum = payload.size + 58L
            fun add(bytes: ByteArray) {
                for (offset in bytes.indices step 2) sum += (u8(bytes, offset) shl 8) +
                    if (offset + 1 < bytes.size) u8(bytes, offset + 1) else 0
            }
            add(source); add(destination); add(payload)
            while (sum ushr 16 != 0L) sum = (sum and 65535) + (sum ushr 16)
            return sum.inv().toInt() and 65535
        }
    }
}
