package com.shilapi.xcertplay

import android.app.PendingIntent
import com.ecarx.sdk.mediacenter.MusicClient
import com.ecarx.sdk.mediacenter.MusicPlaybackInfo
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Loaded only after the installed SDK's concrete class signatures have been verified. */
internal class PrefaceMusicClientBridge(
    snapshot: () -> PrefaceMediaCenter.Snapshot,
    command: (Int) -> Boolean,
    private val packageName: String,
    private val launch: () -> PendingIntent,
) {
    private fun info(value: CarPlayNowPlaying): MusicPlaybackInfo = object : MusicPlaybackInfo() {
        override fun getTitle() = value.title ?: "CarPlay"
        override fun getArtist() = value.artist ?: ""
        override fun getAlbum() = value.album ?: ""
        override fun getDuration() = value.durationMillis ?: 0L
        override fun getPlaybackStatus() = if (value.playing) 1 else 0
        override fun getSourceType() = SOURCE_TYPE
        override fun getPackageName(): String = this@PrefaceMusicClientBridge.packageName
        override fun getAppName() = "CarPlay"
        override fun getLaunchIntent() = launch()
        override fun getCurrentLyricSentence() = ""
        override fun getLyricContent() = ""
    }

    val client: Any = object : MusicClient() {
        override fun onNext() = command(CarPlayMediaButton.NEXT)
        override fun onPrevious() = command(CarPlayMediaButton.PREVIOUS)
        override fun onPlay() = command(CarPlayMediaButton.PLAY)
        override fun onPause() = command(CarPlayMediaButton.PAUSE)
        override fun getMusicPlaybackInfo() = info(snapshot().info)
        override fun getCurrentProgress() = snapshot().info.elapsedMillis ?: 0L
        override fun getCurrentSourceType() = SOURCE_TYPE
        override fun getMediaSourceTypeList() = intArrayOf(SOURCE_TYPE)
    }

    fun playbackInfo(value: CarPlayNowPlaying): Any = info(value)

    private companion object { const val SOURCE_TYPE = 6 }
}
