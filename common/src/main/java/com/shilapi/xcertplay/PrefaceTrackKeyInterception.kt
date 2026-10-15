package com.shilapi.xcertplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/** Owns media/voice keys while CarPlay owns media. All lifecycle calls run on the main thread. */
internal class PrefaceTrackKeyInterception(
    private val backend: Backend,
    private val siri: () -> Unit = {},
    private val volume: (Boolean, Int) -> Unit = { _, _ -> },
    private val diagnostic: (String) -> Unit = {},
    private val handleKey: ((Boolean, Int) -> Boolean)? = null,
    private val releaseKeys: () -> Unit = {},
    private val send: (Int) -> Unit,
) {
    internal interface Backend {
        fun request(callback: (Boolean, Int) -> Unit)
        fun request(callback: (Boolean, Int) -> Unit, volumeKeys: Boolean) = request(callback)
        fun release()
    }

    private var requested = false
    private var failed = false
    private var volumeFailed = false
    private var requestedVolume = false
    private var generation = 0L
    private var accepted = false
    private var receivedEvents = 0L
    private val held = mutableSetOf<Int>()

    data class Status(val requested: Boolean, val accepted: Boolean, val receivedEvents: Long,
        val unavailable: Boolean, val exclusiveConfirmed: Boolean = false)
    fun status() = Status(requested, accepted, receivedEvents, failed)
    fun report(): String = "Track interception requested=$requested requestAccepted=$accepted receivedEvents=$receivedEvents " +
        "exclusiveHandling=unconfirmed volumeRequested=$requestedVolume unavailable=$failed volumeUnavailable=$volumeFailed"

    fun update(eligible: Boolean, volumeKeys: Boolean = false) {
        val withVolume = eligible && volumeKeys && !volumeFailed
        if ((eligible == requested && withVolume == requestedVolume) || (eligible && failed)) return
        if (requested) close()
        if (eligible) {
            requested = true
            requestedVolume = withVolume
            accepted = false
            receivedEvents = 0
            val owner = ++generation
            try {
                backend.request({ down, key ->
                    if (requested && owner == generation) {
                        if (key == 301 || key == 302) {
                            diagnostic("Wheel volume observed key=$key down=$down adjustment=${if (requestedVolume) "application" else "vehicle"}")
                            if (requestedVolume) volume(down, key)
                            return@request
                        }
                        receivedEvents++
                        if (handleKey?.invoke(down, key) == true) return@request
                        if (key == 298) {
                            // Release triggers Siri once; a held/repeated key cannot toggle it repeatedly.
                            if (down) held.add(298) else if (held.remove(298)) siri()
                            return@request
                        }
                        // HiCar's listener handles both standard and E02 physical track codes.
                        val index = when (key) {
                            KeyEvent.KEYCODE_MEDIA_NEXT, 305 -> CarPlayMediaButton.NEXT
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS, 304 -> CarPlayMediaButton.PREVIOUS
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> CarPlayMediaButton.PLAY_PAUSE
                            else -> return@request
                        }
                        if (down) {
                            if (held.add(index)) {
                                diagnostic("Wheel interception received key=$key down=true")
                                send(index)
                            }
                        } else held.remove(index)
                    }
                }, withVolume)
                accepted = true
            } catch (error: Exception) {
                // A failed/partial request must also be released. Broadcast handling remains available.
                close()
                if (withVolume) {
                    volumeFailed = true
                    Log.w("DiPlay-MediaKeys", "Volume interception unavailable; retaining vehicle volume and retrying media/voice keys", error)
                    diagnostic("Wheel volume interception unavailable failureClass=${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}; retrying media keys")
                    update(eligible, false)
                    return
                }
                failed = true
                Log.w("DiPlay-MediaKeys", "Preface track interception unavailable; using media broadcasts", error)
                diagnostic("Wheel interception unavailable failureClass=${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}; using media broadcasts")
            }
        } else close()
    }

    fun close() {
        if (!requested) return
        requested = false
        accepted = false
        requestedVolume = false
        generation++
        held.clear()
        releaseKeys()
        diagnostic("Wheel interception release requested")
        runCatching { backend.release() }
            .onSuccess { diagnostic("Wheel interception released") }
            .onFailure {
                Log.w("DiPlay-MediaKeys", "Preface track interception release failed", it)
                diagnostic("Wheel interception release failed failureClass=${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}")
            }
    }
}

