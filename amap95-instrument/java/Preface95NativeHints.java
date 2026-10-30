import android.content.Context;
import android.app.Activity;
import android.app.Application;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import com.autonavi.amapauto.jni.protocol.data.GuideInfoProtocolData;
import com.autonavi.extscreen.dto.PresentationConfig;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-process hooks for the pinned public 9.5 DEX. No external IPC or APK. */
public final class Preface95NativeHints {
    static final String TAG = "Preface95Hints";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Preface95NativeHints instance;
    private static final AtomicLong guideEpoch = new AtomicLong();
    private static final AtomicLong guideSequence = new AtomicLong();
    private static final AtomicBoolean firstMainFrameLogged = new AtomicBoolean();
    private static final AtomicBoolean firstGuidanceLogged = new AtomicBoolean();
    private static long appliedGuide;
    private final a20 manager;
    private final DisplayManager displays;
    private Preface95HintScreen screen;
    private boolean failed;
    private boolean closed;
    private boolean waitingForDisplay;
    private volatile boolean explicitlyStopped;
    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { if (id == 1) ensureScreen(); }
        public void onDisplayChanged(int id) {
            if (id == 1) {
                closeScreen();
                ensureScreen();
            }
        }
        public void onDisplayRemoved(int id) { if (id == 1) closeScreen(); }
    };

    private Preface95NativeHints(Context context, a20 manager) {
        this.manager = manager;
        displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displays == null) throw new IllegalStateException("DISPLAY_SERVICE_MISSING");
        displays.registerDisplayListener(listener, MAIN);
        if (!(context.getApplicationContext() instanceof Application)) {
            displays.unregisterDisplayListener(listener);
            throw new IllegalStateException("APPLICATION_CONTEXT_UNAVAILABLE");
        }
        ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(lifecycle);
    }

    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        public void onActivityCreated(Activity activity, Bundle state) { }
        public void onActivityStarted(Activity activity) { }
        public void onActivityResumed(Activity activity) { }
        public void onActivityPaused(Activity activity) { }
        public void onActivityStopped(Activity activity) { }
        public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        public void onActivityDestroyed(Activity activity) {
            String name = activity.getClass().getName();
            if (activity.isFinishing() && !activity.isChangingConfigurations() &&
                    ("com.autonavi.amapauto.MainMapActivity".equals(name) ||
                     "com.autonavi.auto.MainMapActivity".equals(name))) {
                guideEpoch.incrementAndGet();
                closed = true;
                closeScreen();
                displays.unregisterDisplayListener(listener);
                ((Application) activity.getApplication()).unregisterActivityLifecycleCallbacks(this);
            }
        }
    };

    public static void onAutoStatus(final int status) {
        // 116 here is the actual main first-draw callback, not external 116.
        // Known native guide-end paths call stopCluster for 9, 12, 34, 308.
        if (status != 116 && status != 9 && status != 12 && status != 34 && status != 308) return;
        if (status != 116) guideEpoch.incrementAndGet();
        MAIN.post(new Runnable() {
            public void run() {
                try {
                    if (status == 116) {
                        if (firstMainFrameLogged.compareAndSet(false, true))
                            Log.i(TAG, "MAIN_FIRST_FRAME_RECEIVED");
                        if (instance != null && instance.closed) instance = null;
                        Preface95NativeHints owner = getOrCreate(a20.l());
                        owner.explicitlyStopped = false;
                        owner.ensureScreen();
                    } else if (instance != null && instance.screen != null) {
                        instance.screen.clearGuidance();
                    }
                } catch (RuntimeException | LinkageError error) { fail(error); }
            }
        });
    }

    public static void onGuide(GuideInfoProtocolData data) {
        if (data == null) return;
        final long epoch = guideEpoch.get(), sequence = guideSequence.incrementAndGet();
        final Preface95Guidance copy;
        try { copy = new Preface95Guidance(data, SystemClock.elapsedRealtime()); }
        catch (RuntimeException | LinkageError error) { fail(error); return; }
        if (copy.navigates() && firstGuidanceLogged.compareAndSet(false, true))
            Log.i(TAG, "NAVIGATION_GUIDANCE_RECEIVED type=" + copy.type + " icon=" + copy.icon +
                    " distancePresent=" + !copy.distance.isEmpty() + " roadPresent=" + !copy.road.isEmpty());
        MAIN.post(new Runnable() {
            public void run() {
                try {
                    if (epoch != guideEpoch.get() || sequence <= appliedGuide ||
                            (instance != null && instance.closed)) { copy.release(); return; }
                    appliedGuide = sequence;
                    Preface95NativeHints owner = getOrCreate(a20.l());
                    owner.ensureScreen();
                    if (owner.screen != null) owner.screen.accept(copy);
                    else copy.release();
                } catch (RuntimeException | LinkageError error) {
                    copy.release();
                    fail(error);
                }
            }
        });
    }

    /** Intercepts only E02 Large; lets every unrelated original call continue. */
    public static boolean tryCreate(final a20 manager, PresentationConfig config) {
        if (config == null || config.deviceId != 1001 ||
                !"InstrumentPannelLarge".equals(config.pluginName)) return false;
        MAIN.post(new Runnable() {
            public void run() {
                try {
                    Preface95NativeHints owner = getOrCreate(manager);
                    owner.explicitlyStopped = false;
                    owner.ensureScreen();
                }
                catch (RuntimeException | LinkageError error) { fail(error); }
            }
        });
        return true;
    }

    public static boolean tryStop(final a20 manager, int window) {
        hl found = manager.k(window);
        if (!(found instanceof Preface95HintScreen)) return false;
        final Preface95HintScreen target = (Preface95HintScreen) found;
        guideEpoch.incrementAndGet();
        if (instance != null && instance.screen == target) instance.explicitlyStopped = true;
        target.requestStop();
        MAIN.post(new Runnable() {
            public void run() {
                // Emit stop while the real window is still registered, then
                // remove exactly our entry. Original SDK entries stay intact.
                target.c();
                manager.c.remove(target);
                if (instance != null && instance.screen == target) instance.screen = null;
            }
        });
        return true;
    }

    private static Preface95NativeHints getOrCreate(a20 manager) {
        if (!"com.autonavi.amapauto".equals(Application.getProcessName())) {
            throw new IllegalStateException("NON_MAIN_PROCESS_REJECTED");
        }
        if (instance == null) instance = new Preface95NativeHints(rv.H().z(), manager);
        if (instance.manager != manager) throw new IllegalStateException("MANAGER_CHANGED");
        return instance;
    }

    private void ensureScreen() {
        if (failed || closed || explicitlyStopped) return;
        if (screen != null) {
            if (manager.k(1001) == screen) return;
            closeScreen();
        }
        Display display = displays.getDisplay(1);
        if (display == null || !display.isValid() || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            if (!waitingForDisplay) Log.i(TAG, "WAITING_FOR_REAL_DISPLAY_1");
            waitingForDisplay = true;
            return;
        }
        waitingForDisplay = false;
        if (display.getState() != Display.STATE_ON) return;
        // Virtual/overlay test displays must never enable the vehicle path.
        int type;
        try {
            type = ((Integer) Display.class.getMethod("getType").invoke(display)).intValue();
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "DISPLAY_TYPE_UNAVAILABLE");
            return;
        }
        // Fixed Android 9 Display types: BUILT_IN=1, HDMI=2. Unknown,
        // Wi-Fi, overlay and virtual displays cannot prepare the instrument.
        if (type != 1 && type != 2) {
            Log.w(TAG, "SYNTHETIC_DISPLAY_REJECTED");
            return;
        }
        hl existing = manager.k(1001);
        if (existing != null) {
            if (existing instanceof Preface95HintScreen) screen = (Preface95HintScreen) existing;
            else Log.w(TAG, "EXISTING_WINDOW_1001_PRESERVED");
            return;
        }
        Preface95HintScreen candidate = new Preface95HintScreen(rv.H().z(), display, manager);
        manager.c.add(candidate);
        screen = candidate;
        candidate.s();
    }

    private void closeScreen() {
        if (screen == null) return;
        Preface95HintScreen old = screen;
        screen = null;
        old.u();
        manager.c.remove(old);
    }

    static void fail(final Throwable error) {
        // No navigation names, location, IDs, config or payloads in logs.
        Log.e(TAG, "NATIVE_HINTS_FAILED: " + error.getClass().getSimpleName());
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() { public void run() { fail(error); } });
            return;
        }
        if (instance != null) {
            instance.failed = true;
            instance.closeScreen();
        }
    }
}
