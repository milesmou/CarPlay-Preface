package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Prefers Linkco's package/MusicClient overload using the installed firmware's implementation. */
internal class PrefaceMediaCenterBackend(private val context: Context,
    private val diagnostic: (String) -> Unit = {},
    private val resolve: (String) -> Class<*> = { Class.forName(it) }) : PrefaceMediaCenter.Backend {
    private lateinit var api: Any
    private lateinit var apiType: Class<*>
    private lateinit var clientType: Class<*>
    private lateinit var infoType: Class<*>
    private lateinit var client: Any
    private lateinit var registration: Method
    private lateinit var publication: Method
    private var concreteDisconnect: (() -> Unit)? = null
    private var concreteInfo: ((CarPlayNowPlaying) -> Any)? = null
    private var registrationPath = "not-selected"
    @Volatile private var registrationAttempted = false
    private var sdkVersion = "unavailable"
    private var initStartedMs = 0L
    private val sampledFailure = AtomicBoolean()
    private val observedCommands = AtomicInteger()
    private lateinit var snapshot: () -> PrefaceMediaCenter.Snapshot
    @Volatile private var progressPush: Method? = null
    @Volatile private var lyricPush: Method? = null
    @Volatile private var mediaListPush: Method? = null
    private var mediaListValue: Any? = null
    @Volatile private var lyricInfoSupported = false
    private var publishedToken: Any? = null
    private var publishedInfo: CarPlayNowPlaying? = null
    private var publishedPosition: Long? = null
    private var clearLyricsPending = false
    private val launch by lazy {
        PendingIntent.getActivity(context, 0,
            Intent(context, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun init(snapshot: () -> PrefaceMediaCenter.Snapshot, command: (Int) -> Boolean,
        ready: (Boolean) -> Unit) {
        this.snapshot = snapshot
        initStartedMs = SystemClock.elapsedRealtime()
        MediaReadinessDiagnostics.environment(context, diagnostic)
        apiType = resolve("com.ecarx.sdk.mediacenter.MediaCenterAPI")
        val callbackType = resolve("com.ecarx.sdk.openapi.ECarXApiClient\$Callback")
        require(callbackType.isInterface)
        // Linkco obtains the API singleton before constructing any MusicClient/PlaybackInfo.
        api = apiType.getMethod("get", Context::class.java).invoke(null, context)
            ?: error("MediaCenterAPI returned null")
        sdkVersion = runCatching {
            (apiType.getMethod("getVersionInt").invoke(api) as? Number)?.toInt()?.toString() ?: "unavailable"
        }
            .getOrDefault("unavailable")
        // A missing overload may fall back; a real ready/permission/register rejection never does.
        val observedCommand: (Int) -> Boolean = { index ->
            val accepted = command(index)
            if (observedCommands.getAndIncrement() < 64) diagnostic("OEM media diagnostic command index=$index accepted=$accepted")
            accepted
        }
        if (!configureConcreteClient(observedCommand)) configureInterfaceClient(observedCommand)
        if (registrationPath == "package-music-client") configureMediaList()
        apiType.getMethod("requestPlay", Any::class.java)
        apiType.getMethod("unregister", Any::class.java)
        apiType.getMethod("updateCurrentSourceType", Any::class.java, Int::class.javaPrimitiveType)
        progressPush = runCatching {
            apiType.getMethod("updateCurrentProgress", Any::class.java, Long::class.javaPrimitiveType)
        }.getOrNull()
        lyricPush = runCatching {
            apiType.getMethod("updateCurrentLyric", Any::class.java, String::class.java)
        }.getOrNull()
        lyricInfoSupported = infoType.methods.any { it.name == "getCurrentLyricSentence" } &&
            infoType.methods.any { it.name == "getLyricContent" }
        diagnostic("OEM media center init path=$registrationPath sdkVersion=$sdkVersion " +
            "mainThread=${Looper.myLooper() === Looper.getMainLooper()} processRegistration=true")
        val callback = proxy(callbackType) { method, args ->
            if (method.name == "onAPIReady") {
                val available = args?.firstOrNull() == true
                val phase = if (registrationAttempted) "after-registration-attempt" else "before-registration"
                diagnostic("OEM media center ready=$available path=$registrationPath phase=$phase")
                diagnostic("OEM media diagnostic callback elapsedMs=${SystemClock.elapsedRealtime() - initStartedMs} " +
                    "mainThread=${Looper.myLooper() === Looper.getMainLooper()} available=$available")
                if (!available && sampledFailure.compareAndSet(false, true)) diagnosticsWorker.execute {
                    MediaReadinessDiagnostics.environment(context, diagnostic)
                    MediaReadinessDiagnostics.sdkLogs(diagnostic)
                }
                ready(available)
            }
            defaultValue(method.returnType)
        }
        val started = SystemClock.elapsedRealtime()
        try { apiType.getMethod("init", Context::class.java, callbackType).invoke(api, context, callback) }
        finally { diagnostic("OEM media diagnostic initReturned elapsedMs=${SystemClock.elapsedRealtime() - started}") }
    }

    private fun configureConcreteClient(command: (Int) -> Boolean): Boolean {
        try {
            val concreteClient = resolve("com.ecarx.sdk.mediacenter.MusicClient")
            val concretePlayback = resolve("com.ecarx.sdk.mediacenter.MusicPlaybackInfo")
            val register = apiType.getMethod("registerMusic", String::class.java, concreteClient)
            val publish = apiType.getMethod("updateMusicPlaybackState", Any::class.java, concretePlayback)
            // Both constructors and the callback contract must exist before loading our subclass.
            concreteClient.getConstructor()
            concretePlayback.getConstructor()
            for (name in listOf("onNext", "onPrevious", "onPlay", "onPause")) {
                if (concreteClient.getMethod(name).returnType != Boolean::class.javaPrimitiveType) {
                    throw NoSuchMethodException("MusicClient callback signature")
                }
            }
            if (concreteClient.getMethod("getMusicPlaybackInfo").returnType != concretePlayback) {
                throw NoSuchMethodException("MusicClient playback signature")
            }
            val bridge = PrefaceMusicClientBridge(snapshot, command, context.packageName, { launch })
            require(concreteClient.isInstance(bridge.client))
            client = bridge.client
            clientType = concreteClient
            infoType = concretePlayback
            registration = register
            publication = publish
            concreteInfo = bridge::playbackInfo
            concreteDisconnect = bridge::disconnect
            registrationPath = "package-music-client"
            return true
        } catch (error: ReflectiveOperationException) {
            diagnostic("OEM media center concrete-api-unavailable:${error.javaClass.simpleName}; using interface overload")
        } catch (error: LinkageError) {
            diagnostic("OEM media center concrete-api-unavailable:${error.javaClass.simpleName}; using interface overload")
        }
        return false
    }

    private fun configureInterfaceClient(command: (Int) -> Boolean) {
        clientType = resolve("com.ecarx.sdk.mediacenter.IMusicClient")
        infoType = resolve("com.ecarx.sdk.mediacenter.IMusicPlaybackInfo")
        require(clientType.isInterface && infoType.isInterface)
        registration = apiType.getMethod("registerMusic", clientType)
        publication = apiType.getMethod("updateMusicPlaybackState", Any::class.java, infoType)
        registrationPath = "interface-client"
        client = proxy(clientType) { method, args ->
            when (method.name) {
                "onNext" -> command(CarPlayMediaButton.NEXT)
                "onPrevious" -> command(CarPlayMediaButton.PREVIOUS)
                "onPlay" -> command(CarPlayMediaButton.PLAY)
                "onPause" -> command(CarPlayMediaButton.PAUSE)
                "onSourceSelected" -> if (args?.firstOrNull() == SOURCE_TYPE) command(CarPlayMediaButton.PLAY) else false
                "getMusicPlaybackInfo" -> playbackInfo(snapshot().info)
                "getCurrentProgress" -> snapshot().info.elapsedMillis ?: 0L
                "getCurrentSourceType" -> SOURCE_TYPE
                "getMediaSourceTypeList" -> intArrayOf(SOURCE_TYPE)
                "getPlaylist" -> emptyList<Any>()
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun configureMediaList() {
        try {
            val type = resolve("com.ecarx.sdk.mediacenter.MediaListInfo")
            val method = apiType.getMethod("updateMediaList", Any::class.java, type)
            val value = prefaceMediaList()
            require(type.isInstance(value))
            mediaListValue = value
            mediaListPush = method
        } catch (error: ReflectiveOperationException) {
            diagnostic("OEM media center media-list-unavailable:${error.javaClass.simpleName}")
        } catch (error: LinkageError) {
            diagnostic("OEM media center media-list-unavailable:${error.javaClass.simpleName}")
        }
    }

    override fun register(): Any? {
        registrationAttempted = true
        val started = SystemClock.elapsedRealtime()
        val result = if (registrationPath == "package-music-client") {
            registration.invoke(api, context.packageName, client)
        } else registration.invoke(api, client)
        diagnostic("OEM media diagnostic register resultPresent=${result != null} elapsedMs=${SystemClock.elapsedRealtime() - started}")
        return result
    }
    override fun requestPlay(token: Any): Boolean {
        val started = SystemClock.elapsedRealtime()
        // A process-owned record may still say source=-1 from the previous disconnection.
        // Restore the external-player identity before the SDK queries it during requestPlay.
        concreteInfo?.invoke(snapshot().info)
        val accepted = call("requestPlay", token) == true
        if (accepted) publishedToken = null
        diagnostic("OEM media diagnostic requestPlay accepted=$accepted elapsedMs=${SystemClock.elapsedRealtime() - started}")
        return accepted
    }

    override fun publish(token: Any, info: CarPlayNowPlaying): Boolean {
        val newSource = publishedToken !== token
        if (newSource) {
            mediaListPush?.let { method -> optional("media-list", { mediaListPush = null }) {
                method.invoke(api, token, mediaListValue)
            } }
            apiType.getMethod("updateCurrentSourceType", Any::class.java, Int::class.javaPrimitiveType)
                .invoke(api, token, SOURCE_TYPE)
            publishedToken = token
            publishedInfo = null
            publishedPosition = null
            clearLyricsPending = true
        }
        // Position ticks have their own SDK method; do not retransmit the whole music record.
        val metadata = info.copy(elapsedMillis = null)
        if (publishedInfo != metadata) {
            if (publication.invoke(api, token, playbackInfo(info)) != true) return false
            publishedInfo = metadata
        }
        val position = info.elapsedMillis
        if (position != null && position != publishedPosition) {
            progressPush?.let { method -> optional("progress", { progressPush = null }) {
                method.invoke(api, token, position)
            } }
            publishedPosition = position
        }
        // The current iAP2 subscription has no independent lyrics payload. Clear stale OEM
        // lyrics at source activation; never mislabel the song title as a lyric sentence.
        if (clearLyricsPending) {
            lyricPush?.let { method -> optional("lyric-clear", { lyricPush = null }) {
                method.invoke(api, token, "")
            } }
            clearLyricsPending = false
        }
        return true
    }

    override fun disconnect() {
        concreteDisconnect?.invoke()
        publishedToken = null
        publishedInfo = null
        publishedPosition = null
    }

    override fun unregister(token: Any): Boolean {
        disconnect()
        return call("unregister", token) == true
    }
    private fun call(name: String, token: Any): Any? = apiType.getMethod(name, Any::class.java).invoke(api, token)

    private fun playbackInfo(info: CarPlayNowPlaying): Any = concreteInfo?.invoke(info) ?: proxy(infoType) { method, _ ->
        when (method.name) {
            "getTitle" -> info.title ?: "CarPlay"
            "getArtist" -> info.artist ?: ""
            "getAlbum" -> info.album ?: ""
            "getDuration" -> info.durationMillis ?: 0L
            "getPlaybackStatus" -> if (info.playing) 1 else 0
            "getSourceType" -> SOURCE_TYPE
            "getLaunchIntent" -> launch
            "getPackageName" -> context.packageName
            "getAppName" -> "CarPlay"
            "getCurrentLyricSentence", "getLyricContent" -> ""
            "getLyric" -> null
            else -> defaultValue(method.returnType)
        }
    }

    override fun report(): String = "registrationPath=$registrationPath sdkVersion=$sdkVersion metadata=title,artist,album,duration,state " +
        "mediaListPush=${mediaListPush != null} " +
        "progressPush=${progressPush != null} lyricInfo=$lyricInfoSupported lyricPush=${lyricPush != null} " +
        "lyricInput=not-provided"

    private inline fun optional(phase: String, disable: () -> Unit, action: () -> Unit) {
        try { action() } catch (error: Exception) {
            disable()
            val cause = error.cause ?: error
            runCatching { diagnostic("OEM media center $phase-failed:${cause.javaClass.simpleName}") }
        } catch (error: LinkageError) {
            disable()
            runCatching { diagnostic("OEM media center $phase-unavailable:${error.javaClass.simpleName}") }
        }
    }

    private fun proxy(type: Class<*>, invoke: (Method, Array<out Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "CarPlay ${type.simpleName}"
                else -> invoke(method, args)
            }
        }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    private companion object {
        val diagnosticsWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-media-diagnostic").apply { isDaemon = true }
        }
        // Observed in the reference APK; availability/admission remain runtime checks.
        const val SOURCE_TYPE = 6
    }
}
