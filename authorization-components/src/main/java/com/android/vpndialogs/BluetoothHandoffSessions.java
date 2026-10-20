package com.android.vpndialogs;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.UserManager;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Authenticated A2DP-only leases. No general disconnect endpoint, HFP or adapter writes. */
final class BluetoothHandoffSessions {
    interface CallerCheck { void check(int uid) throws Exception; }
    private static final int SINK = 11, REPLY = 3;
    private final Context context;
    private final Handler main;
    private final CallerCheck caller;
    private final Runnable retainService, idleService;
    private final Map<String, Owner> owners = new HashMap<>();
    private final Map<String, Entry> entries = new HashMap<>();

    BluetoothHandoffSessions(Context context, Handler main, CallerCheck caller, Runnable retainService, Runnable idleService) {
        this.context = context; this.main = main; this.caller = caller;
        this.retainService = retainService; this.idleService = idleService;
    }

    void acquire(int uid, String nonce, String address, Messenger reply) throws Exception {
        String key = uid + ":" + nonce;
        if (owners.containsKey(key)) return;
        String peer = address.toUpperCase(java.util.Locale.US);
        // Add the replacement before releasing the previous owner; no reconnect between generations.
        Entry entry = entries.get(peer);
        if (entry == null) { entry = new Entry(peer, uid); entries.put(peer, entry); }
        Owner owner = new Owner(key, nonce, uid, reply, entry);
        try { reply.getBinder().linkToDeath(owner, 0); }
        catch (Exception failure) {
            if (entry.clients.isEmpty() && entry.lease == null) entry.close();
            throw failure;
        }
        owners.put(key, owner);
        entry.clients.put(key, owner);
        for (Owner old : new ArrayList<>(owners.values())) if (old.uid == uid && old != owner) old.close();
        entry.start(owner);
    }

    void cancel(int uid, String nonce, Messenger reply) {
        Owner owner = owners.get(uid + ":" + nonce);
        if (owner != null && owner.reply.getBinder().equals(reply.getBinder())) owner.close();
    }

    void cancelAll() { for (Owner owner : new ArrayList<>(owners.values())) owner.close(); }
    boolean hasWork() { return !entries.isEmpty(); }

