package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrefaceInstrumentBackendTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var handle: PrefaceInstrumentSdkHandle
    private var currentSession = true
    private var navigationInfoType: Class<*> = Info::class.java
    private var navigationSdkType: Class<*> = Navigation::class.java
    private val resolved = mutableListOf<String>()
    private val guidance = PrefaceInstrumentGuidance(102, 150, "测试道路", 12500, 90)

    private fun backend() = PrefaceInstrumentSdkBackend(context, resolve = { name ->
        resolved += name
        when (name) {
            "com.ecarx.xui.adaptapi.diminteraction.DimInteraction" -> Dim::class.java
            "com.ecarx.xui.adaptapi.binder.IConnectable" -> Connectable::class.java
            "com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher" -> Watcher::class.java
            "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction" -> navigationSdkType
            "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$INavigationInfo" -> navigationInfoType
            else -> throw ClassNotFoundException(name) // No raw Car API, encoder or SystemProperties.
        }
    }, currentSession = { currentSession }, sdk = handle)

    @Before fun reset() {
        handle = PrefaceInstrumentSdkHandle()
        currentSession = true
        navigationInfoType = Info::class.java
        navigationSdkType = Navigation::class.java
        resolved.clear()
        Dim.events.clear()
        Dim.created = 0
        Dim.instance = Dim()
        Dim.automaticConnection = true
        Dim.failPublish = null
        Dim.failStart = null
        Dim.failStop = null
        Dim.failField = null
        Dim.afterField = null
        Dim.failClearStatus = false
    }

    private fun connected(): PrefaceInstrumentSdkBackend = backend().also { it.connect(); assertTrue(it.ready()) }

    @Test fun followsNativeCreateConnectStartUpdateStopLifecycle() {
        val api = connected()
        api.publish(guidance)
        api.publish(guidance.copy(distanceMeters = 120))
        assertEquals(listOf("create", "watch", "connect", "navigation", "start", "status:1",
            "update:1", "arrow:102", "nextDistance:150", "eta:90", "destinationDistance:12500", "road",
            "update:1", "arrow:102", "nextDistance:120", "eta:90", "destinationDistance:12500", "road"), Dim.events)
        val info = Dim.instance.navigation.updates.first()
        assertEquals(1, info.getNavigationStatus())
        assertEquals(102, info.getNavigationTurnId())
        assertEquals(150L, info.getDistanceToNextGuidancePoint())
        assertEquals(12500L, info.getDistanceToDestination())
        assertEquals(90L, info.getETA())
        assertEquals("测试道路", info.getNextGuidancePointName())
        assertEquals(0, info.getDrivingDirection())
        assertEquals(0, info.getLaneInfo().size)
        assertNull(info.getExtensionInfo())
        api.clear()
        assertEquals(listOf("update:0", "stop", "status:0"), Dim.events.takeLast(3))
        assertEquals(0L, Dim.instance.navigation.updates.last().getDistanceToDestination())
        api.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550))
        assertEquals(listOf("unwatch", "disconnect"), Dim.events.takeLast(2))
    }

    @Test fun missingBicvPropertiesAreNotQueriedOrUsedAsAnAdmissionGate() {
        val api = connected()
        api.publish(guidance)
        assertTrue(resolved.none { "SystemProperties" in it || "CarProperty" in it || "ECarXDIMUtil" in it })
        assertEquals(1, Dim.instance.navigation.updates.single().getNavigationStatus())
        api.close()
    }

    @Test fun sendsOriginalMapsIntegerGuidanceFieldsWithoutRequestingProjection() {
        val api = connected()
        api.publish(guidance)
        assertEquals(listOf("arrow" to 102, "nextDistance" to 150, "eta" to 90,
            "destinationDistance" to 12500, "road" to "测试道路"), Dim.instance.navigation.fields)
        assertEquals(1, Dim.events.count { it == "status:1" })
        api.publish(guidance.copy(turnId = 109, remainingMeters = 600, remainingSeconds = 25))
        assertEquals(listOf("arrow" to 109, "nextDistance" to 150, "eta" to 25,
            "destinationDistance" to 600, "road" to "测试道路"), Dim.instance.navigation.fields.takeLast(5))
        assertEquals(1, Dim.events.count { it == "status:1" })
        api.close()
    }

    @Test fun perFieldValuesCannotWrapLongDistancesOrEtaIntoNegativeIntegers() {
        val api = connected()
        api.publish(guidance.copy(remainingMeters = 0xffffffffL, remainingSeconds = Long.MAX_VALUE))
        val fields = Dim.instance.navigation.fields.toMap()
        assertEquals(Int.MAX_VALUE, fields["destinationDistance"])
        assertEquals(Int.MAX_VALUE, fields["eta"])
        api.close()
    }

    @Test fun missingPerFieldContractFailsBeforeCreatingOrStartingNativeSdk() {
        navigationSdkType = IncompleteNavigation::class.java
        val api = backend()
        val failure = assertThrows(IllegalStateException::class.java) { api.connect() }
        assertEquals("instrument-sdk-guidance-method-mismatch", failure.message)
        assertTrue(failure.cause is NoSuchMethodException)
        assertEquals(0, Dim.created)
        assertTrue(Dim.events.isEmpty())
        api.close()
    }

    @Test fun fieldFailureStopsRemainingFieldsAndCleanupStillClearsNativeStatus() {
        val api = connected()
        Dim.failField = "eta"
        val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { api.publish(guidance) }
        assertTrue(failure.targetException is SecurityException)
        assertEquals(listOf("arrow", "nextDistance"), Dim.instance.navigation.fields.map { it.first })
        api.close()
        assertTrue(Dim.events.containsAll(listOf("update:0", "stop", "status:0")))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550))
        assertEquals(listOf("unwatch", "disconnect"), Dim.events.takeLast(2))
    }

    @Test fun invalidatedSessionCannotSendTheRestOfAMultiFieldUpdate() {
        val api = connected()
        Dim.afterField = { if (it == "arrow") currentSession = false }
        assertThrows(IllegalStateException::class.java) { api.publish(guidance) }
        assertEquals(listOf("arrow"), Dim.instance.navigation.fields.map { it.first })
        api.close()
        assertEquals(listOf("update:0", "stop", "status:0"), Dim.events.takeLast(3))
    }

    @Test fun failedStatusClearStillDrainsNativeStopsBeforeReleasingOrReplacing() {
        val api = connected()
        api.publish(guidance)
        Dim.failClearStatus = true
        api.close()
        Dim.failClearStatus = false
        val replacement = backend()
        replacement.connect()
        assertFalse(replacement.ready())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550))
        assertTrue(replacement.ready())
        replacement.publish(guidance)
        assertTrue(Dim.events.indexOfLast { it == "native-clear:500" } < Dim.events.indexOfLast { it == "arrow:102" })
        replacement.close()
    }

    @Test fun mustReceiveRealConnectionCallbackBeforeGettingNavigationOrPublishing() {
        Dim.automaticConnection = false
        val api = backend()
        api.connect()
        assertFalse(api.ready())
        assertFalse("navigation" in Dim.events)
        assertThrows(IllegalStateException::class.java) { api.publish(guidance) }
        Dim.instance.watcher!!.onConnected()
        assertTrue(api.ready())
        api.publish(guidance)
        Dim.instance.watcher!!.onDisConnected()
        assertFalse(api.ready())
        assertThrows(IllegalStateException::class.java) { api.publish(guidance) }
        api.close()
    }

    @Test fun queuedNativeStopsFinishBeforeAReplacementCanPublish() {
        val first = connected()
        first.publish(guidance)
        first.clear()
        first.close()
        val second = backend()
        second.connect()
        assertFalse(second.ready())
        assertThrows(IllegalStateException::class.java) { second.publish(guidance) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550))
        assertTrue(second.ready())
        second.publish(guidance)
        assertEquals(1, Dim.created) // Reuse the SDK's original vehicle-manager instance.
        assertFalse("disconnect" in Dim.events)
        val lastClear = Dim.events.indexOfLast { it == "native-clear:500" }
        assertTrue(lastClear < Dim.events.indexOfLast { it == "update:1" })
        first.close()
        assertTrue(second.ready())
        second.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550))
        assertEquals(1, Dim.events.count { it == "disconnect" })
    }

    @Test fun reconnectReusesOriginalNativeInstanceAfterFullRelease() {
        val first = connected()
        first.publish(guidance); first.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550))
        val second = connected()
        second.publish(guidance)
        assertEquals(1, Dim.created)
        assertEquals(2, Dim.events.count { it == "connect" })
        assertEquals(2, Dim.events.count { it == "start" })
        second.close()
    }

    @Test fun expiredSessionCannotStartOrPublishAndUnsupportedTurnIsRejected() {
        val api = connected()
        currentSession = false
        assertThrows(IllegalStateException::class.java) { api.publish(guidance) }
        assertFalse("start" in Dim.events)
        currentSession = true
        assertThrows(IllegalStateException::class.java) { api.publish(guidance.copy(turnId = 999)) }
        assertFalse("start" in Dim.events)
        api.close()
        assertThrows(IllegalStateException::class.java) { api.publish(guidance) }
    }

    @Test fun nativePermissionRejectionHasNoRawPropertyFallback() {
        val api = connected()
        Dim.failPublish = SecurityException("native rejection")
        val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { api.publish(guidance) }
        assertTrue(failure.targetException is SecurityException)
        api.close()
        assertTrue("stop" in Dim.events)
        assertTrue(resolved.none { "CarProperty" in it || "ECarXDIMUtil" in it })
    }

    @Test fun failedStopStillReleasesWatcherAndConnection() {
        val api = connected()
        api.publish(guidance)
        Dim.failStop = SecurityException("stop rejected")
        api.close()
        assertTrue("status:0" in Dim.events)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550))
        assertEquals(listOf("unwatch", "disconnect"), Dim.events.takeLast(2))
    }

    @Test fun rejectedStartDoesNotClearNavigationThatWeNeverStarted() {
        val api = connected()
        Dim.failStart = SecurityException("start denied")
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) { api.publish(guidance) }
        api.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Dim.instance.navigation.updates.isEmpty())
        assertFalse("stop" in Dim.events)
        assertEquals(listOf("unwatch", "disconnect"), Dim.events.takeLast(2))
    }

    @Test fun unavailableSdkDoesNotPretendToConnect() {
        val api = PrefaceInstrumentSdkBackend(context, resolve = { throw ClassNotFoundException(it) }, sdk = handle)
        assertThrows(ClassNotFoundException::class.java) { api.connect() }
        assertFalse(api.ready())
        assertEquals(0, Dim.created)
        api.close()
    }

    @Test fun mismatchedSdkDataInterfaceFailsBeforeCreatingTheNativeInstance() {
        navigationInfoType = BadInfo::class.java
        val api = backend()
        assertThrows(IllegalStateException::class.java) { api.connect() }
        assertEquals(0, Dim.created)
        api.close()
    }

    @Test fun backgroundCallbacksAreOrderedOnMainAndReleasedCallbacksAreIgnored() {
        Dim.automaticConnection = false
        val api = backend()
        api.connect()
        val watcher = Dim.instance.watcher!!
        Thread { watcher.onConnected() }.apply { start(); join() }
        assertFalse(api.ready())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(api.ready())
        api.close()
        shadowOf(Looper.getMainLooper()).idle()
        watcher.onConnected()
        assertFalse(handle.connected)
    }

    @Test fun instrumentNavigationDefaultsOnAndPreservesSavedChoices() {
        val prefs = context.getSharedPreferences(PrefaceInstrumentSettings.PREFERENCES, Context.MODE_PRIVATE)
        assertFalse(prefs.contains(PrefaceInstrumentSettings.ENABLED))
        assertTrue(PrefaceInstrumentSettings.enabled(context))
        assertFalse(prefs.contains(PrefaceInstrumentSettings.ENABLED))
        PrefaceInstrumentSettings.save(context, true)
        assertTrue(PrefaceInstrumentSettings.enabled(context))
        PrefaceInstrumentSettings.save(context, false)
        assertFalse(PrefaceInstrumentSettings.enabled(context))
        prefs.edit().remove(PrefaceInstrumentSettings.ENABLED).commit()
        assertTrue(PrefaceInstrumentSettings.enabled(context))
    }

    interface Watcher { fun onConnected(); fun onDisConnected() }
    interface Connectable {
        fun registerConnectWatcher(watcher: Watcher)
        fun unregisterConnectWatcher()
        fun connect()
        fun disconnect()
    }
    class Dim : Connectable {
        var watcher: Watcher? = null
        val navigation = Navigation()
        override fun registerConnectWatcher(watcher: Watcher) { events += "watch"; this.watcher = watcher }
        override fun unregisterConnectWatcher() { events += "unwatch"; watcher = null }
        override fun connect() { events += "connect"; if (automaticConnection) watcher!!.onConnected() }
        override fun disconnect() { events += "disconnect" }
        fun getNaviInteraction(): Navigation { events += "navigation"; return navigation }
        fun setPresentationToDimSwitch(app: Int, pkg: String, cls: String, enabled: Boolean) {
            error("Projection is not part of CarPlay instrument navigation")
        }
        companion object {
            val events = mutableListOf<String>()
            var created = 0
            var automaticConnection = true
            var instance = Dim()
            var failPublish: Exception? = null
            var failStart: Exception? = null
            var failStop: Exception? = null
            var failField: String? = null
            var afterField: ((String) -> Unit)? = null
            var failClearStatus = false
            @JvmStatic fun create(context: Context): Dim { events += "create"; created++; return instance }
        }
    }
    class Navigation {
        val updates = mutableListOf<Info>()
        val fields = mutableListOf<Pair<String, Any>>()
        private var nativeStatus = 0
        fun notifyTurnByTurnStarted() { Dim.failStart?.let { throw it }; Dim.events += "start" }
        fun notifyNavigationStatus(value: Int) {
            Dim.events += "status:$value"
            if (value == 0 && Dim.failClearStatus) throw SecurityException("status clear denied")
            nativeStatus = value
        }
        fun updateNavigationInfo(info: Info) {
            if (info.getNavigationStatus() != 0) Dim.failPublish?.let { throw it }
            Dim.events += "update:${info.getNavigationStatus()}"
            updates += info
            nativeStatus = info.getNavigationStatus()
        }
        fun updateTurnByTurnArrow(value: Int) { field("arrow", value) }
        fun updateDistanceToNextGuidancePoint(value: Int) { field("nextDistance", value) }
        fun updateETA(value: Int) { field("eta", value) }
        fun updateDistanceToDestination(value: Int) { field("destinationDistance", value) }
        fun updateNextGuidancePointName(value: String) { field("road", value) }
        private fun field(name: String, value: Any) {
            if (nativeStatus == 0) return
            if (Dim.failField == name) throw SecurityException("native field denied")
            Dim.events += if (name == "road") name else "$name:$value"
            fields += name to value
            Dim.afterField?.invoke(name)
        }
        fun notifyTurnByTurnStopped() {
            Dim.events += "stop"
            for (delay in listOf(0L, 200L, 500L)) {
                Handler(Looper.getMainLooper()).postDelayed({ Dim.events += "native-clear:$delay" }, delay)
            }
            Dim.failStop?.let { throw it }
        }
    }
    class IncompleteNavigation {
        fun notifyTurnByTurnStarted() {}
        fun notifyTurnByTurnStopped() {}
        fun updateNavigationInfo(info: Info) {}
    }
    interface Info {
        fun getNavigationStatus(): Int
        fun getNavigationTurnId(): Int
        fun getDistanceToNextGuidancePoint(): Long
        fun getDistanceToDestination(): Long
        fun getETA(): Long
        fun getNextGuidancePointName(): String
        fun getDrivingDirection(): Int
        fun getMuteState(): Int
        fun getDayNightMode(): Int
        fun getNavigationTurnSVG(): String?
        fun getLaneInfo(): Array<Any>
        fun getExtensionInfo(): Any?
        fun getHighwayExitInfo(): Any?
        fun getRoadCameraInfo(): Any?
        fun getServiceAreaInfo(): Any?
    }
    interface BadInfo {
        fun getNavigationStatus(): Int
        fun getNavigationTurnId(): Int
        fun getDistanceToNextGuidancePoint(): Long
        fun getDistanceToDestination(): Long
        fun getETA(): Long
        fun getNextGuidancePointName(): String
        fun getLaneInfo(): Any // Native SDK requires an array.
    }
}
