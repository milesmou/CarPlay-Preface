package com.android.vpndialogs;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi", shadows = NativeAppOpsManagerShadow.class)
public class AdditionalAuthorizationTest {
    static Backend fake;
    static final AuthorizationBackend.Target TARGET = new AuthorizationBackend.Target("com.shihab.diplay.hudtest", 10123, "CarPlay");
    public static class TestActivity extends AdditionalAuthorizationActivity {
        @Override protected AdditionalAuthorizationBackend createBackend() { return fake; }
    }
    static class Backend extends AdditionalAuthorizationBackend {
        int confirmations;
        boolean unavailable, changedUid, failWrite;
        Backend(Context context) { super(context); }
        @Override void checkOverlayEnvironment() {
            if (unavailable) throw new SecurityException("restricted or permission missing");
        }
        @Override Target overlayTarget(String name) {
            if (!Android9Backend.supportedPackage(name)) throw new SecurityException("unsupported target");
            return new Target(name, changedUid ? TARGET.uid + 1 : TARGET.uid, TARGET.label);
        }
        @Override boolean setOverlayAllowed(Target expected, boolean value) throws Exception {
            confirmations++;
            return !failWrite && super.setOverlayAllowed(expected, value);
        }
    }
    @Before public void reset() throws Exception {
        fake = new Backend(RuntimeEnvironment.getApplication());
        setOverlayMode(TARGET.uid, AppOpsManager.MODE_ERRORED);
    }
    private void setOverlayMode(int uid, int mode) throws Exception {
        AppOpsManager manager = RuntimeEnvironment.getApplication().getSystemService(AppOpsManager.class);
        AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class)
            .invoke(manager, AppOpsManager.class.getField("OP_SYSTEM_ALERT_WINDOW").getInt(null), uid, TARGET.packageName, mode);
    }
    private TestActivity open() {
        return open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + TARGET.packageName)));
    }
    private TestActivity open(Intent intent) { return Robolectric.buildActivity(TestActivity.class, intent).setup().get(); }
    private void click(Activity activity, String label) {
        android.widget.Button button = AuthorizationConsentTest.button(activity.getWindow().getDecorView(), label);
        assertNotNull(label, button); assertTrue(button.getFilterTouchesWhenObscured()); button.performClick();
    }
    @Test public void overlayNeedsExplicitConsentAndIsReadBackUnderTargetUid() throws Exception {
        TestActivity page = open();
        assertFalse(fake.overlayAllowed(TARGET)); assertEquals(0, fake.confirmations);
        click(page, "允许");
        assertTrue(fake.overlayAllowed(TARGET));
        assertEquals(Activity.RESULT_OK, Shadows.shadowOf(page).getResultCode());
        assertEquals(1, fake.confirmations);
        page = open(); click(page, "关闭授权");
        assertFalse(fake.overlayAllowed(TARGET)); assertEquals(2, fake.confirmations);
    }
    @Test public void overlayQueryDoesNotConfuseAnotherUidWithTheTarget() throws Exception {
        setOverlayMode(TARGET.uid + 1, AppOpsManager.MODE_ALLOWED);
        assertFalse(fake.overlayAllowed(TARGET));
    }
    @Test public void cancellationAndRecreationHaveNoSideEffects() throws Exception {
        org.robolectric.android.controller.ActivityController<TestActivity> controller = Robolectric.buildActivity(TestActivity.class,
            new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + TARGET.packageName))).setup();
        controller.recreate(); click(controller.get(), "取消");
        assertEquals(0, fake.confirmations); assertFalse(fake.overlayAllowed(TARGET));
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(controller.get()).getResultCode());
        controller.pause().stop().destroy();
    }
    @Test public void latePolicyAndUidChangesCannotGrant() throws Exception {
        TestActivity page = open();
        fake.unavailable = true; click(page, "允许");
        assertFalse(fake.overlayAllowed(TARGET)); assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(page).getResultCode());
        fake.unavailable = false; fake.changedUid = true;
        assertThrows(SecurityException.class, () -> fake.setOverlayAllowed(TARGET, true));
        assertFalse(fake.overlayAllowed(TARGET));
    }
    @Test public void failedWriteDoesNotReportAuthorizationSuccess() throws Exception {
        fake.failWrite = true; TestActivity page = open(); click(page, "允许");
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(page).getResultCode()); assertFalse(fake.overlayAllowed(TARGET));
    }
    @Test public void unrelatedPackagesMalformedUrisAndRemovedActionsAreRejected() {
        for (Intent intent : new Intent[]{new Intent(), new Intent("arbitrary", Uri.parse("package:" + TARGET.packageName)),
            new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:com.other.app")),
            new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("file:com.shihab.diplay")),
            new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", Uri.parse("package:" + TARGET.packageName))}) {
            TestActivity page = open(intent);
            assertNull(AuthorizationConsentTest.button(page.getWindow().getDecorView(), "允许"));
            assertEquals(0, fake.confirmations); page.finish();
        }
    }
    @Test public void noPackageOpensTheSelectorWithoutGranting() {
        TestActivity page = open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        Intent next = Shadows.shadowOf(page).getNextStartedActivity();
        assertNotNull(next); assertEquals(SpecialAccessActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, next.getAction());
        assertEquals(0, fake.confirmations);
    }
    @Test public void realOverlayGuardRespectsBothUserRestrictions() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AdditionalAuthorizationBackend guards = new AdditionalAuthorizationBackend(context) {
            @Override public void checkEnvironment(boolean vpn) { }
        };
        guards.checkOverlayEnvironment();
        android.os.UserManager users = context.getSystemService(android.os.UserManager.class);
        Shadows.shadowOf(users).setUserRestriction(android.os.Process.myUserHandle(), android.os.UserManager.DISALLOW_CREATE_WINDOWS, true);
        assertThrows(SecurityException.class, guards::checkOverlayEnvironment);
        Shadows.shadowOf(users).setUserRestriction(android.os.Process.myUserHandle(), android.os.UserManager.DISALLOW_CREATE_WINDOWS, false);
        Shadows.shadowOf(users).setUserRestriction(android.os.Process.myUserHandle(), android.os.UserManager.DISALLOW_APPS_CONTROL, true);
        assertThrows(SecurityException.class, guards::checkOverlayEnvironment);
    }
}
