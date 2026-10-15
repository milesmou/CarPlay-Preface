package com.ecarx.sdk.mediacenter;

import android.app.PendingIntent;

/** Handwritten compile-only signatures, not a replacement implementation of the vendor SDK. */
public class MusicPlaybackInfo {
    public String getAppIcon() { return null; }
    public android.net.Uri getArtwork() { return null; }
    public android.net.Uri getNextArtwork() { return null; }
    public android.net.Uri getPreviousArtwork() { return null; }
    public int getPlayingMediaListType() { return 0; }
    public boolean isCollected() { return false; }
    public boolean isSupportCollect() { return false; }
    public boolean isSupportDownload() { return false; }
    public boolean isSupportLoopModeSwitch() { return false; }
    public boolean isSupportVrCtrlPlayStatus() { return false; }
    public String getTitle() { return null; }
    public String getArtist() { return null; }
    public String getAlbum() { return null; }
    public long getDuration() { return 0L; }
    public int getPlaybackStatus() { return 0; }
    public int getSourceType() { return -1; }
    public PendingIntent getLaunchIntent() { return null; }
    public String getPackageName() { return null; }
    public String getAppName() { return null; }
    public String getCurrentLyricSentence() { return null; }
    public String getLyricContent() { return null; }
}
