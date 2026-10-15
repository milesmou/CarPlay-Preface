package com.shilapi.xcertplay

import android.content.Context
import com.ecarx.sdk.mediacenter.MediaListInfo
import com.ecarx.sdk.mediacenter.MusicClient
import com.ecarx.sdk.mediacenter.MusicPlaybackInfo
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PrefaceConcreteMediaCenterTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val log = mutableListOf<String>()
    private val commands = mutableListOf<Int>()
    private val song = CarPlayNowPlaying(title = "Track", artist = "Artist", album = "Album",
        playing = true, durationMillis = 45000, elapsedMillis = 1200)
    private var snapshot = PrefaceMediaCenter.Snapshot(song, true)

    @Before fun reset() { Api.current = Api() }

    private fun backend(apiType: Class<*> = Api::class.java, missingList: Boolean = false, beforeClient: () -> Unit = {}) = PrefaceMediaCenterBackend(app, log::add) { name ->
        when (name.substringAfterLast('.')) {
            "MediaCenterAPI" -> apiType
            "MusicClient" -> MusicClient::class.java.also { beforeClient() }
            "MusicPlaybackInfo" -> MusicPlaybackInfo::class.java
            "MediaListInfo" -> if (missingList) throw ClassNotFoundException(name) else MediaListInfo::class.java
            "IMusicClient" -> PrefaceMediaCenterBackendTest.Client::class.java
            "IMusicPlaybackInfo" -> PrefaceMediaCenterBackendTest.Info::class.java
            "ECarXApiClient\$Callback" -> Ready::class.java
            else -> throw ClassNotFoundException(name)
        }
    }

    @Test fun packageOverloadUsesOwnIdentityAndConcreteCallbacksEvenWhenInterfaceOverloadExists() {
        val backend = backend()
        val ready = mutableListOf<Boolean>()
        backend.init({ snapshot }, { commands.add(it) }, ready::add)
        assertEquals(listOf(true), ready)
        val token = backend.register()!!
        assertEquals(app.packageName, Api.current.registeredPackage)
        assertEquals(1, Api.current.classRegistrations)
        assertEquals(0, Api.current.interfaceRegistrations)
        val client = Api.current.client!!
        assertTrue(client.onNext()); assertTrue(client.onPrevious())
        assertTrue(client.onPlay()); assertTrue(client.onPause())
        assertEquals(listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS,
            CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE), commands)
        assertEquals(1200L, client.currentProgress)
        assertEquals(6, client.currentSourceType)
        assertArrayEquals(intArrayOf(6), client.mediaSourceTypeList)
        val initial = client.musicPlaybackInfo!!
        assertEquals(app.packageName, initial.packageName)
        assertEquals("Track", initial.title)
        assertEquals(45000L, initial.duration)
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(initial.launchIntent).savedIntent.component!!.className)
        snapshot = snapshot.copy(info = song.copy(playing = false))
        assertEquals(0, client.musicPlaybackInfo!!.playbackStatus)
        assertTrue(backend.requestPlay(token))
        assertTrue(backend.publish(token, song))
        assertEquals(listOf("request", "list", "source", "state"), Api.current.events)
        assertTrue(log.any { it.contains("register resultPresent=true elapsedMs=") })
        assertTrue(log.any { it.contains("requestPlay accepted=true elapsedMs=") })
        assertTrue(log.any { it.contains("command index=4 accepted=true") })
        assertEquals(6, Api.current.list!!.sourceType)
        assertTrue(Api.current.list!!.mediaList!!.isEmpty())
        assertEquals("Artist", Api.current.info!!.artist)
        assertEquals("Album", Api.current.info!!.album)
        assertEquals("", Api.current.info!!.lyricContent)
        backend.publish(token, song.copy(elapsedMillis = 1500))
        assertEquals(1, Api.current.events.count { it == "state" })
        assertTrue(backend.unregister(token))
        assertSame(token, Api.current.unregistered)
        assertTrue(backend.report().contains("registrationPath=package-music-client"))
        assertTrue(backend.report().contains("sdkVersion=331"))
    }

    @Test fun notReadyDoesNotRegisterOrTryAnotherOverload() {
        Api.current.ready = false
        val backend = backend()
        val center = PrefaceMediaCenter(Executor { it.run() }, { backend }, { true }, log::add)
        center.start()
        center.update(song, true, requestSource = true)
        assertEquals(0, Api.current.classRegistrations)
        assertEquals(0, Api.current.interfaceRegistrations)
        assertTrue(center.report().contains("stage=api-not-ready"))
        assertTrue(log.any { it.contains("ready=false path=package-music-client phase=before-registration") })
        center.close()
    }

    @Test fun registrationPermissionRejectionNeverRetriesWithTheInterfaceOverloadOrLogsPrivateMessages() {
        Api.current.rejectRegistration = true
        val backend = backend()
        val center = PrefaceMediaCenter(Executor { it.run() }, { backend }, { true }, log::add)
        center.start(); center.update(song, true, requestSource = true)
        assertEquals(1, Api.current.classRegistrations)
        assertEquals(0, Api.current.interfaceRegistrations)
        assertTrue(center.report().contains("register-failed:SecurityException"))
        assertFalse(log.any { it.contains("private credential") })
        center.close()
    }

    @Test fun nullTokenNeverRequestsPlayOrRegistersByAnotherOverload() {
        Api.current.nullToken = true
        val backend = backend()
        val center = PrefaceMediaCenter(Executor { it.run() }, { backend }, { true }, log::add)
        center.start(); center.update(song, true, requestSource = true)
        assertEquals(1, Api.current.classRegistrations)
        assertEquals(0, Api.current.interfaceRegistrations)
        assertTrue(Api.current.events.isEmpty())
        assertTrue(center.report().contains("register-null"))
        center.close()
    }

    @Test fun anOlderSdkWithoutThePackageOverloadKeepsTheInterfacePath() {
        val backend = backend(InterfaceOnlyApi::class.java)
        backend.init({ snapshot }, { true }, {})
        assertNotNull(backend.register())
        assertTrue(backend.report().contains("registrationPath=interface-client"))
        assertTrue(log.any { it.contains("concrete-api-unavailable:NoSuchMethodException") })
    }

    @Test fun realExplicitPauseIsNotConvertedIntoToggleAndRejectedCommandsRemainRejected() {
        val backend = backend()
        backend.init({ snapshot }, { false }, {})
        backend.register()
        val client = Api.current.client!!
        assertFalse(client.onPlay()); assertFalse(client.onPause())
        assertFalse(client.onNext()); assertFalse(client.onPrevious())
    }

    @Test fun aLowSdkVersionIsDiagnosticOnlyAndMissingMediaListDoesNotBlockPlayback() {
        Api.current.version = 300
        val backend = backend(missingList = true)
        backend.init({ snapshot }, { true }, {})
        val token = backend.register()!!
        assertTrue(backend.requestPlay(token))
        assertTrue(backend.publish(token, song))
        assertEquals(listOf("request", "source", "state"), Api.current.events)
        assertTrue(backend.report().contains("sdkVersion=300"))
        assertTrue(backend.report().contains("mediaListPush=false"))
    }

    @Test fun readinessLossAfterRegistrationIsDistinguishedFromInitialFailure() {
        val backend = backend()
        val readiness = mutableListOf<Boolean>()
        backend.init({ snapshot }, { true }, readiness::add)
        backend.register()
        Api.current.callback!!.onAPIReady(false)
        assertEquals(listOf(true, false), readiness)
        assertTrue(log.any { it.contains("ready=false path=package-music-client phase=after-registration-attempt") })
    }

    @Test fun apiSingletonIsObtainedBeforeConstructingTheConcreteClient() {
        val backend = backend(beforeClient = { assertTrue(Api.current.singletonRead) })
        backend.init({ snapshot }, { true }, {})
        assertNotNull(backend.register())
    }

    @Test fun cachedPlaybackRecordReflectsPauseDisconnectAndResumeWithoutChangingIdentity() {
        val backend = backend()
        backend.init({ snapshot }, { true }, {})
        val token = backend.register()!!
        val client = Api.current.client!!
        val record = client.musicPlaybackInfo!!
        backend.requestPlay(token)
        backend.publish(token, song)
        assertSame(record, Api.current.info)
        snapshot = snapshot.copy(info = song.copy(playing = false))
        assertSame(record, client.musicPlaybackInfo)
        assertEquals(0, record.playbackStatus)
        backend.disconnect()
        assertEquals(-1, record.sourceType)
        assertEquals(-1, client.currentSourceType)
        assertEquals(0, record.playbackStatus)
        assertNull(Api.current.unregistered)
        snapshot = snapshot.copy(info = song.copy(title = "Next"))
        backend.requestPlay(token)
        backend.publish(token, snapshot.info)
        assertSame(record, client.musicPlaybackInfo)
        assertEquals("Next", record.title)
        assertEquals(6, record.sourceType)
        assertEquals(1, record.playbackStatus)
        backend.unregister(token)
        assertEquals(-1, record.sourceType)
        assertEquals(0, record.playbackStatus)
    }

    @Test fun referencePlaybackCapabilitiesDoNotAdvertiseUnsupportedCarFunctions() {
        val backend = backend()
        backend.init({ snapshot }, { true }, {})
        backend.register()
        val info = Api.current.client!!.musicPlaybackInfo!!
        assertNull(info.appIcon)
        assertEquals("carplay", info.artwork.toString())
        assertEquals(info.artwork, info.nextArtwork)
        assertEquals(info.artwork, info.previousArtwork)
        assertEquals(0, info.playingMediaListType)
        assertFalse(info.isCollected)
        assertFalse(info.isSupportCollect)
        assertFalse(info.isSupportDownload)
        assertFalse(info.isSupportLoopModeSwitch)
        assertFalse(info.isSupportVrCtrlPlayStatus)
    }

    interface Ready { fun onAPIReady(ready: Boolean) }
    class Api {
        var ready = true
        var singletonRead = false
        var version = 331
        var callback: Ready? = null
        var rejectRegistration = false
        var nullToken = false
        var classRegistrations = 0
        var interfaceRegistrations = 0
        var registeredPackage: String? = null
        var client: MusicClient? = null
        var info: MusicPlaybackInfo? = null
        var list: MediaListInfo? = null
        var unregistered: Any? = null
        val events = mutableListOf<String>()
        fun getVersionInt() = version
        fun init(context: Context, callback: Ready) { this.callback = callback; callback.onAPIReady(ready) }
        fun registerMusic(packageName: String, client: MusicClient): Any? {
            classRegistrations++
            if (rejectRegistration) throw SecurityException("private credential")
            registeredPackage = packageName; this.client = client
            return if (nullToken) null else Any()
        }
        fun registerMusic(client: PrefaceMediaCenterBackendTest.Client): Any {
            interfaceRegistrations++; error("Concrete path must not register the interface")
        }
        fun requestPlay(token: Any): Boolean {
            assertEquals(6, client!!.musicPlaybackInfo!!.sourceType)
            events.add("request"); return true
        }
        fun updateMediaList(token: Any, info: MediaListInfo) { events.add("list"); list = info }
        fun updateCurrentSourceType(token: Any, source: Int) { assertEquals(6, source); events.add("source") }
        fun updateMusicPlaybackState(token: Any, info: MusicPlaybackInfo): Boolean {
            events.add("state"); this.info = info; return true
        }
        fun unregister(token: Any): Boolean { unregistered = token; return true }
        companion object {
            lateinit var current: Api
            @JvmStatic fun get(context: Context): Api = current.also { it.singletonRead = true }
        }
    }

    class InterfaceOnlyApi {
        fun init(context: Context, callback: Ready) { callback.onAPIReady(true) }
        fun registerMusic(client: PrefaceMediaCenterBackendTest.Client): Any = Any()
        fun requestPlay(token: Any) = true
        fun unregister(token: Any) = true
        fun updateCurrentSourceType(token: Any, source: Int) {}
        fun updateMusicPlaybackState(token: Any, info: PrefaceMediaCenterBackendTest.Info) = true
        companion object { @JvmStatic fun get(context: Context) = InterfaceOnlyApi() }
    }
}
