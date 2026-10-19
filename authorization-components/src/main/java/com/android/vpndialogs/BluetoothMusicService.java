package com.android.vpndialogs;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Narrow platform-signed Android 9 bridge; no disconnect, pairing, HFP, or global setting calls. */
public final class BluetoothMusicService extends Service {
    private static final String ACTION = "com.android.vpndialogs.action.CONNECT_BLUETOOTH_MUSIC";
    private static final int CONNECT = 1, CANCEL = 2, REPLY = 3, A2DP_SINK = 11;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Request> requests = new HashMap<>();
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private boolean bound;

    @Override public IBinder onBind(Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return null;
        bound = true;
        return messenger.getBinder();
    }

    @Override public boolean onUnbind(Intent intent) { cancelAll(); bound = false; return false; }
    @Override public void onDestroy() { cancelAll(); bound = false; super.onDestroy(); }
    private void cancelAll() { for (Request request : new ArrayList<>(requests.values())) request.close(); }

    private boolean receive(Message message) {
        if (!bound || message.replyTo == null) return true;
        try {
            checkCaller(message.sendingUid);
            Bundle data = message.getData();
            String nonce = data.getString("nonce");
            if (nonce == null || nonce.length() != 36) return true;
            String key = message.sendingUid + ":" + nonce;
            if (message.what == CANCEL) {
                Request request = requests.get(key);
                if (request != null && request.reply.getBinder().equals(message.replyTo.getBinder())) request.close();
                return true;
            }
            if (message.what != CONNECT || requests.containsKey(key)) return true;
            String address = data.getString("address");
            if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) return true;
            // Each caller has at most one outstanding operation. A replaced client cannot keep a late callback alive.
            for (Request previous : new ArrayList<>(requests.values())) if (previous.uid == message.sendingUid) previous.close();
            Request request = new Request(key, nonce, address, message.sendingUid, message.replyTo);
            requests.put(key, request);
            request.start();
        } catch (Exception ignored) { /* Unauthenticated messages neither operate Bluetooth nor disclose state. */ }
        return true;
    }

    @SuppressWarnings("deprecation")
    private void checkCaller(int uid) throws Exception {
        PackageManager pm = getPackageManager();
        ComponentIdentity.requireSystemIdentity(this);
        if (Build.VERSION.SDK_INT != 28 || !UserHandle.getUserHandleForUid(uid).equals(Process.myUserHandle()) ||
            !Android9Backend.PLATFORM_CERT.equals(Android9Backend.certificate(pm.getPackageInfo("android", PackageManager.GET_SIGNATURES))) ||
            !Android9Backend.PLATFORM_CERT.equals(Android9Backend.certificate(pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES)))) {
            throw new SecurityException("platform mismatch");
        }
        for (String permission : new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN})
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Bluetooth permission missing");
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) throw new SecurityException("caller missing");
        for (String name : packages) {
            PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES);
            if (!Android9Backend.supportedPackage(name) || info.applicationInfo.uid != uid || !info.applicationInfo.enabled ||
                !Android9Backend.CARPLAY_CERT.equals(Android9Backend.certificate(info))) throw new SecurityException("caller identity mismatch");
        }
    }

    private final class Request implements BluetoothProfile.ServiceListener, IBinder.DeathRecipient {
        final String key, nonce, address;
        final int uid;
        final Messenger reply;
        final BluetoothAdapter adapter;
        BluetoothProfile profile;
        boolean active = true, linked;
        final Runnable timeout = () -> finish(false, "platform profile timed out");
        Request(String key, String nonce, String address, int uid, Messenger reply) {
            this.key = key; this.nonce = nonce; this.address = address; this.uid = uid; this.reply = reply;
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            adapter = manager == null ? null : manager.getAdapter();
        }
        boolean current() { return active && bound && requests.get(key) == this && reply.getBinder().isBinderAlive(); }
        void start() {
            try {
                reply.getBinder().linkToDeath(this, 0); linked = true;
                if (adapter == null || !adapter.isEnabled() || adapter.getRemoteDevice(address).getBondState() != BluetoothDevice.BOND_BONDED)
                    throw new IllegalStateException("paired phone unavailable");
                main.postDelayed(timeout, 4_000L);
                if (!adapter.getProfileProxy(BluetoothMusicService.this, this, A2DP_SINK)) finish(false, "sink profile unavailable");
            } catch (Exception failure) { finish(false, "platform request unavailable"); }
        }
        @Override public void onServiceConnected(int id, BluetoothProfile connected) {
            if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> onServiceConnected(id, connected)); return; }
            if (id != A2DP_SINK) return;
            if (!current()) { if (adapter != null) try { adapter.closeProfileProxy(A2DP_SINK, connected); } catch (Exception ignored) { } return; }
            profile = connected;
            try {
                checkCaller(uid);
                BluetoothDevice phone = adapter.getRemoteDevice(address);
                AudioManager audio = getSystemService(AudioManager.class);
                if (!adapter.isEnabled() || phone.getBondState() != BluetoothDevice.BOND_BONDED ||
                    audio == null || audio.getMode() == AudioManager.MODE_IN_CALL || audio.getMode() == AudioManager.MODE_IN_COMMUNICATION)
                    throw new IllegalStateException("music connection blocked");
                UserManager users = getSystemService(UserManager.class);
                if (users == null || users.hasUserRestriction(UserManager.DISALLOW_BLUETOOTH) ||
                    users.hasUserRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH)) throw new SecurityException("Bluetooth policy blocked");
                for (BluetoothDevice device : connected.getDevicesMatchingConnectionStates(new int[]{
                    BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTING}))
                    if (!address.equalsIgnoreCase(device.getAddress())) throw new IllegalStateException("another phone owns music");
                int state = connected.getConnectionState(phone);
                if (state == BluetoothProfile.STATE_CONNECTED) { finish(true, "music already connected"); return; }
                if (state != BluetoothProfile.STATE_DISCONNECTED) { finish(false, "music connection is changing"); return; }
                if (!current()) return;
                Object accepted = connected.getClass().getMethod("connect", BluetoothDevice.class).invoke(connected, phone);
                finish(Boolean.TRUE.equals(accepted), "music connection requested; connection must be confirmed");
            } catch (Exception failure) { finish(false, "platform music connection unavailable: " + failure.getClass().getSimpleName()); }
        }
        @Override public void onServiceDisconnected(int id) {
            if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> onServiceDisconnected(id)); return; }
            if (id == A2DP_SINK) { profile = null; finish(false, "sink profile disconnected"); }
        }
        @Override public void binderDied() { main.post(this::close); }
        void finish(boolean accepted, String reason) {
            if (!current()) { close(); return; }
            Message result = Message.obtain(null, REPLY);
            Bundle data = new Bundle(); data.putString("nonce", nonce); data.putBoolean("accepted", accepted); data.putString("reason", reason);
            result.setData(data);
            try { reply.send(result); } catch (Exception ignored) { }
            close();
        }
        void close() {
            if (!active) return;
            active = false; requests.remove(key, this); main.removeCallbacks(timeout);
            if (linked) { try { reply.getBinder().unlinkToDeath(this, 0); } catch (Exception ignored) { } linked = false; }
            BluetoothProfile closing = profile; profile = null;
            if (closing != null && adapter != null) try { adapter.closeProfileProxy(A2DP_SINK, closing); } catch (Exception ignored) { }
        }
    }
}