    private String identity(int uid) throws Exception {
        caller.check(uid);
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length == 0) throw new SecurityException("caller missing");
        java.util.Arrays.sort(packages);
        StringBuilder result = new StringBuilder();
        for (String name : packages) {
            PackageInfo info = context.getPackageManager().getPackageInfo(name, 0);
            result.append(name).append(':').append(info.getLongVersionCode()).append(';');
        }
        return result.toString();
    }

    private final class Owner implements IBinder.DeathRecipient {
        final String key, nonce;
        final int uid;
        final Messenger reply;
        final Entry entry;
        boolean active = true;
        final Runnable timeout = () -> result(false, "selected A2DP handoff timed out");
        Owner(String key, String nonce, int uid, Messenger reply, Entry entry) {
            this.key = key; this.nonce = nonce; this.uid = uid; this.reply = reply; this.entry = entry;
        }
        void result(boolean confirmed, String reason) {
            if (!active || owners.get(key) != this) return;
            main.removeCallbacks(timeout);
            Message message = Message.obtain(null, REPLY);
            Bundle data = new Bundle(); data.putString("nonce", nonce);
            data.putBoolean("accepted", confirmed); data.putString("reason", reason); message.setData(data);
            try { reply.send(message); } catch (Exception failure) { close(); return; }
            if (!confirmed) close();
        }
        @Override public void binderDied() { main.post(this::close); }
        void close() {
            if (!active) return;
            active = false;
            main.removeCallbacks(timeout);
            owners.remove(key, this);
            try { reply.getBinder().unlinkToDeath(this, 0); } catch (Exception ignored) { }
            entry.clients.remove(key);
            if (!entry.closed && entry.lease != null) {
                retainService.run();
                entry.lease.release(key);
            } else if (entry.clients.isEmpty()) entry.close();
        }
    }

    private final class Entry implements BluetoothProfile.ServiceListener {
        final String peer, callerIdentity;
        final int uid;
        final BluetoothAdapter adapter;
        final Map<String, Owner> clients = new HashMap<>();
        BluetoothProfile profile;
        BluetoothHandoffLease lease;
        boolean requested, closed;
        Entry(String peer, int uid) throws Exception {
            this.peer = peer; this.uid = uid; callerIdentity = identity(uid);
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            adapter = manager == null ? null : manager.getAdapter();
        }
        void start(Owner owner) {
            main.postDelayed(owner.timeout, 5_000L);
            if (lease != null) { lease.acquire(owner.key, owner::result); return; }
            if (requested) return;
            requested = true;
            try {
                if (adapter == null || !adapter.isEnabled() || !adapter.getProfileProxy(context, this, SINK))
                    fail("platform sink profile unavailable");
            } catch (Exception failure) { fail("platform sink profile unavailable"); }
        }
        @Override public void onServiceConnected(int id, BluetoothProfile connected) {
            if (Looper.myLooper() != main.getLooper()) { main.post(() -> onServiceConnected(id, connected)); return; }
            if (id != SINK) return;
            if (closed || entries.get(peer) != this || clients.isEmpty()) {
                if (adapter != null) try { adapter.closeProfileProxy(SINK, connected); } catch (Exception ignored) { }
                return;
            }
            profile = connected;
            try {
                Method connect = connected.getClass().getMethod("connect", BluetoothDevice.class);
                Method disconnect = connected.getClass().getMethod("disconnect", BluetoothDevice.class);
                if (connect.getReturnType() != boolean.class || disconnect.getReturnType() != boolean.class)
                    throw new NoSuchMethodException("A2DP operation result");
                BluetoothDevice phone = adapter.getRemoteDevice(peer);
                lease = new BluetoothHandoffLease(new BluetoothHandoffLease.Backend() {
                    @Override public boolean allowed(boolean changing) {
                        try {
                            if (!callerIdentity.equals(identity(uid)) || !adapter.isEnabled() ||
                                phone.getBondState() != BluetoothDevice.BOND_BONDED) return false;
                            UserManager users = context.getSystemService(UserManager.class);
                            if (users == null || users.hasUserRestriction(UserManager.DISALLOW_BLUETOOTH) ||
                                users.hasUserRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH)) return false;
                            for (BluetoothDevice other : connected.getDevicesMatchingConnectionStates(new int[]{1, 2, 3}))
                                if (!peer.equalsIgnoreCase(other.getAddress())) return false;
                            AudioManager audio = context.getSystemService(AudioManager.class);
                            return !changing || audio != null && audio.getMode() == AudioManager.MODE_NORMAL;
                        } catch (Exception failure) { return false; }
                    }
                    @Override public int state() { return connected.getConnectionState(phone); }
                    @Override public boolean disconnect() { return invoke(disconnect, phone); }
                    @Override public boolean connect() { return invoke(connect, phone); }
                    private boolean invoke(Method method, BluetoothDevice target) {
                        try { return Boolean.TRUE.equals(method.invoke(connected, target)); }
                        catch (Exception failure) { throw new IllegalStateException("A2DP operation unavailable"); }
                    }
                }, new BluetoothHandoffLease.Scheduler() {
                    @Override public long now() { return android.os.SystemClock.elapsedRealtime(); }
                    @Override public void schedule(Runnable task, long delay) { main.postDelayed(task, delay); }
                    @Override public void cancel(Runnable task) { main.removeCallbacks(task); }
                }, this::close);
                for (Owner owner : new ArrayList<>(clients.values()))
                    if (owner.active) lease.acquire(owner.key, owner::result);
            } catch (Exception failure) { fail("platform A2DP methods unavailable: " + failure.getClass().getSimpleName()); }
        }
        @Override public void onServiceDisconnected(int id) {
            if (Looper.myLooper() != main.getLooper()) { main.post(() -> onServiceDisconnected(id)); return; }
            if (id == SINK) fail("platform sink service disconnected");
        }
        void fail(String reason) {
            for (Owner owner : new ArrayList<>(clients.values())) owner.result(false, reason);
            if (lease == null) close();
        }
        void close() {
            if (closed) return;
            closed = true;
            entries.remove(peer, this);
            for (Owner owner : new ArrayList<>(clients.values())) owner.result(false, "platform handoff ended");
            if (profile != null && adapter != null) try { adapter.closeProfileProxy(SINK, profile); } catch (Exception ignored) { }
            profile = null;
            if (entries.isEmpty()) idleService.run();
            Log.i("CarPlay-A2DP", "session lease released; restoration is limited to its own disconnection");
        }
    }
}
