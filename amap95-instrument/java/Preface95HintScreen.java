import android.app.Presentation;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import com.autonavi.amapauto.jni.ViewInfo;
import com.autonavi.amapauto.jni.protocol.AndroidProtocolExe;

/** Real secondary Presentation, software buffer submission and readback. */
public final class Preface95HintScreen extends hl implements SurfaceHolder.Callback2 {
    private static final long MAX_AGE_MS = 10000;
    private static final String TAG = Preface95NativeHints.TAG;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final a20 manager;
    private final Display display;
    private final Preface95FrameGate gate = new Preface95FrameGate();
    private HintPresentation presentation;
    private SurfaceView surfaceView;
    private SurfaceHolder holder;
    private Preface95Guidance guidance;
    private boolean copying;
    private boolean disposed;
    private volatile boolean stopped;
    private final Runnable expire = new Runnable() {
        public void run() {
            if (guidance != null && SystemClock.elapsedRealtime() - guidance.receivedAt >= MAX_AGE_MS) {
                clearGuidance();
            }
        }
    };

    public Preface95HintScreen(Context context, Display display, a20 manager) {
        super(context);
        this.display = display;
        this.manager = manager;
        o(1001);
        ViewInfo info = new ViewInfo();
        info.a = "InstrumentPannelLarge";
        info.b = "InstrumentPannelLarge";
        info.c = 1001;
        info.i = 1;
        info.n = 1;
        r(info);
    }

    public void accept(Preface95Guidance update) {
        if (disposed || stopped || SystemClock.elapsedRealtime() - update.receivedAt >= MAX_AGE_MS) {
            update.release();
            return;
        }
        releaseGuidance();
        if (update.navigates()) guidance = update;
        else update.release();
        gate.changeContent();
        main.removeCallbacks(expire);
        if (guidance != null) main.postDelayed(expire, MAX_AGE_MS);
        else stopLease();
        drawFrame();
    }

    public void clearGuidance() {
        releaseGuidance();
        main.removeCallbacks(expire);
        gate.changeContent();
        stopLease();
        drawFrame();
    }

    private void releaseGuidance() {
        if (guidance != null) guidance.release();
        guidance = null;
    }

    private boolean validWindow() {
        return !disposed && !stopped && presentation != null && presentation.isShowing() &&
                display.isValid() && display.getState() == Display.STATE_ON &&
                manager.k(1001) == this && holder != null && holder.getSurface().isValid();
    }

    private void stopLease() {
        boolean notified = gate.closeSurface();
        if (notified) {
            try { AndroidProtocolExe.sendExScreenStatus(1, 1001, 1); }
            catch (RuntimeException | LinkageError error) {
                Log.e(TAG, "STOP_PROTOCOL_FAILED: " + error.getClass().getSimpleName());
            }
        }
    }

    private void drawFrame() {
        if (!validWindow() || copying) return;
        int width = surfaceView.getWidth(), height = surfaceView.getHeight();
        if (width <= 0 || height <= 0 || width < height || (long) width * height > 8294400L) return;
        // This is an actual Surface generation, never a made-up SDK device ID.
        if (!gate.current(gate.generation(), gate.revision())) gate.openSurface();
        final long lease = gate.generation(), content = gate.revision();
        final boolean hasGuidance = guidance != null;
        final Bitmap expected;
        try { expected = paintFrame(width, height, guidance); }
        catch (RuntimeException | LinkageError error) { fail(error); return; }
        Canvas canvas = null;
        boolean posted = false;
        try {
            canvas = holder.lockCanvas();
            if (canvas == null) throw new IllegalStateException("SURFACE_CANVAS_UNAVAILABLE");
            canvas.drawBitmap(expected, 0, 0, null);
            holder.unlockCanvasAndPost(canvas);
            canvas = null;
            posted = true;
        } catch (RuntimeException error) {
            fail(error);
        } finally {
            if (canvas != null) {
                // Unlock the producer even when drawing failed; no READY is
                // considered for this partial buffer.
                try { holder.unlockCanvasAndPost(canvas); } catch (RuntimeException ignored) { }
            }
        }
        if (!posted || !hasGuidance || !validWindow() || gate.ready()) { expected.recycle(); return; }
        final Bitmap readback = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        copying = true;
        final Runnable timeout = new Runnable() {
            public void run() {
                if (copying && validWindow()) fail(new IllegalStateException("PIXELCOPY_TIMEOUT"));
            }
        };
        main.postDelayed(timeout, 1500);
        try {
            PixelCopy.request(holder.getSurface(), readback, new PixelCopy.OnPixelCopyFinishedListener() {
                public void onPixelCopyFinished(int result) {
                    main.removeCallbacks(timeout);
                    copying = false;
                    try {
                        if (!validWindow() || !gate.current(lease, content)) return;
                        if (result != PixelCopy.SUCCESS || !matches(expected, readback)) {
                            throw new IllegalStateException("POSTED_GUIDANCE_READBACK_FAILED_" + result);
                        }
                        if (guidance == null || SystemClock.elapsedRealtime() - guidance.receivedAt >= MAX_AGE_MS) return;
                        if (gate.confirm(lease, content, true, true)) {
                            // Existing JSON/status route consumes this actual
                            // window's submitted/read-back guidance. No direct
                            // vehicle property, power or projection calls.
                            AndroidProtocolExe.sendExScreenStatus(0, 1001, 1);
                            Log.i(TAG, "GUIDANCE_BUFFER_READBACK_CONFIRMED_WINDOW_1001");
                        }
                    } catch (RuntimeException | LinkageError error) {
                        fail(error);
                    } finally {
                        expected.recycle();
                        readback.recycle();
                        if (validWindow() && !gate.current(lease, content)) drawFrame();
                    }
                }
            }, main);
        } catch (RuntimeException error) {
            main.removeCallbacks(timeout);
            copying = false;
            expected.recycle();
            readback.recycle();
            fail(error);
        }
    }

