package com.preface.carplay.instrument;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.security.MessageDigest;

/** Shared wire contract. No navigation payload, arbitrary commands or permissions. */
public final class Preface95PreparationProtocol {
    public static final String ACTION = "com.preface.carplay.action.INSTRUMENT_PREPARATION";
    public static final String DESCRIPTOR = "com.preface.carplay.instrument.Preparation.v1";
    public static final String HOST_PACKAGE = "com.autonavi.amapauto";
    public static final String HOST_SERVICE = "com.autonavi.amapauto.protocol.service.JsonProtocolService";
    public static final String CLIENT_PACKAGE = "com.preface.carplay";
    public static final String HOST_CERT = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";
    public static final String CLIENT_CERT = "75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8";
    public static final int VERSION = 1, OPEN = 1, POLL = 2, CLOSE = 3;
    public static final int FAILED = -1, WAITING = 0, BUFFER_CONFIRMED = 1;
    public static final long HEARTBEAT_MS = 3000, INITIALIZE_MS = 20000, DRAIN_MS = 550;

    @SuppressWarnings("deprecation")
    public static boolean verified(Context context, String name, String certificate, int uid) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES);
            if (info.applicationInfo == null || !info.applicationInfo.enabled ||
                    info.applicationInfo.uid != uid || info.signatures == null || info.signatures.length != 1) return false;
            String[] names = pm.getPackagesForUid(uid);
            if (names == null || names.length != 1 || !name.equals(names[0])) return false;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return certificate.equals(hex.toString());
        } catch (Exception error) { return false; }
    }
    private Preface95PreparationProtocol() { }
}
