package com.shilapi.xcertplay.vehicle;

import com.preface.carplay.instrument.Preface95PreparationProtocol;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Session-scoped binding to the modified map's actual background renderer. */
public final class PrefaceAmapPreparation implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor IO = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "preface-map-preparation"); thread.setDaemon(true); return thread;
    });
    static { IO.setRemoveOnCancelPolicy(true); }
    private final Context context;
    private final BooleanSupplier current;
    private final Consumer<String> diagnostic;
    interface HostIdentity { boolean matches(Context context) throws PackageManager.NameNotFoundException; }
    private final HostIdentity identity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final IBinder token = new Binder();
    private final long session = Math.max(1, System.nanoTime());
    private final long startedAt = SystemClock.elapsedRealtime();
    private boolean started, bound;
    private volatile boolean closed, pending, confirmed, failed;
    private volatile IBinder remote;
    private volatile boolean protocolAccepted;
    private volatile ScheduledFuture<?> poll;
    private long sequence; // IO executor only
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!live() || failed) { unbind(); return; }
            remote = binder;
            IO.execute(() -> handshake(binder));
        }
        public void onServiceDisconnected(ComponentName name) { fail("disconnected"); }
        public void onBindingDied(ComponentName name) { fail("binding-died"); }
        public void onNullBinding(ComponentName name) { fallback("unsupported-host"); }
    };

    public PrefaceAmapPreparation(Context context, BooleanSupplier current, Consumer<String> diagnostic) {
        this(context, current, diagnostic, candidate -> {
            ApplicationInfo info = candidate.getPackageManager().getApplicationInfo(Preface95PreparationProtocol.HOST_PACKAGE, 0);
            return Preface95PreparationProtocol.verified(candidate, Preface95PreparationProtocol.HOST_PACKAGE,
                    Preface95PreparationProtocol.HOST_CERT, info.uid);
        });
    }

    PrefaceAmapPreparation(Context context, BooleanSupplier current, Consumer<String> diagnostic, HostIdentity identity) {
        this.context = context.getApplicationContext(); this.current = current; this.diagnostic = diagnostic;
        this.identity = identity;
    }

    private boolean live() { return !closed && current.getAsBoolean(); }

    public void start() { // main thread
        if (started || !live()) return;
        started = true;
        try {
            if (!identity.matches(context)) { fallback("host-identity-unmatched"); return; }
            pending = true;
            Intent intent = new Intent(Preface95PreparationProtocol.ACTION).setComponent(new ComponentName(
                    Preface95PreparationProtocol.HOST_PACKAGE, Preface95PreparationProtocol.HOST_SERVICE));
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) fallback("host-unavailable");
        } catch (PackageManager.NameNotFoundException error) { fallback("map-not-installed"); }
        catch (RuntimeException error) { fail("bind-failed"); }
    }

    private void handshake(IBinder binder) {
        if (!live()) { close(); return; }
        if (remote != binder) return;
        try {
            if (!Preface95PreparationProtocol.DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
                remote = null;
                fallback("original-map-protocol"); return;
            }
            protocolAccepted = true;
            if (!live()) { close(); return; }
            transact(binder, Preface95PreparationProtocol.OPEN);
            if (!live()) { close(); return; }
            poll = IO.scheduleWithFixedDelay(() -> {
                if (!live()) { close(); return; }
                try {
                    int result = transact(binder, Preface95PreparationProtocol.POLL);
                    if (result == Preface95PreparationProtocol.FAILED) throw new IllegalStateException("host-failed");
                    boolean wasConfirmed = confirmed;
                    confirmed = live() && result == Preface95PreparationProtocol.BUFFER_CONFIRMED;
                    if (confirmed && !wasConfirmed) diagnostic.accept("instrument background idle buffer confirmed; OEM readiness/display acknowledgement unconfirmed");
                    if (!confirmed && SystemClock.elapsedRealtime() - startedAt >= Preface95PreparationProtocol.INITIALIZE_MS)
                        throw new IllegalStateException("initialize-timeout");
                } catch (Exception error) { fail("host-preparation-failed"); }
            }, 0, 500, TimeUnit.MILLISECONDS);
        } catch (Exception error) { fail("handshake-failed"); }
    }

    private int transact(IBinder binder, int code) throws android.os.RemoteException {
        Parcel request = Parcel.obtain(), response = Parcel.obtain();
        try {
            request.writeInterfaceToken(Preface95PreparationProtocol.DESCRIPTOR);
            request.writeInt(Preface95PreparationProtocol.VERSION);
            request.writeStrongBinder(token); request.writeLong(session); request.writeLong(++sequence);
            if (!binder.transact(code, request, response, 0)) throw new IllegalStateException("unsupported-transaction");
            response.readException();
            if (response.readInt() != Preface95PreparationProtocol.VERSION) throw new IllegalStateException("protocol-version");
            int state = response.readInt();
            if (response.dataAvail() != 0 || state < Preface95PreparationProtocol.FAILED || state > Preface95PreparationProtocol.BUFFER_CONFIRMED)
                throw new IllegalStateException("invalid-state");
            return state;
        } finally { request.recycle(); response.recycle(); }
    }

    private void fallback(String reason) {
        if (closed) return;
        pending = false; confirmed = false;
        main.post(this::unbind);
        diagnostic.accept("instrument background preparation unavailable reason=" + reason + "; existing OEM preparation still required");
    }

    private void fail(String reason) {
        if (closed || failed) return;
        failed = true; confirmed = false;
        diagnostic.accept("instrument background preparation failed reason=" + reason + "; retry=next-session");
        closeTransport();
    }

    public boolean ready() {
        if (!live()) return false;
        if (failed) throw new IllegalStateException("instrument-background-preparation-failed");
        return !pending || confirmed;
    }
    public long timeoutNs() { return pending ? TimeUnit.MILLISECONDS.toNanos(Preface95PreparationProtocol.INITIALIZE_MS) : 5_000_000_000L; }

    private void releaseRemote(IBinder binder) {
        ScheduledFuture<?> task = poll;
        if (task != null) task.cancel(false);
        if (remote != binder) return;
        remote = null;
        if (protocolAccepted) {
            try { transact(binder, Preface95PreparationProtocol.CLOSE); } catch (Exception ignored) { }
        }
    }

    private void closeTransport() {
        ScheduledFuture<?> task = poll;
        if (task != null) task.cancel(false);
        IBinder binder = remote;
        if (binder != null) IO.execute(() -> releaseRemote(binder));
        main.post(this::unbind);
    }

    private void unbind() {
        if (!bound) return;
        bound = false;
        try { context.unbindService(connection); } catch (RuntimeException ignored) { }
    }

    @Override public void close() {
        if (closed) return;
        closed = true; confirmed = false;
        closeTransport();
    }
}