    private Bitmap paintFrame(int width, int height, Preface95Guidance data) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(12, 18, 24));
        float density = presentation.getContext().getResources().getDisplayMetrics().density;
        float scale = Math.min(width / (640f * density), height / (240f * density)) * density;
        canvas.translate((width - 640 * scale) / 2, (height - 240 * scale) / 2);
        canvas.scale(scale, scale);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(24);
        if (data == null) {
            canvas.drawText("暂无导航提示", 48, 130, paint);
            return bitmap;
        }
        if (data.distance.isEmpty() && data.road.isEmpty()) {
            bitmap.recycle();
            throw new IllegalStateException("EMPTY_REAL_GUIDANCE");
        }
        Bitmap arrow = data.makeArrow(presentation.getContext());
        if (arrow == null || arrow.isRecycled()) {
            bitmap.recycle();
            throw new IllegalStateException("TURN_ICON_UNAVAILABLE");
        }
        if (arrow != null && !arrow.isRecycled()) {
            float fit = Math.min(150f / arrow.getWidth(), 150f / arrow.getHeight());
            float w = arrow.getWidth() * fit, h = arrow.getHeight() * fit;
            canvas.drawBitmap(arrow, null, new RectF(35 + (150 - w) / 2, 40 + (150 - h) / 2,
                    35 + (150 + w) / 2, 40 + (150 + h) / 2), paint);
            arrow.recycle();
        }
        paint.setTextSize(42);
        canvas.drawText(fitText(data.distance, paint, 395), 220, 110, paint);
        paint.setTextSize(25);
        canvas.drawText(fitText(data.road, paint, 395), 220, 157, paint);
        if (data.type == 1) {
            paint.setTextSize(15);
            canvas.drawText("模拟导航", 220, 194, paint);
        }
        return bitmap;
    }

    private static String fitText(String text, Paint paint, float width) {
        int length = paint.breakText(text, true, width, null);
        return length == text.length() ? text : text.substring(0, Math.max(0, length - 1)) + "…";
    }

    private static boolean matches(Bitmap expected, Bitmap actual) {
        // Read every pixel, in bounded row buffers. This confirms the submitted
        // app buffer, not that the vehicle cluster has accepted/displayed it.
        if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) return false;
        int width = expected.getWidth();
        int[] before = new int[width], after = new int[width];
        for (int y = 0; y < expected.getHeight(); y++) {
            expected.getPixels(before, 0, width, 0, y, width, 1);
            actual.getPixels(after, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) if ((before[x] & 0xffffff) != (after[x] & 0xffffff)) return false;
        }
        return true;
    }

    private void fail(Throwable error) {
        stopped = true;
        stopLease();
        Preface95NativeHints.fail(error);
    }

    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        if (surfaceView == null || surfaceView.getHolder() != surfaceHolder) return;
        stopLease();
        holder = surfaceHolder;
        gate.openSurface();
        drawFrame();
    }
    public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
        if (surfaceView == null || surfaceView.getHolder() != surfaceHolder) return;
        stopLease();
        holder = surfaceHolder;
        gate.openSurface();
        drawFrame();
    }
    public void surfaceRedrawNeeded(SurfaceHolder surfaceHolder) { if (holder == surfaceHolder) drawFrame(); }
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        if (holder == surfaceHolder) { stopLease(); holder = null; }
    }

    public void requestStop() { stopped = true; }

    // Pinned enum ordinal 2 is SYSTEM_PRESENTATION. Original field names are
    // obfuscated; JADX's displayed enum constant is not its DEX field name.
    public d20 f() { return d20.values()[2]; }
    public void i() { }
    public void n(MotionEvent event) { } // No input injection or UI controls.
    public void t(int mode) { s(); }
    public void s() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(new Runnable() { public void run() { s(); } });
            return;
        }
        if (disposed) return;
        stopped = false;
        if (presentation != null && presentation.isShowing()) return;
        final HintPresentation opened = new HintPresentation(b, display);
        presentation = opened;
        opened.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (presentation != opened) return;
                stopLease();
                holder = null;
                surfaceView = null;
            }
        });
        try { presentation.show(); }
        catch (RuntimeException error) { fail(error); }
    }
    public void u() {
        stopped = true;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(new Runnable() { public void run() { u(); } });
            return;
        }
        stopLease();
        releaseGuidance();
        main.removeCallbacks(expire);
        if (presentation != null) presentation.dismiss();
        presentation = null;
        holder = null;
        surfaceView = null;
    }
    public void c() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(new Runnable() { public void run() { c(); } });
            return;
        }
        u(); disposed = true;
    }
    public void b(int target, boolean visible) { if (visible) s(); else u(); }

    private final class HintPresentation extends Presentation {
        HintPresentation(Context context, Display display) { super(context, display); }
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            if (getWindow() == null) throw new IllegalStateException("PRESENTATION_WINDOW_MISSING");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            surfaceView = new SurfaceView(getContext());
            surfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
            surfaceView.getHolder().addCallback(Preface95HintScreen.this);
            setContentView(surfaceView);
        }
    }
}
