package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PrefaceMediaCenterBackendTest {
    @Test fun interfaceOverloadsPublishOwnSourceAndSendExplicitCommands() {
        val context = RuntimeEnvironment.getApplication()
        val sent = mutableListOf<Int>()
        val readiness = mutableListOf<Boolean>()
        val info = CarPlayNowPlaying(title = "Track", artist = "Artist", album = "Album", playing = true,
            elapsedMillis = 42L, durationMillis = 120L)
        val backend = PrefaceMediaCenterBackend(context) { name ->
            when (name.substringAfterLast('.')) {
                "MediaCenterAPI" -> Api::class.java
                "IMusicClient" -> Client::class.java
                "IMusicPlaybackInfo" -> Info::class.java
                "ECarXApiClient\$Callback" -> Ready::class.java
                else -> throw ClassNotFoundException(name)
            }
        }
        backend.init({ PrefaceMediaCenter.Snapshot(info, true) }, { sent.add(it) }, readiness::add)
        assertEquals(listOf(true), readiness)
        val token = backend.register()!!
        assertTrue(backend.requestPlay(token))
        assertTrue(backend.publish(token, info))
        val api = Api.current
        assertSame(context, api.context)
        val client = api.client!!
        assertTrue(client.onNext()); assertTrue(client.onPrevious())
        assertTrue(client.onPlay()); assertTrue(client.onPause())
        assertEquals(listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS,
            CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE), sent)
        assertFalse(client.onForward())
        assertArrayEquals(intArrayOf(6), client.getMediaSourceTypeList())
        assertEquals(42L, client.getCurrentProgress())
        val published = api.info!!
        assertEquals("Track", published.getTitle())
        assertEquals("Artist", published.getArtist())
        assertEquals("Album", published.getAlbum())
        assertEquals(120L, published.getDuration())
        assertEquals(context.packageName, published.getPackageName())
        assertEquals(6, api.source)
        assertEquals(1, published.getPlaybackStatus())
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(published.getLaunchIntent()).savedIntent.component!!.className)
        assertTrue(backend.publish(token, info.copy(playing = false)))
        assertEquals(0, api.info!!.getPlaybackStatus())
        assertTrue(backend.unregister(token))
        assertSame(token, api.unregistered)
    }

    @Test fun absentFirmwareSdkFailsBeforeRegistration() {
        val backend = PrefaceMediaCenterBackend(RuntimeEnvironment.getApplication()) {
            throw ClassNotFoundException(it)
        }
        try {
            backend.init({ PrefaceMediaCenter.Snapshot() }, { error("command") }, { error("ready") })
            fail("Must retain fallback for absent firmware API")
        } catch (_: ClassNotFoundException) { }
    }

    @Test fun progressTicksDoNotRepublishMetadataAndDynamicTitlesArePreserved() {
        val backend = backend()
        val info = CarPlayNowPlaying(title = "歌曲名称", artist = "歌手", playing = true, elapsedMillis = 500)
        val token = initialize(backend, info)
        assertTrue(backend.publish(token, info))
        assertTrue(backend.publish(token, info.copy(elapsedMillis = 1000)))
        assertTrue(backend.publish(token, info.copy(elapsedMillis = 1000)))
        assertEquals(listOf(500L, 1000L), Api.current.positions)
        assertEquals(1, Api.current.stateUpdates)
        assertEquals(1, Api.current.sourceUpdates)
        assertTrue(backend.publish(token, info.copy(title = "手机传来的动态标题", elapsedMillis = 1500)))
        assertEquals("手机传来的动态标题", Api.current.info!!.getTitle())
        assertEquals(2, Api.current.stateUpdates)
        // There is no independent lyric input: title text must not be guessed to be lyrics.
        assertEquals("", Api.current.info!!.getCurrentLyricSentence())
        assertEquals("", Api.current.info!!.getLyricContent())
        assertEquals(listOf(""), Api.current.lyrics)
        assertTrue(backend.report().contains("lyricInput=not-provided"))
    }

    @Test fun optionalPushFailuresDoNotBreakSongMetadataOrRetryOnEveryTick() {
        val log = mutableListOf<String>()
        val backend = backend(log::add)
        val info = CarPlayNowPlaying(title = "Track", playing = true, elapsedMillis = 0)
        val token = initialize(backend, info)
        Api.current.rejectOptional = true
        assertTrue(backend.publish(token, info))
        assertTrue(backend.publish(token, info.copy(elapsedMillis = 500)))
        assertEquals("Track", Api.current.info!!.getTitle())
        assertEquals(1, Api.current.progressCalls)
        assertEquals(1, Api.current.lyricCalls)
        assertTrue(log.any { it.contains("progress-failed:SecurityException") })
        assertTrue(log.any { it.contains("lyric-clear-failed:SecurityException") })
        assertFalse(log.any { it.contains("Private error") })
        assertTrue(backend.report().contains("progressPush=false"))
    }

    @Test fun resumingTheSourceResendsItsStateAndClearsStaleLyrics() {
        val backend = backend()
        val info = CarPlayNowPlaying(title = "Track", playing = true, elapsedMillis = 300)
        val token = initialize(backend, info)
        backend.publish(token, info)
        assertTrue(backend.requestPlay(token))
        backend.publish(token, info)
        assertEquals(2, Api.current.stateUpdates)
        assertEquals(2, Api.current.sourceUpdates)
        assertEquals(listOf("", ""), Api.current.lyrics)
        assertEquals(listOf(300L, 300L), Api.current.positions)
    }

    @Test fun failedMetadataPublishRetriesBeforeClearingThePreviousLyric() {
        val backend = backend()
        val info = CarPlayNowPlaying(title = "Track", playing = true)
        val token = initialize(backend, info)
        Api.current.acceptMetadata = false
        assertFalse(backend.publish(token, info))
        assertTrue(Api.current.lyrics.isEmpty())
        Api.current.acceptMetadata = true
        assertTrue(backend.publish(token, info))
        assertEquals(listOf(""), Api.current.lyrics)
    }

    @Test fun missingOptionalFirmwareMethodsKeepMusicRegistrationAndMetadataWorking() {
        val backend = backend(apiType = LegacyApi::class.java)
        val info = CarPlayNowPlaying(title = "Track", playing = true, elapsedMillis = 500)
        val token = initialize(backend, info)
        assertTrue(backend.requestPlay(token))
        assertTrue(backend.publish(token, info))
        assertTrue(backend.publish(token, info.copy(elapsedMillis = 1000)))
        assertTrue(backend.report().contains("progressPush=false"))
        assertTrue(backend.report().contains("lyricPush=false"))
    }

    private fun backend(diagnostic: (String) -> Unit = {}, apiType: Class<*> = Api::class.java) =
        PrefaceMediaCenterBackend(RuntimeEnvironment.getApplication(), diagnostic) { name ->
            when (name.substringAfterLast('.')) {
                "MediaCenterAPI" -> apiType
                "IMusicClient" -> Client::class.java
                "IMusicPlaybackInfo" -> Info::class.java
                "ECarXApiClient\$Callback" -> Ready::class.java
                else -> throw ClassNotFoundException(name)
            }
        }

    private fun initialize(backend: PrefaceMediaCenterBackend, info: CarPlayNowPlaying): Any {
        backend.init({ PrefaceMediaCenter.Snapshot(info, true) }, { true }, {})
        return backend.register()!!
    }

    // Local test doubles for the documented signatures; never packaged as vendor classes.
    interface Ready { fun onAPIReady(ready: Boolean) }
    interface Client {
        fun onNext(): Boolean
        fun onPrevious(): Boolean
        fun onPlay(): Boolean
        fun onPause(): Boolean
        fun onForward(): Boolean
        fun getCurrentProgress(): Long
        fun getMediaSourceTypeList(): IntArray
        fun getMusicPlaybackInfo(): Info
    }
    interface Info {
        fun getTitle(): String
        fun getArtist(): String
        fun getAlbum(): String
        fun getDuration(): Long
        fun getLyricContent(): String
        fun getCurrentLyricSentence(): String
        fun getPackageName(): String
        fun getLaunchIntent(): PendingIntent
        fun getPlaybackStatus(): Int
    }
    class Api {
        lateinit var context: Context
        var client: Client? = null
        var info: Info? = null
        var source = -1
        var unregistered: Any? = null
        var stateUpdates = 0
        var sourceUpdates = 0
        var progressCalls = 0
        var lyricCalls = 0
        var rejectOptional = false
        var acceptMetadata = true
        val positions = mutableListOf<Long>()
        val lyrics = mutableListOf<String>()
        fun init(context: Context, callback: Ready) { this.context = context; callback.onAPIReady(true) }
        fun registerMusic(client: Client): Any { this.client = client; return Any() }
        fun requestPlay(token: Any): Boolean = true
        fun updateCurrentSourceType(token: Any, source: Int) { this.source = source; sourceUpdates++ }
        fun updateMusicPlaybackState(token: Any, info: Info): Boolean {
            stateUpdates++; if (acceptMetadata) this.info = info; return acceptMetadata
        }
        fun updateCurrentProgress(token: Any, position: Long) {
            progressCalls++
            if (rejectOptional) throw SecurityException("Private error")
            positions.add(position)
        }
        fun updateCurrentLyric(token: Any, lyric: String) {
            lyricCalls++
            if (rejectOptional) throw SecurityException("Private error")
            lyrics.add(lyric)
        }
        fun unregister(token: Any): Boolean { unregistered = token; return true }
        companion object {
            lateinit var current: Api
            @JvmStatic fun get(context: Context): Api = Api().also { current = it }
        }
    }

    class LegacyApi {
        fun init(context: Context, callback: Ready) { callback.onAPIReady(true) }
        fun registerMusic(client: Client): Any = Any()
        fun requestPlay(token: Any): Boolean = true
        fun updateCurrentSourceType(token: Any, source: Int) {}
        fun updateMusicPlaybackState(token: Any, info: Info): Boolean = true
        fun unregister(token: Any): Boolean = true
        companion object { @JvmStatic fun get(context: Context): LegacyApi = LegacyApi() }
    }
}
