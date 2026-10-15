package com.shilapi.xcertplay.transport

/** CDC notifications are evidence of USB link indications, not confirmation of IP/TCP reachability. */
internal class NcmStatusDiagnostics {
    private var notifications = 0L
    private var malformed = 0L
    private var link = "unknown"
    private var upstream: Long? = null
    private var downstream: Long? = null

    @Synchronized fun receive(bytes: ByteArray, length: Int) {
        if (length < 8 || length > bytes.size || (bytes[0].toInt() and 255) != 0xa1) { malformed++; return }
        val size = word(bytes, 6)
        if (size + 8 > length) { malformed++; return }
        notifications++
        when (bytes[1].toInt() and 255) {
            0 -> if (size == 0 && word(bytes, 2) in 0..1) link = if (word(bytes, 2) == 1) "up" else "down"
                else malformed++
            0x2a -> if (size == 8) { downstream = uint(bytes, 8); upstream = uint(bytes, 12) } else malformed++
        }
    }

    @Synchronized fun summary(): String = "usbStatus notifications=$notifications malformed=$malformed " +
        "linkIndication=$link downstreamBps=${downstream ?: "unknown"} upstreamBps=${upstream ?: "unknown"} ipReachability=unconfirmed"
    private fun word(b: ByteArray, p: Int) = (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
    private fun uint(b: ByteArray, p: Int): Long = (0..3).sumOf { (b[p + it].toLong() and 255) shl (8 * it) }
}