/**
 * Firmware 327's HiCar support SDK requests 85/87/88/298 through the customized input Binder.
 * Resolve its real interfaces and transaction constant at runtime; no copied vendor SDK or
 * hardcoded IInputManager transaction numbers. Missing APIs/permissions fail to the broadcast path.
 */
internal class PrefaceInputInterceptionBackend(
    private val deliver: (() -> Unit) -> Unit,
    private val diagnostic: (String) -> Unit = {},
    private val additionalKeys: () -> IntArray = { intArrayOf() },
) : PrefaceTrackKeyInterception.Backend {
    private var release: (() -> Unit)? = null

    override fun request(callback: (Boolean, Int) -> Unit) {
        request(callback, false)
    }

    override fun request(callback: (Boolean, Int) -> Unit, volumeKeys: Boolean) {
        val observerType = Class.forName("android.hardware.input.IKeyEventObserver")
        val observerStub = Class.forName("android.hardware.input.IKeyEventObserver\$Stub")
        val callbackCode = observerStub.getDeclaredField("TRANSACTION_onKeyEvent").apply {
            isAccessible = true
        }.getInt(null)
        val descriptor = observerType.name
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.INTERFACE_TRANSACTION) {
                    reply?.writeString(descriptor)
                    return true
                }
                if (code != callbackCode) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(descriptor)
                val down = data.readInt() != 0
                val key = data.readInt()
                deliver { callback(down, key) }
                reply?.writeNoException()
                return true
            }
        }
        val observer = observerStub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("System key observer interface unavailable")
        val service = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "input") as? IBinder
            ?: error("System input service unavailable")
        val inputType = Class.forName("android.hardware.input.IInputManager")
        val manager = Class.forName("android.hardware.input.IInputManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, service)
            ?: error("System input interface unavailable")
        val abandon = inputType.getMethod("abandonKeysInterception", observerType)
        release = { abandon.invoke(manager, observer) }
        val standardKeys = intArrayOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 298) +
            if (volumeKeys) intArrayOf(301, 302) else intArrayOf()
        // The API registers an observer; it does not promise exclusive handling. Physical 304/305
        // may be followed by the OEM's synthetic 87/88 broadcast, which must share deduplication.
        val request = inputType.getMethod("requestKeysInterception", IntArray::class.java, observerType)
        val keys = requestPrefaceTrackKeys(standardKeys, additionalKeys = additionalKeys(),
            request = { request.invoke(manager, it, observer) },
            abandon = { abandon.invoke(manager, observer) }, diagnostic = diagnostic)
        Log.i("DiPlay-MediaKeys", "Preface interception requested (${keys.joinToString()}); vehicle validation pending")
        diagnostic("Wheel interception requested keys=${keys.joinToString(",")} receiptNotYetConfirmed=true exclusiveHandling=unconfirmed")
    }

    override fun release() {
        val action = release ?: return
        action()
        release = null
        Log.i("DiPlay-MediaKeys", "Preface track interception released")
    }
}

/** A rejected expanded request is released before retrying the reference SDK's key set. */
internal fun requestPrefaceTrackKeys(standardKeys: IntArray, request: (IntArray) -> Unit,
    abandon: () -> Unit, diagnostic: (String) -> Unit, additionalKeys: IntArray = intArrayOf()): IntArray {
    val expanded = (standardKeys + intArrayOf(304, 305) + additionalKeys).distinct().toIntArray()
    try {
        request(expanded)
        return expanded
    } catch (error: java.lang.reflect.InvocationTargetException) {
        abandon()
        diagnostic("Wheel physical interception request rejected failureClass=${error.cause?.javaClass?.simpleName}; retrying standard keys")
        request(standardKeys)
        return standardKeys
    }
}
