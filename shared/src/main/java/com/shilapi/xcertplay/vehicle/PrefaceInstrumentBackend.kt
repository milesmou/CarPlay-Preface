package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The native adapter caches navigation against the first DimInteraction's vehicle manager. */
internal class PrefaceInstrumentSdkHandle {
    var dim: Any? = null
    var navigation: Any? = null
    var watcher: Any? = null
    var registered = false
    var connected = false
    var connecting = false
    var owner: Any? = null
    var quietUntilMs = 0L
}

/** NaviDashboardManager's aggregate and per-field guidance calls, using the installed SDK. */
internal class PrefaceInstrumentSdkBackend(
    private val context: Context,
    private val resolve: (String) -> Class<*> = { Class.forName(it) },
    private val diagnostic: (String) -> Unit = {},
    private val currentSession: () -> Boolean = { true },
    private val sdk: PrefaceInstrumentSdkHandle = applicationSdk,
) : PrefaceInstrumentBackend {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var started = false // main thread only
    private lateinit var dimType: Class<*>
    private lateinit var connectableType: Class<*>
    private lateinit var navigationType: Class<*>
    private lateinit var infoType: Class<*>
    private lateinit var update: Method
    private lateinit var status: Method
    private lateinit var arrow: Method
    private lateinit var nextDistance: Method
    private lateinit var eta: Method
    private lateinit var destinationDistance: Method
    private lateinit var nextRoad: Method
    private var configured = false
    private var reportedReady = false
    private var awaitingPreparation = false
    private val preparation = PrefaceAmapPreparation(context, { !closed && currentSession() }, diagnostic)

    override fun prepareSession() = onMain { preparation.start() }
    override fun connectionTimeoutNs(): Long = preparation.timeoutNs()

    override fun connect() = onMain {
        check(!closed && currentSession()) { "instrument-session-ended" }
        preparation.start()
        awaitingPreparation = !preparation.ready()
        if (awaitingPreparation) return@onMain
        if (!configured) {
            dimType = resolve("com.ecarx.xui.adaptapi.diminteraction.DimInteraction")
            connectableType = resolve("com.ecarx.xui.adaptapi.binder.IConnectable")
            val watcherType = resolve("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            navigationType = resolve("com.ecarx.xui.adaptapi.diminteraction.INaviInteraction")
            infoType = resolve("com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$INavigationInfo")
            check(watcherType.isInterface && infoType.isInterface) { "instrument-sdk-interface-mismatch" }
            val methods = infoType.methods.filter { it.declaringClass != Any::class.java }
            val getters = methods.map { it.name }.toSet()
            check(getters.all { it in INFO_GETTERS } && INFO_REQUIRED.all { it in getters } && methods.all {
                it.parameterCount == 0 && when (it.name) {
                    "getNavigationStatus", "getNavigationTurnId", "getDrivingDirection", "getMuteState", "getDayNightMode" ->
                        it.returnType == Int::class.javaPrimitiveType
                    "getDistanceToNextGuidancePoint", "getDistanceToDestination", "getETA" ->
                        it.returnType == Long::class.javaPrimitiveType
                    "getNextGuidancePointName", "getNavigationTurnSVG" -> it.returnType == String::class.java
                    "getLaneInfo" -> it.returnType.isArray
                    else -> !it.returnType.isPrimitive
                }
            }) {
                "instrument-sdk-navigation-info-mismatch"
            }
            update = navigationType.getMethod("updateNavigationInfo", infoType)
            navigationType.getMethod("notifyTurnByTurnStarted")
            navigationType.getMethod("notifyTurnByTurnStopped")
            // Native refreshGuideInfo sends both aggregate info and these individual fields.
            // Resolve the complete contract before creating an SDK instance or starting a route.
            val integer = Int::class.javaPrimitiveType!!
            status = guidanceMethod("notifyNavigationStatus", integer)
            arrow = guidanceMethod("updateTurnByTurnArrow", integer)
            nextDistance = guidanceMethod("updateDistanceToNextGuidancePoint", integer)
            eta = guidanceMethod("updateETA", integer)
            destinationDistance = guidanceMethod("updateDistanceToDestination", integer)
            nextRoad = guidanceMethod("updateNextGuidancePointName", String::class.java)
            if (sdk.dim == null) {
                sdk.dim = dimType.getMethod("create", Context::class.java).invoke(null, context)
                    ?: error("instrument-sdk-create-unavailable")
            }
            check(connectableType.isInstance(sdk.dim)) { "instrument-sdk-connectable-unavailable" }
            if (sdk.watcher == null) {
                // The retained watcher must not capture a controller/backend from an old session.
                val nativeState = sdk
                val callbackMain = main
                sdk.watcher = proxy(watcherType) { name, _ ->
                    check(name == "onConnected" || name == "onDisConnected") { "instrument-sdk-watcher-mismatch" }
                    val change = {
                        if (nativeState.registered) {
                            nativeState.connected = name == "onConnected"
                            nativeState.connecting = false
                        }
                    }
                    if (Looper.myLooper() === callbackMain.looper) change() else callbackMain.post(change)
                    null
                }
            }
            configured = true
        }
        check(!closed && currentSession()) { "instrument-session-ended" }
        check(sdk.owner == null || sdk.owner === this) { "instrument-sdk-owned-by-another-session" }
        sdk.owner = this
        if (!sdk.registered) {
            val watcherType = resolve("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            sdk.registered = true
            connectableType.getMethod("registerConnectWatcher", watcherType).invoke(sdk.dim, sdk.watcher)
        }
        if (!sdk.connected && !sdk.connecting) {
            sdk.connecting = true
            connectableType.getMethod("connect").invoke(sdk.dim)
        }
    }

    override fun ready(): Boolean = onMain {
        if (closed || !currentSession()) return@onMain false
        if (!preparation.ready()) return@onMain false
        if (!configured && awaitingPreparation) connect()
        if (!configured) return@onMain false
        if (closed || !currentSession() || sdk.owner !== this || !sdk.connected || SystemClock.uptimeMillis() < sdk.quietUntilMs) {
            return@onMain false
        }
        if (sdk.navigation == null) {
            sdk.navigation = dimType.getMethod("getNaviInteraction").invoke(sdk.dim)
                ?: error("instrument-sdk-navigation-unavailable")
            check(navigationType.isInstance(sdk.navigation)) { "instrument-sdk-navigation-type-mismatch" }
        }
        if (!reportedReady) {
            reportedReady = true
            diagnostic("instrument navigation native SDK connected; output=aggregate+fields; state-management=OEM; preparation=OEM-or-modified-AMap-background; display-confirmed=false")
        }
        true
    }

    // The SDK owns its navigation/phone bookkeeping. Its output flags are not an admission gate.
    override fun available(): Boolean = ready()

    override fun publish(guidance: PrefaceInstrumentGuidance) = onMain {
        check(ready() && currentSession()) { "instrument-session-ended" }
        check(guidance.turnId in 102..109) { "unconfirmed-instrument-arrow" }
        if (!started) {
            navigationType.getMethod("notifyTurnByTurnStarted").invoke(sdk.navigation)
            started = true
            invokeGuidance(status, 1) // Original map-start status 16 becomes NAVIGATION_STATUS_SUCCEED.
        }
        invokeGuidance(update, navigationInfo(guidance))
        // Build327 NaviDashboardManager.refreshGuideInfo (a.java:734) sends these after
        // updateNavigationInfo. The integer-arrow path has its own native state and encoder;
        // it must not be replaced with a fabricated projection/first-frame-ready event.
        invokeGuidance(arrow, guidance.turnId)
        invokeGuidance(nextDistance, guidance.distanceMeters.coerceAtLeast(0))
        invokeGuidance(eta, guidance.remainingSeconds.toSdkInt())
        invokeGuidance(destinationDistance, guidance.remainingMeters.toSdkInt())
        invokeGuidance(nextRoad, guidance.road)
        Unit
    }

    private fun guidanceMethod(name: String, parameter: Class<*>): Method {
        try {
            return navigationType.getMethod(name, parameter).also {
                check(it.returnType == Void.TYPE) { "instrument-sdk-guidance-method-mismatch" }
            }
        } catch (error: NoSuchMethodException) {
            diagnostic("instrument navigation native SDK missing method=$name; required=OEM-guidance-fields")
            throw IllegalStateException("instrument-sdk-guidance-method-mismatch", error)
        }
    }

    private fun invokeGuidance(method: Method, value: Any) {
        check(!closed && sdk.owner === this && sdk.connected && currentSession()) { "instrument-session-ended" }
        method.invoke(sdk.navigation, value)
    }

    private fun Long.toSdkInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    override fun clear() = onMain {
        if (sdk.owner === this && sdk.connected) stopNavigation()
    }

    private fun stopNavigation() {
        if (!started) return
        started = false
        try {
            // UNNAVI=0 clears the native adapter's own route state; never pass null info.
            update.invoke(sdk.navigation, navigationInfo(null))
        } finally {
            try { navigationType.getMethod("notifyTurnByTurnStopped").invoke(sdk.navigation) }
            finally {
                try { status.invoke(sdk.navigation, 0) }
                finally {
                    // Build327 queues native clear callbacks on main at 0/200/500 ms. Complete
                    // them before replacement guidance, even when clear/stop/status fails.
                    sdk.quietUntilMs = SystemClock.uptimeMillis() + NATIVE_STOP_DRAIN_MS
                }
            }
        }
    }

    private fun navigationInfo(guidance: PrefaceInstrumentGuidance?): Any = proxy(infoType) { name, method ->
        when (name) {
            "getNavigationStatus" -> if (guidance == null) 0 else 1
            "getNavigationTurnId" -> guidance?.turnId ?: 0
            "getDistanceToNextGuidancePoint" -> guidance?.distanceMeters?.toLong() ?: 0L
            "getDistanceToDestination" -> guidance?.remainingMeters ?: 0L
            "getETA" -> guidance?.remainingSeconds ?: 0L
            "getNextGuidancePointName" -> guidance?.road.orEmpty()
            "getNavigationTurnSVG" -> null
            "getDrivingDirection", "getMuteState", "getDayNightMode" -> 0
            "getLaneInfo" -> java.lang.reflect.Array.newInstance(
                method.returnType.componentType ?: error("instrument-sdk-navigation-info-mismatch"), 0)
            "getExtensionInfo", "getHighwayExitInfo", "getRoadCameraInfo", "getServiceAreaInfo" -> null
            else -> error("instrument-sdk-navigation-info-mismatch")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        preparation.close()
        // Cleanup is never cancelled by the publication timeout. It stays ahead of a replacement
        // connect in the main queue, even if the UI temporarily stops processing messages.
        val release = Runnable {
            try { releaseOnMain() }
            catch (error: Exception) { diagnostic("instrument navigation native release failed type=${error.javaClass.simpleName}") }
            catch (error: LinkageError) { diagnostic("instrument navigation native release failed type=${error.javaClass.simpleName}") }
        }
        if (Looper.myLooper() === main.looper) release.run()
        else if (!main.post(release)) diagnostic("instrument navigation native release unavailable; main queue stopped")
    }

    private fun releaseOnMain() {
        if (sdk.owner !== this) return
        try {
            if (sdk.connected) stopNavigation()
        } finally {
            started = false
            sdk.owner = null
            val delay = (sdk.quietUntilMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
            main.postDelayed({
                // A replacement may already have leased the same native instance.
                if (sdk.owner == null) {
                    try {
                        if (sdk.registered) connectableType.getMethod("unregisterConnectWatcher").invoke(sdk.dim)
                    } catch (error: Exception) {
                        diagnostic("instrument navigation native watcher release failed type=${error.javaClass.simpleName}")
                    } finally {
                        sdk.registered = false
                        sdk.connected = false
                        sdk.connecting = false
                        runCatching { connectableType.getMethod("disconnect").invoke(sdk.dim) }.onFailure {
                            diagnostic("instrument navigation native disconnect failed type=${it.javaClass.simpleName}")
                        }
                    }
                }
            }, delay)
        }
    }

    private fun proxy(type: Class<*>, invoke: (String, Method) -> Any?): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { target, method, args ->
            when (method.name) {
                "equals" -> target === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(target)
                "toString" -> "CarPlay instrument SDK adapter"
                else -> invoke(method.name, method)
            }
        }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() === main.looper) return block()
        val task = FutureTask(block)
        check(main.post(task)) { "instrument-sdk-main-unavailable" }
        try { return task.get(5, TimeUnit.SECONDS) }
        catch (error: TimeoutException) {
            task.cancel(false)
            throw IllegalStateException("instrument-sdk-main-timeout", error)
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            when (cause) {
                is Exception -> throw cause
                is LinkageError -> throw cause
                else -> throw IllegalStateException("instrument-sdk-main-failure", cause)
            }
        }
    }

    private companion object {
        val applicationSdk = PrefaceInstrumentSdkHandle()
        const val NATIVE_STOP_DRAIN_MS = 550L
        val INFO_REQUIRED = setOf("getNavigationStatus", "getNavigationTurnId", "getDistanceToNextGuidancePoint",
            "getDistanceToDestination", "getETA", "getNextGuidancePointName")
        val INFO_GETTERS = INFO_REQUIRED + setOf("getNavigationTurnSVG", "getDrivingDirection", "getMuteState",
            "getDayNightMode", "getLaneInfo", "getExtensionInfo", "getHighwayExitInfo", "getRoadCameraInfo", "getServiceAreaInfo")
    }
}
