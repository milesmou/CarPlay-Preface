package com.shilapi.xcertplay.transport

/** Counts header categories only. Never retains frames, addresses, ports or application data. */
internal class NcmFrameTraffic {
    private var ipv4 = 0L
    private var ipv6 = 0L
    private var arp = 0L
    private var vlan = 0L
    private var other = 0L
    private var short = 0L
    private var malformed = 0L
    private var extensions = 0L
    private var fragments = 0L
    private var udp = 0L
    private var dhcp4 = 0L
    private var dhcpMalformed = 0L
    private val dhcpMessages = LongArray(9)
    private var mdns = 0L
    private var syn7000 = 0L
    private var icmp6 = 0L
    private var ns = 0L
    private var na = 0L
    private var echo = 0L
    private val otherTypes = linkedSetOf<Int>()

    fun receive(frame: ByteArray) {
        if (frame.size < 14) { short++; return }
        var type = word(frame, 12)
        var offset = 14
        if (type == 0x8100 || type == 0x88a8) {
            vlan++
            repeat(2) {
                if (type == 0x8100 || type == 0x88a8) {
                    if (frame.size < offset + 4) { short++; return }
                    type = word(frame, offset + 2)
                    offset += 4
                }
            }
        }
        when (type) {
            0x0806 -> arp++
            0x0800 -> {
                ipv4++
                if (frame.size < offset + 20 || byte(frame, offset) shr 4 != 4) { malformed++; return }
                val header = (byte(frame, offset) and 15) * 4
                val length = word(frame, offset + 2)
                if (header < 20 || length < header || offset + length > frame.size) { malformed++; return }
                if (word(frame, offset + 6) and 0x3fff != 0) { fragments++; return }
                transport(frame, offset + header, offset + length, byte(frame, offset + 9), false)
            }
            0x86dd -> {
                ipv6++
                if (frame.size < offset + 40 || byte(frame, offset) shr 4 != 6) { malformed++; return }
                val length = word(frame, offset + 4)
                if (length == 0 || offset + 40 + length > frame.size) { malformed++; return }
                transport(frame, offset + 40, offset + 40 + length, byte(frame, offset + 6), true)
            }
            else -> { other++; if (otherTypes.size < 4) otherTypes.add(type) }
        }
    }

    private fun transport(frame: ByteArray, offset: Int, end: Int, next: Int, v6: Boolean) {
        when (next) {
            17 -> {
                if (end - offset < 8) { malformed++; return }
                val size = word(frame, offset + 4)
                if (size < 8 || offset + size > end) { malformed++; return }
                udp++
                val source = word(frame, offset)
                val destination = word(frame, offset + 2)
                if (source == 5353 || destination == 5353) mdns++
                if (!v6 && (source == 67 && destination == 68 || source == 68 && destination == 67)) {
                    dhcp4++
                    dhcpMessage(frame, offset + 8, offset + size)
                }
            }
            6 -> {
                if (end - offset < 20) { malformed++; return }
                val header = (byte(frame, offset + 12) shr 4) * 4
                if (header < 20 || offset + header > end) { malformed++; return }
                val flags = byte(frame, offset + 13)
                if (word(frame, offset + 2) == 7000 && flags and 2 != 0 && flags and 16 == 0) syn7000++
            }
            58 -> if (v6) {
                if (end - offset < 4) { malformed++; return }
                icmp6++
                when (byte(frame, offset)) {
                    135 -> ns++
                    136 -> na++
                    128, 129 -> echo++
                }
            }
            0, 43, 44, 50, 51, 60 -> if (v6) extensions++ // No extension/fragment reassembly in this diagnostic.
        }
    }

    fun summary(): String = "ethernetHeaders ipv4=$ipv4 ipv6=$ipv6 arp=$arp vlan=$vlan other=$other short=$short " +
        "otherTypes=${otherTypes.joinToString(",") { "0x${it.toString(16).padStart(4, '0')}" }.ifEmpty { "none" }}\n" +
        "protocolHeaders malformed=$malformed ipv6Extensions=$extensions fragments=$fragments udp=$udp dhcp4=$dhcp4 " +
        "mdns=$mdns synTo7000=$syn7000 icmp6=$icmp6 ns=$ns na=$na echo=$echo headerOnly=true\n" +
        "dhcp4Messages discover=${dhcpMessages[1]} offer=${dhcpMessages[2]} request=${dhcpMessages[3]} " +
        "ack=${dhcpMessages[5]} nak=${dhcpMessages[6]} other=${dhcpMessages[0] + dhcpMessages[4] + dhcpMessages[7] + dhcpMessages[8]} " +
        "malformed=$dhcpMalformed contentNotRetained=true"

    /** Only option 53 is counted; client IDs, names, requested addresses and all other options are discarded. */
    private fun dhcpMessage(frame: ByteArray, offset: Int, end: Int) {
        if (end - offset < 240 || byte(frame, offset) !in 1..2 ||
            byte(frame, offset + 236) != 99 || byte(frame, offset + 237) != 130 ||
            byte(frame, offset + 238) != 83 || byte(frame, offset + 239) != 99) { dhcpMalformed++; return }
        var cursor = offset + 240
        var message: Int? = null
        var ended = false
        while (cursor < end) {
            val kind = byte(frame, cursor++)
            if (kind == 255) { ended = true; break }
            if (kind == 0) continue
            if (cursor >= end) { dhcpMalformed++; return }
            val size = byte(frame, cursor++)
            if (cursor + size > end) { dhcpMalformed++; return }
            if (kind == 53) {
                if (size != 1 || message != null) { dhcpMalformed++; return }
                message = byte(frame, cursor)
            }
            cursor += size
        }
        if (!ended || message == null) { dhcpMalformed++; return }
        dhcpMessages[message.takeIf { it in 1..8 } ?: 0]++
    }

    private fun byte(frame: ByteArray, offset: Int) = frame[offset].toInt() and 255
    private fun word(frame: ByteArray, offset: Int) = (byte(frame, offset) shl 8) or byte(frame, offset + 1)
}
