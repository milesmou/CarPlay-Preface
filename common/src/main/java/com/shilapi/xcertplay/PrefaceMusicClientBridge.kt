package com.shilapi.xcertplay

import android.app.PendingIntent
import com.ecarx.sdk.mediacenter.MusicClient
import com.ecarx.sdk.mediacenter.MusicPlaybackInfo
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Loaded only after the installed SDK's concrete class signatures have been verified. */
internal class PrefaceMusicClientBridge(
    private val snapshot: () -> PrefaceMediaCenter.Snapshot,
    command: (Int) -> Boolean,
    private val packageName: String,
    private val launch: () -> PendingIntent,
) {
    @Volatile private var current = snapshot().info
    @Volatile private var disconnected = false

    // The SDK may retain this object. Linkco mutates one playback record for the whole process.
    private val info = object : MusicPlaybackInfo() {
        override fun getTitle() = current.title ?: "CarPlay"
        override fun getArtist() = current.artist ?: "carplay"
        override fun getAlbum() = current.album ?: ""
        override fun getDuration() = current.durationMillis ?: 0L
        override fun getPlaybackStatus() = if (!disconnected && current.playing) 1 else 0
        override fun getSourceType() = if (disconnected) -1 else SOURCE_TYPE
        override fun getPackageName(): String = this@PrefaceMusicClientBridge.packageName
        override fun getAppName() = "CarPlay"
        override fun getLaunchIntent() = launch()
        override fun getAppIcon(): String? = null
        override fun getArtwork() = android.net.Uri.parse("carplay")
        override fun getNextArtwork() = android.net.Uri.parse("carplay")
        override fun getPreviousArtwork() = android.net.Uri.parse("carplay")
        override fun getPlayingMediaListType() = 0
        override fun isCollected() = false
        override fun isSupportCollect() = false
        override fun isSupportDownload() = false
        override fun isSupportLoopModeSwitch() = false
        override fun isSupportVrCtrlPlayStatus() = false
        override fun getCurrentLyricSentence() = ""
        override fun getLyricContent() = ""
    }

    val client: Any = object : MusicClient() {
        override fun onNext() = command(CarPlayMediaButton.NEXT)
        override fun onPrevious() = command(CarPlayMediaButton.PREVIOUS)
        override fun onPlay() = command(CarPlayMediaButton.PLAY)
        override fun onPause() = command(CarPlayMediaButton.PAUSE)
        override fun getMusicPlaybackInfo(): MusicPlaybackInfo {
            current = snapshot().info
            return info
        }
        override fun getCurrentProgress() = snapshot().info.elapsedMillis ?: 0L
        override fun getCurrentSourceType() = info.sourceType
        override fun getMediaSourceTypeList() = intArrayOf(SOURCE_TYPE)
    }

    fun playbackInfo(value: CarPlayNowPlaying): Any {
        current = value
        disconnected = false
        return info
    }

    fun disconnect() {
        disconnected = true
        current = current.copy(playing = false)
    }

    private companion object { const val SOURCE_TYPE = 6 }
}
