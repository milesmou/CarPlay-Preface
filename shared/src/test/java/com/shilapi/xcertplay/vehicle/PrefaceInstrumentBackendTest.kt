package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrefaceInstrumentBackendTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var currentSession = true
    private val backend get() = PrefaceInstrumentCarBackend(context, resolve = { name ->
        when (name) {
            "android.car.Car" -> Car::class.java
            "android.car.hardware.property.CarPropertyManager" -> Properties::class.java
            "android.os.SystemProperties" -> SystemState::class.java
            "com.ecarx.xui.adaptapi.dim.impl.util.ECarXDIMUtil" -> Encoder::class.java
            else -> error(name)
        }
    }, currentSession = { currentSession })

    @Before fun reset() {
        currentSession = true
        Car.current = null
        Car.properties = Properties()
        SystemState.values.clear()
        SystemState.values["sys.bicv.navi.status"] = "false"
        SystemState.values["sys.bicv.phone.status"] = "false"
        Encoder.values = emptyList()
    }

    private fun connected(): PrefaceInstrumentCarBackend = backend.also {
        it.connect(); shadowOf(Looper.getMainLooper()).idle(); assertTrue(it.ready())
    }

    @Test fun writesOnlyFixedNavigationPropertyUsingFirmwareEncoder() {
        val api = connected()
        api.publish(PrefaceInstrumentGuidance(102, 150, "道路", 12500, 90))
        assertEquals(listOf(true, false, 2, 0, 12f, 1, 150f, 2, "道路"), Encoder.values)
        assertEquals(762314830, Car.properties.ids.single())
        assertArrayEquals(byteArrayOf(3, 42), Car.properties.sent.single())
        api.clear()
        assertEquals(false, Encoder.values[0])
        assertEquals(true, Encoder.values[1])
        api.close(); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Car.current!!.connected)
    }

    @Test fun nativeNavigationPhoneAndUnknownStateRejectAllWritesIncludingClear() {
        val api = connected()
        for ((key, state) in listOf("sys.bicv.navi.status" to "true", "sys.bicv.phone.status" to "1",
            "sys.bicv.phone.status" to "unknown", "sys.bicv.navi.status" to "")) {
            SystemState.values[key] = state
            assertFalse(api.available())
            assertThrows(IllegalStateException::class.java) { api.publish(PrefaceInstrumentGuidance(102, 100, "", 0, 0)) }
            assertThrows(IllegalStateException::class.java) { api.clear() }
            SystemState.values[key] = "false"
        }
        assertTrue(Car.properties.sent.isEmpty())
        api.close()
    }

    @Test fun missingReadOnlyOrWrongTypePropertyDoesNotBecomeReady() {
        for (config in listOf(null, PropertyConfig(access = 1), PropertyConfig(type = Int::class.java))) {
            Car.properties.config = config
            val api = backend
            api.connect(); shadowOf(Looper.getMainLooper()).idle()
            assertThrows(IllegalStateException::class.java) { api.ready() }
            assertTrue(Car.properties.sent.isEmpty())
            api.close(); shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun disconnectAndClosedBackendCannotWrite() {
        val api = connected()
        currentSession = false
        assertThrows(IllegalStateException::class.java) { api.publish(PrefaceInstrumentGuidance(102, 100, "", 0, 0)) }
        assertTrue(Car.properties.sent.isEmpty())
        Car.current!!.connected = false
        assertFalse(api.ready())
        assertThrows(IllegalStateException::class.java) { api.clear() }
        api.close(); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(api.ready())
    }

    @Test fun unavailableSdkFailsBeforeCarConnection() {
        val api = PrefaceInstrumentCarBackend(context, resolve = { throw ClassNotFoundException(it) })
        assertThrows(ClassNotFoundException::class.java) { api.connect() }
        assertNull(Car.current)
        api.close()
    }

    @Test fun encoderInputsFitInstrumentDistanceAndTimeFields() {
        val api = connected()
        api.publish(PrefaceInstrumentGuidance(109, Int.MAX_VALUE, "", Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(6553.5f, Encoder.values[4])
        assertEquals(1638.3f, Encoder.values[6])
        assertEquals(8191, Encoder.values[7])
        api.close()
    }

    @Test fun savedExperimentalChoiceDefaultsOffAndIsPreserved() {
        assertFalse(PrefaceInstrumentSettings.enabled(context))
        PrefaceInstrumentSettings.save(context, true)
        assertTrue(PrefaceInstrumentSettings.enabled(context))
        PrefaceInstrumentSettings.save(context, false)
        assertFalse(PrefaceInstrumentSettings.enabled(context))
    }

    class Car(private val callback: ServiceConnection) {
        var connected = false
        fun connect() { connected = true; callback.onServiceConnected(ComponentName("test", "Car"), null) }
        fun disconnect() { connected = false }
        fun isConnected() = connected
        fun getCarManager(name: String): Any { check(name == "property"); return properties }
        companion object {
            var current: Car? = null
            var properties = Properties()
            @JvmStatic fun createCar(context: Context, callback: ServiceConnection): Car = Car(callback).also { current = it }
        }
    }
    class Properties {
        var config: PropertyConfig? = PropertyConfig()
        val sent = mutableListOf<ByteArray>()
        val ids = mutableListOf<Int>()
        fun getPropertyList(): List<PropertyConfig> = listOfNotNull(config)
        fun setProperty(type: Class<*>, id: Int, area: Int, value: Any) {
            check(type == ByteArray::class.java && area == 0)
            ids += id; sent += value as ByteArray
        }
    }
    class PropertyConfig(private val access: Int = 3, private val type: Class<*> = ByteArray::class.java) {
        fun getPropertyId() = 762314830
        fun getAccess() = access
        fun getPropertyType() = type
    }
    class SystemState {
        companion object {
            val values = mutableMapOf<String, String>()
            @JvmStatic fun get(key: String, default: String): String = values[key] ?: default
        }
    }
    class Encoder {
        companion object {
            var values = emptyList<Any>()
            @JvmStatic fun convertNAVTurn2IPK(id: Int): Int = id - 100
            @JvmStatic fun convertNAVF3(active: Boolean, end: Boolean, arrow: Int, destinationUnit: Int,
                destination: Float, turnUnit: Int, turn: Float, minutes: Int, road: String): ByteArray {
                values = listOf(active, end, arrow, destinationUnit, destination, turnUnit, turn, minutes, road)
                return byteArrayOf(3, 42)
            }
        }
    }
}
