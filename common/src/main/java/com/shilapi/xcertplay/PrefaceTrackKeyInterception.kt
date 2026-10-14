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
    private val held = mutableSetOf<Int>()

    fun update(eligible: Boolean, volumeKeys: Boolean = false) {
        val withVolume = eligible && volumeKeys && !volumeFailed
        if ((eligible == requested && withVolume == requestedVolume) || (eligible && failed)) return
        if (requested) close()
        if (eligible) {
            requested = true
            requestedVolume = withVolume
            val owner = ++generation
            try {
                backend.request({ down, key ->
                    if (requested && owner == generation) {
                        if (key == 301 || key == 302) {
                            if (requestedVolume) volume(down, key)
                            return@request
                        }
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
                            if (held.add(index)) send(index)
                        } else held.remove(index)
                    }
                }, withVolume)
            } catch (error: Exception) {
                // A failed/partial request must also be released. Broadcast handling remains available.
                close()
                if (withVolume) {
                    volumeFailed = true
                    Log.w("DiPlay-MediaKeys", "Volume interception unavailable; retaining vehicle volume and retrying media/voice keys", error)
                    update(eligible, false)
                    return
                }
                failed = true
                Log.w("DiPlay-MediaKeys", "Preface track interception unavailable; using media broadcasts", error)
            }
        } else close()
    }

    fun close() {
        if (!requested) return
        requested = false
        requestedVolume = false
        generation++
        held.clear()
        runCatching { backend.release() }
            .onFailure { Log.w("DiPlay-MediaKeys", "Preface track interception release failed", it) }
    }
}

/**
 * Firmware 327's HiCar support SDK requests 85/87/88/298 through the customized input Binder.
 * Resolve its real interfaces and transaction constant at runtime; no copied vendor SDK or
 * hardcoded IInputManager transaction numbers. Missing APIs/permissions fail to the broadcast path.
 */
internal class PrefaceInputInterceptionBackend(
    private val deliver: (() -> Unit) -> Unit,
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
        val keys = intArrayOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 298) +
            if (volumeKeys) intArrayOf(301, 302) else intArrayOf()
        inputType.getMethod("requestKeysInterception", IntArray::class.java, observerType)
            .invoke(manager, keys, observer)
        Log.i("DiPlay-MediaKeys", "Preface interception requested (${keys.joinToString()}); vehicle validation pending")
    }

    override fun release() {
        val action = release ?: return
        action()
        release = null
        Log.i("DiPlay-MediaKeys", "Preface track interception released")
    }
}
