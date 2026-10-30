import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import com.autonavi.amapauto.MainMapActivity;
import com.autonavi.amapauto.jni.protocol.data.GuideInfoProtocolData;
import com.autonavi.extscreen.dto.PresentationConfig;

/** Runs the actual owner; Android, vendor manager and Surface are host doubles. */
public final class Preface95HintLifecycleTest {
    private static Application app;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Preface95HintScreen screen() {
        return (Preface95HintScreen) a20.l().k(1001);
    }
    private static void guide() { Preface95NativeHints.onGuide(new GuideInfoProtocolData()); }
    private static MainMapActivity initial() {
        app = new Application();
        Application.current = app;
        MainMapActivity activity = new MainMapActivity();
        Preface95NativeHints.onAutoStatus(116);
        Handler.drain();
        // The engine hook can create the owner after Activity.onCreate.
        app.resumed(activity);
        guide();
        Handler.drain();
        check(screen().confirmReadback(), "initial real-content readback required");
        return activity;
    }
    private static void finish(Activity activity) {
        activity.finishing = true;
        app.destroyed(activity);
    }
    private static void expectEmptyWindow() {
        check(screen() != null && screen().accepted == 0, "new window must discard old data");
        check(!screen().confirmReadback(), "reopening alone must not prepare");
        check(app.callbacks.size() == 1, "exactly one Application observer");
        check(app.displays.listeners.size() == 1, "exactly one Display observer");
    }
    private static void newGuidance() {
        guide(); Handler.drain();
        check(screen().accepted == 1, "new guidance must be accepted without a new first-frame hook");
        check(!screen().gate.ready(), "guidance cannot bypass readback");
        check(screen().confirmReadback(), "new content can prepare after readback");
    }
    public static void main(String[] args) {
        MainMapActivity activity = initial();
        String scenario = args[0];
        if ("reopen-no-frame".equals(scenario)) {
            finish(activity);
            app.created(new MainMapActivity());
            expectEmptyWindow(); newGuidance();
        } else if ("reopen".equals(scenario)) {
            for (int i = 0; i < 3; i++) {
                Preface95HintScreen old = screen();
                guide(); // Queued before the old Activity is destroyed.
                Preface95NativeHints.onAutoStatus(9);
                finish(activity);
                check(screen() == null && old.stopped && !old.gate.ready(), "exit releases old output");
                check(app.displays.listeners.isEmpty(), "exit unregisters Display observer");
                guide();
                Preface95NativeHints.onAutoStatus(116);
                Preface95NativeHints.tryCreate(a20.l(), new PresentationConfig());
                Handler.drain();
                check(screen() == null, "late data, first frame and create cannot reopen a closed Activity");
                // Also queue stale callbacks immediately before the new lifecycle.
                guide();
                Preface95NativeHints.onAutoStatus(12);
                Preface95NativeHints.tryCreate(a20.l(), new PresentationConfig());
                activity = new MainMapActivity();
                app.created(activity); app.resumed(activity);
                expectEmptyWindow();
                newGuidance();
            }
            check(Preface95Guidance.releases >= 9, "obsolete copies must be released");
        } else if ("replacement".equals(scenario)) {
            Preface95HintScreen old = screen();
            Preface95NativeHints.tryStop(a20.l(), 1001); // Old cleanup remains queued.
            guide();
            Preface95NativeHints.onAutoStatus(9);
            MainMapActivity replacement = new MainMapActivity();
            app.created(replacement);
            expectEmptyWindow();
            guide();
            finish(activity); // Old Activity must not close or invalidate the replacement.
            Handler.drain();
            check(old.stopped && screen().accepted == 1, "old stop/destroy cannot affect replacement");
            check(screen().confirmReadback(), "replacement remains usable");
        } else if ("configuration".equals(scenario)) {
            activity.finishing = true; activity.changing = true;
            app.destroyed(activity);
            check(screen() != null, "configuration destroy is not an explicit exit");
            guide();
            app.created(new MainMapActivity());
            expectEmptyWindow(); newGuidance();
        } else if ("background".equals(scenario)) {
            Preface95HintScreen active = screen();
            app.created(new Activity()); app.background(activity); app.destroyed(new Activity());
            Handler.drain();
            check(screen() == active && active.gate.ready(), "background or unrelated Activity must not close navigation");
            guide(); Handler.drain();
            check(active.accepted == 2, "background navigation keeps receiving");
        } else if ("explicit-stop".equals(scenario)) {
            check(Preface95NativeHints.tryStop(a20.l(), 1001), "own stop intercepted");
            Handler.drain(); guide(); Handler.drain();
            check(screen() == null, "guidance cannot override explicit window stop");
            Preface95NativeHints.tryCreate(a20.l(), new PresentationConfig()); Handler.drain();
            expectEmptyWindow(); newGuidance();
            Preface95NativeHints.onAutoStatus(34); Handler.drain();
            check(!screen().gate.ready() && !screen().hasContent, "route end releases ready content");
            guide(); Handler.drain();
            check(screen().confirmReadback(), "a subsequent route still requires fresh readback");
        } else if ("failure".equals(scenario)) {
            Preface95NativeHints.fail(new IllegalStateException("host failure"));
            guide(); Handler.drain();
            check(screen() == null, "failed lifecycle must not silently retry");
            finish(activity);
            app.created(new MainMapActivity());
            expectEmptyWindow(); newGuidance();
        } else throw new AssertionError("unknown scenario");
        System.out.println("PASS " + scenario + " (host doubles; no vehicle protocol)");
    }
}
