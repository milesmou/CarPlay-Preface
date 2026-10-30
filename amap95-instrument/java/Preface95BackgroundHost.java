import android.app.Application;
import com.preface.carplay.instrument.Preface95PreparationProtocol;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/** Uses the existing JSON Service; its ordinary OEM Binder remains unchanged. */
public final class Preface95BackgroundHost extends Binder {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean initialized;
    private static Preface95BackgroundHost host; // main thread
    private static volatile long quietUntil;
    private static final java.util.concurrent.atomic.AtomicInteger releasing = new java.util.concurrent.atomic.AtomicInteger();
    private final Service service;
    private volatile Lease lease;
    private volatile boolean destroyed;
    private long lastSession; // Binder lock; closed sessions cannot reopen.

    private final class Lease implements IBinder.DeathRecipient {
        final IBinder token;
        final int uid;
        final long session;
        final long started = SystemClock.elapsedRealtime();
        volatile long heartbeat = started;
        long sequence;
        volatile int status = Preface95PreparationProtocol.WAITING;
        boolean backgroundRequested; // main thread
        String stage = "engine-initialization"; // Fixed labels only, no user data.
        Lease(IBinder token, int uid, long session, long sequence) {
            this.token = token; this.uid = uid; this.session = session; this.sequence = sequence;
        }
        public void binderDied() { invalidate(this, "client-died"); }
    }

    private Preface95BackgroundHost(Service service) {
        this.service = service;
        attachInterface(null, Preface95PreparationProtocol.DESCRIPTOR);
    }

    public static IBinder bind(Service service, Intent intent) {
        if (intent == null || !Preface95PreparationProtocol.ACTION.equals(intent.getAction())) return null;
        if (!Preface95PreparationProtocol.HOST_PACKAGE.equals(Application.getProcessName()))
            throw new IllegalStateException("BACKGROUND_HOST_NON_MAIN_PROCESS");
        if (host == null || host.destroyed) host = new Preface95BackgroundHost(service);
        if (host.service != service) throw new IllegalStateException("BACKGROUND_HOST_SERVICE_CHANGED");
        return host;
    }

    /** Called after the actual g7.b() initialization callback and its original body. */
    public static void engineInitialized() { initialized = true; }

    public static void unbind(Service service, Intent intent) {
        if (intent != null && Preface95PreparationProtocol.ACTION.equals(intent.getAction()) &&
                host != null && host.service == service) host.invalidate(host.lease, "client-unbound");
    }

    public static void destroy(Service service) {
        if (host == null || host.service != service) return;
        host.destroyed = true;
        host.invalidate(host.lease, "service-destroyed");
        host = null;
    }

    public static void localNavigationStarted() {
        if (host != null) host.invalidate(host.lease, "local-navigation");
    }

    public static void windowStopped() {
        if (host != null) host.invalidate(host.lease, "window-stopped");
    }

    private boolean live(Lease value) {
        return !destroyed && lease == value && value.token.isBinderAlive() &&
                SystemClock.elapsedRealtime() - value.heartbeat <= Preface95PreparationProtocol.HEARTBEAT_MS;
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
        if (code < Preface95PreparationProtocol.OPEN || code > Preface95PreparationProtocol.CLOSE) return false;
        data.enforceInterface(Preface95PreparationProtocol.DESCRIPTOR);
        if (flags != 0 || reply == null || data.dataAvail() > 256) throw new SecurityException("Invalid preparation transaction");
        int uid = Binder.getCallingUid();
        if (!Preface95PreparationProtocol.verified(service, Preface95PreparationProtocol.CLIENT_PACKAGE,
                Preface95PreparationProtocol.CLIENT_CERT, uid)) throw new SecurityException("Preparation caller rejected");
        int version = data.readInt();
        IBinder token = data.readStrongBinder();
        long session = data.readLong(), sequence = data.readLong();
        if (version != Preface95PreparationProtocol.VERSION || token == null || !token.isBinderAlive() ||
                session <= 0 || sequence <= 0 || data.dataAvail() != 0)
            throw new SecurityException("Invalid preparation identity");
        int result;
        synchronized (this) {
            Lease current = lease;
            if (code == Preface95PreparationProtocol.OPEN) {
                if (destroyed || current != null) throw new IllegalStateException("Preparation already leased");
                if (session <= lastSession) throw new SecurityException("Retired preparation session");
                Lease opened = new Lease(token, uid, session, sequence);
                token.linkToDeath(opened, 0);
                lease = opened;
                lastSession = session;
                if (!token.isBinderAlive()) { lease = null; token.unlinkToDeath(opened, 0); }
                else MAIN.post(new Runnable() { public void run() { advance(opened); } });
                result = Preface95PreparationProtocol.WAITING;
            } else {
                if (current == null || current.uid != uid || current.token != token || current.session != session ||
                        sequence <= current.sequence) throw new SecurityException("Stale preparation lease");
                current.sequence = sequence;
                // Expired heartbeats cannot revive a stale lease.
                if (!live(current)) {
                    invalidate(current, "heartbeat-expired");
                    result = Preface95PreparationProtocol.FAILED;
                } else if (code == Preface95PreparationProtocol.CLOSE) {
                    invalidate(current, "client-closed");
                    result = Preface95PreparationProtocol.WAITING;
                } else {
                    current.heartbeat = SystemClock.elapsedRealtime();
                    result = current.status;
                }
            }
        }
        reply.writeNoException();
        reply.writeInt(Preface95PreparationProtocol.VERSION);
        reply.writeInt(result);
        return true;
    }

