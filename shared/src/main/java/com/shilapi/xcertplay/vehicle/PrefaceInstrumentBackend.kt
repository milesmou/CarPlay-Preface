package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.lang.reflect.Method

/**
 * A fixed NAV F3 writer using the installed firmware's encoder and real CarPropertyManager.
 * No SDK NaviInteraction singleton, presentation switch, system-property write or power request.
 */
internal class PrefaceInstrumentCarBackend(
    private val context: Context,
    private val resolve: (String) -> Class<*> = { Class.forName(it) },
    private val diagnostic: (String) -> Unit = {},
    private val currentSession: () -> Boolean = { true },
) : PrefaceInstrumentBackend {
    private val main = Handler(Looper.getMainLooper())
    private val lifecycle = Any()
    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile private var connectionFailure: Throwable? = null
    @Volatile private var car: Any? = null
    private lateinit var carType: Class<*>
    private lateinit var propertyType: Class<*>
    private lateinit var encoder: Method
    private lateinit var convertTurn: Method
    private lateinit var getProperty: Method
    private var properties: Any? = null
    private var lastNativeState: String? = null

    override fun connect() {
        carType = resolve("android.car.Car")
        propertyType = resolve("android.car.hardware.property.CarPropertyManager")
        val util = resolve("com.ecarx.xui.adaptapi.dim.impl.util.ECarXDIMUtil")
        val int = Int::class.javaPrimitiveType!!
        val float = Float::class.javaPrimitiveType!!
        val bool = Boolean::class.javaPrimitiveType!!
        encoder = util.getMethod("convertNAVF3", bool, bool, int, int, float, int, float, int, String::class.java)
        convertTurn = util.getMethod("convertNAVTurn2IPK", int)
        getProperty = resolve("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
        propertyType.getMethod("setProperty", Class::class.java, int, int, Any::class.java)
        main.post {
            synchronized(lifecycle) {
                if (closed) return@synchronized
                try {
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { connected = true }
                        override fun onServiceDisconnected(name: ComponentName?) { connected = false }
                    }
                    car = carType.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                        .invoke(null, context, connection) ?: error("car-api-unavailable")
                    carType.getMethod("connect").invoke(car)
                } catch (error: Exception) { connectionFailure = error }
                catch (error: LinkageError) { connectionFailure = error }
            }
        }
    }

    override fun ready(): Boolean {
        connectionFailure?.let { throw IllegalStateException("car-service-connect-failed", it) }
        val api = car ?: return false
        if (closed || !connected || carType.getMethod("isConnected").invoke(api) != true) return false
        if (properties == null) {
            val manager = carType.getMethod("getCarManager", String::class.java).invoke(api, "property")
                ?: error("car-property-manager-unavailable")
            val configs = propertyType.getMethod("getPropertyList").invoke(manager) as? List<*>
                ?: error("car-property-list-unavailable")
            val config = configs.filterNotNull().firstOrNull {
                it.javaClass.getMethod("getPropertyId").invoke(it) == INSTRUMENT_PROPERTY
            } ?: error("instrument-property-unavailable-or-permission-denied")
            check((config.javaClass.getMethod("getAccess").invoke(config) as Number).toInt() and 2 != 0) {
                "instrument-property-not-writable"
            }
            check(config.javaClass.getMethod("getPropertyType").invoke(config) == ByteArray::class.java) {
                "instrument-property-type-mismatch"
            }
            properties = manager
            diagnostic("instrument navigation car-service connected; fixed byte-array property writable")
        }
        return true
    }

    override fun available(): Boolean {
        // Only read firmware state. A reflection/permission failure propagates and stops this session.
        fun state(key: String): String = when (getProperty.invoke(null, key, "unknown")) {
            "false", "0" -> "idle"
            "true", "1" -> "active"
            else -> "unknown"
        }
        val navigation = state("sys.bicv.navi.status")
        val phone = state("sys.bicv.phone.status")
        val summary = "nativeNavigation=$navigation nativePhone=$phone"
        if (lastNativeState != summary) {
            diagnostic("instrument navigation $summary")
            lastNativeState = summary
        }
        return navigation == "idle" && phone == "idle"
    }

    override fun publish(guidance: PrefaceInstrumentGuidance) {
        val ipk = (convertTurn.invoke(null, guidance.turnId) as Number).toInt()
        check(ipk in 2..9) { "unconfirmed-instrument-arrow" }
        val destination = distance(guidance.remainingMeters, 6553.5f)
        val turn = distance(guidance.distanceMeters.toLong(), 1638.3f)
        val minutes = (guidance.remainingSeconds / 60 + if (guidance.remainingSeconds % 60 >= 30) 1 else 0)
            .coerceIn(1, 8191).toInt()
        val data = encoder.invoke(null, true, false, ipk, destination.first, destination.second,
            turn.first, turn.second, minutes, guidance.road) as? ByteArray
            ?: error("instrument-encoder-returned-null")
        write(data, requireCurrentSession = true)
    }

    override fun clear() {
        // The same encoder's routing=false form clears navigation only, never music/phone frames.
        write(encoder.invoke(null, false, true, 0, 0, 0f, 0, 0f, 0, "") as ByteArray)
    }

    private fun write(data: ByteArray, requireCurrentSession: Boolean = false) {
        check(!closed && ready() && available()) { "instrument-output-no-longer-available" }
        check(data.isNotEmpty()) { "instrument-encoder-returned-empty" }
        check(!requireCurrentSession || currentSession()) { "instrument-session-ended" }
        propertyType.getMethod("setProperty", Class::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Any::class.java)
            .invoke(properties, ByteArray::class.java, INSTRUMENT_PROPERTY, 0, data)
    }

    override fun close() {
        synchronized(lifecycle) { closed = true; connected = false }
        main.post {
            synchronized(lifecycle) {
                val api = car
                car = null
                properties = null
                if (api != null) runCatching { carType.getMethod("disconnect").invoke(api) }
            }
        }
    }

    private fun distance(meters: Long, max: Float): Pair<Int, Float> =
        if (meters < 1000) 1 to meters.coerceAtLeast(0).toFloat().coerceAtMost(max)
        else 0 to (if (meters < 10000) ((meters + 50) / 100).toFloat() / 10 else (meters / 1000).toFloat()).coerceAtMost(max)

    private companion object { const val INSTRUMENT_PROPERTY = 762314830 }
}
