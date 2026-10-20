package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

data class CarPlayCallCard(val phase: Phase, val name: String, val activeSinceMillis: Long? = null) {
    enum class Phase { RINGING, DIALING, ACTIVE }
}

/** Opaque call identity: phone commands never need to expose a number, name or UUID. */
data class CarPlayCallControl(val token: Any, val phase: CarPlayCallCard.Phase)

/**
 * The iPhone's calls from iAP2 CallStateUpdate (0x4155): remote id (0), display name (1), status (2)
 * and call UUID (4). Status: 0 disconnected, 1 sending, 2 ringing, 3 connecting, 4 active, 5 held,
 * 6 disconnecting. Several calls can exist at once (call waiting); the card shows a ringing call
 * first, then a connected (active or held) one, then an outgoing one.
 */
class CarPlayCallState(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Call(val status: Int, val name: String, val activeSince: Long?, val token: Any)

    private val calls = LinkedHashMap<String, Call>()
    private var last: CarPlayCallCard? = null

    /** Returns true when the card changed; read it with [current]. */
    fun accept(frame: Iap2Frame): Boolean {
        if (frame.messageId != CALL_STATE_UPDATE) return false
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return false
        val status = runCatching { body.optionalU8(STATUS) }.getOrNull() ?: return false
        val remote = runCatching { body.optionalString(REMOTE_ID) }.getOrNull()?.trim().orEmpty()
        val display = runCatching { body.optionalString(DISPLAY_NAME) }.getOrNull()?.trim().orEmpty()
        val id = runCatching { body.optionalString(CALL_UUID) }.getOrNull()?.takeIf { it.isNotBlank() } ?: remote
        if (id.isBlank()) return false
        if (status == DISCONNECTED || status == DISCONNECTING) {
            calls.remove(id)
        } else {
            val previous = calls[id]
            val connected = status == ACTIVE || status == HELD
            calls[id] = Call(
                status = status,
                name = display.ifEmpty { remote }.ifEmpty { previous?.name.orEmpty() },
                activeSince = previous?.activeSince ?: if (connected) clock() else null,
                token = previous?.token ?: Any(),
            )
        }
        val next = card()
        if (next == last) return false
        last = next
        return true
    }

    fun current(): CarPlayCallCard? = last

    /** Untargeted Hook/Drop is ambiguous during call waiting or multiple calls. */
    fun control(): CarPlayCallControl? {
        val call = calls.values.singleOrNull() ?: return null
        val phase = last?.phase ?: return null
        return CarPlayCallControl(call.token, phase)
    }

    fun clear() {
        calls.clear()
        last = null
    }

    private fun card(): CarPlayCallCard? {
        calls.values.firstOrNull { it.status == RINGING }?.let { return CarPlayCallCard(CarPlayCallCard.Phase.RINGING, it.name) }
        (calls.values.firstOrNull { it.status == ACTIVE } ?: calls.values.firstOrNull { it.status == HELD })?.let {
            return CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, it.name, it.activeSince)
        }
        calls.values.firstOrNull { it.status == SENDING || it.status == CONNECTING }?.let {
            return CarPlayCallCard(CarPlayCallCard.Phase.DIALING, it.name)
        }
        return null
    }

    companion object {
        const val CALL_STATE_UPDATE = 0x4155
        private const val REMOTE_ID = 0
        private const val DISPLAY_NAME = 1
        private const val STATUS = 2
        private const val CALL_UUID = 4
        private const val DISCONNECTED = 0
        private const val SENDING = 1
        private const val RINGING = 2
        private const val CONNECTING = 3
        private const val ACTIVE = 4
        private const val HELD = 5
        private const val DISCONNECTING = 6

    }
}