    private void advance(Lease current) {
        if (!live(current)) { invalidate(current, "lease-ended"); return; }
        try {
            if (initialized && releasing.get() == 0 && SystemClock.elapsedRealtime() >= quietUntil) {
                current.stage = "background-worker";
                if (!current.backgroundRequested) {
                    current.backgroundRequested = true;
                    a7.c().h(service); // Existing background worker, no Activity launch.
                }
                if (a7.c().d()) { // Actual worker completion; requesting startup is insufficient.
                    current.stage = "surface-readback";
                    Preface95NativeHints.beginBackground(current, new java.util.function.BooleanSupplier() {
                        public boolean getAsBoolean() { return live(current); }
                    });
                    current.status = Preface95NativeHints.backgroundStatus(current);
                    if (current.status == Preface95PreparationProtocol.FAILED)
                        throw new IllegalStateException("BACKGROUND_SURFACE_FAILED");
                }
            }
            if (current.status != Preface95PreparationProtocol.BUFFER_CONFIRMED &&
                    SystemClock.elapsedRealtime() - current.started >= Preface95PreparationProtocol.INITIALIZE_MS)
                throw new IllegalStateException("BACKGROUND_INITIALIZE_TIMEOUT");
            MAIN.postDelayed(new Runnable() { public void run() { advance(current); } }, 250);
        } catch (RuntimeException | LinkageError error) {
            String message = error.getMessage();
            String reason = "BACKGROUND_INITIALIZE_TIMEOUT".equals(message) || "BACKGROUND_SURFACE_FAILED".equals(message) ||
                    "LOCAL_NAVIGATION_ACTIVE".equals(message) || "OTHER_WINDOW_1001_ACTIVE".equals(message)
                    ? message : "api-failure";
            Log.e(Preface95NativeHints.TAG, "BACKGROUND_PREPARATION_FAILED stage=" + current.stage +
                    " reason=" + reason + " type=" + error.getClass().getSimpleName());
            invalidate(current, "preparation-failed");
        }
    }

    private void invalidate(Lease current, String reason) {
        if (current == null) return;
        synchronized (this) {
            if (lease != current) return;
            lease = null; // Makes in-flight PixelCopy confirmation fail immediately.
            current.status = Preface95PreparationProtocol.FAILED;
            releasing.incrementAndGet();
        }
        current.token.unlinkToDeath(current, 0);
        Runnable release = new Runnable() { public void run() {
            try {
                Preface95NativeHints.endBackground(current);
                Log.i(Preface95NativeHints.TAG, "BACKGROUND_PREPARATION_RELEASED reason=" + reason);
            } finally {
                quietUntil = SystemClock.elapsedRealtime() + Preface95PreparationProtocol.DRAIN_MS;
                releasing.decrementAndGet();
            }
        } };
        if (Looper.myLooper() == Looper.getMainLooper()) release.run(); else MAIN.post(release);
    }
}
