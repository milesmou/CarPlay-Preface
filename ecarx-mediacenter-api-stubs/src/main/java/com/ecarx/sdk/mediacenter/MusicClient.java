package com.ecarx.sdk.mediacenter;

/** Handwritten compile-only signatures. The installed E02 SDK supplies all runtime behavior. */
public class MusicClient {
    public MusicPlaybackInfo getMusicPlaybackInfo() { return null; }
    public long getCurrentProgress() { return 0L; }
    public int getCurrentSourceType() { return -1; }
    public int[] getMediaSourceTypeList() { return null; }
    public boolean onNext() { return false; }
    public boolean onPrevious() { return false; }
    public boolean onPlay() { return false; }
    public boolean onPause() { return false; }
}
