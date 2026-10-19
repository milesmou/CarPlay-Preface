package com.android.vpndialogs;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
public class AuthorizationConsentTest {
    static FakeBackend fake;
    static String caller;
    public static class TestConfirm extends ConfirmDialog {
        @Override protected AuthorizationBackend createBackend() { return fake; }
        @Override public String getCallingPackage() { return caller; }
    }
    public static class TestWrite extends WriteSettingsActivity {
        @Override protected AuthorizationBackend createBackend() { return fake; }
    }
    static class FakeBackend implements AuthorizationBackend {
        int vpnWrites, settingsWrites, preparedReads;
        boolean blocked, prepared, allowed, unavailable, failWrite;
        String changedPackage;
        @Override public void checkEnvironment(boolean vpn) {
            if (unavailable) throw new SecurityException("no platform permission");
        }
        @Override public Target target(String name, boolean vpn) {
            if (!Android9Backend.supportedPackage(name) && !"com.example.other".equals(name))
                throw new SecurityException("unavailable target");
            return new Target(name, 10123, "CarPlay");
        }
        @Override public boolean vpnBlocked(Target target) { return blocked; }
        @Override public boolean vpnAuthorized(Target target) { preparedReads++; return prepared; }
        @Override public boolean allowVpn(Target target) { vpnWrites++; changedPackage = target.packageName; return !failWrite; }
        @Override public boolean writeAllowed(Target target) { return allowed; }
        @Override public boolean setWriteAllowed(Target target, boolean value) {
            settingsWrites++; changedPackage = target.packageName; allowed = value; return !failWrite;
        }
    }
    @Before public void reset() { fake = new FakeBackend(); caller = "com.shihab.diplay.hudtest"; }
    static Button button(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = button(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
    private TestConfirm vpn() { return Robolectric.buildActivity(TestConfirm.class).setup().get(); }
    private TestWrite settings(Intent intent) { return Robolectric.buildActivity(TestWrite.class, intent).setup().get(); }
    private Intent settingsIntent() {
        return new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:com.shihab.diplay.hudtest"));
    }
    private void click(Activity activity, String label) {
        Button control = button(activity.getWindow().getDecorView(), label);
        assertNotNull(label, control);
        assertTrue(control.getFilterTouchesWhenObscured());
        control.performClick();
    }
    @Test public void openingVpnOnlyReadsUntilExplicitConfirmation() {
        TestConfirm activity = vpn();
        assertEquals(0, fake.vpnWrites);
        assertEquals(0, fake.settingsWrites);
        click(activity, "允许");
        assertEquals(1, fake.vpnWrites);
        assertEquals(caller, fake.changedPackage);
        assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
    }
    @Test public void cancellingVpnDoesNotGrantOrCreateAnything() {
        TestConfirm activity = vpn(); click(activity, "取消");
        assertEquals(0, fake.vpnWrites);
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
    }
    @Test public void backCancelsWithoutGrant() {
        TestConfirm activity = vpn(); activity.onBackPressed();
        assertEquals(0, fake.vpnWrites);
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
    }
    @Test public void preparedVpnReturnsSuccessWithoutRegranting() {
        fake.prepared = true; TestConfirm activity = vpn();
        assertTrue(activity.isFinishing()); assertEquals(0, fake.vpnWrites);
        assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
    }
    @Test public void restrictedVpnCannotPrepareOrGrant() {
        fake.blocked = true; TestConfirm activity = vpn();
        assertNull(button(activity.getWindow().getDecorView(), "允许"));
        assertEquals(0, fake.preparedReads); assertEquals(0, fake.vpnWrites);
    }
    @Test public void missingCallerAndUnavailableCallerCannotGrant() {
        for (String name : new String[]{null, "com.other.vpn"}) {
            caller = name; TestConfirm activity = vpn();
            assertNull(button(activity.getWindow().getDecorView(), "允许"));
            assertEquals(0, fake.vpnWrites); activity.finish();
        }
    }
    @Test public void anotherVpnAppCanRequestConsentOnlyForItsActualCallerIdentity() {
        caller = "com.example.other";
        TestConfirm activity = Robolectric.buildActivity(TestConfirm.class,
            new Intent().putExtra("package", "com.shihab.diplay").setData(Uri.parse("package:com.shihab.diplay"))).setup().get();
        assertEquals(0, fake.vpnWrites);
        click(activity, "允许");
        assertEquals("com.example.other", fake.changedPackage);
        assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
    }
    @Test public void anotherAppCanExplicitlyGrantAndRevokeWriteSettings() {
        Intent request = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:com.example.other"));
        TestWrite activity = settings(request);
        assertEquals(0, fake.settingsWrites);
        click(activity, "允许");
        assertEquals("com.example.other", fake.changedPackage); assertTrue(fake.allowed);
        activity = settings(request); click(activity, "关闭授权");
        assertFalse(fake.allowed);
    }
    @Test public void settingsWithoutAPackageOpensTheAppSelectorWithoutGranting() {
        TestWrite activity = settings(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS));
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next); assertEquals(SpecialAccessActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(Settings.ACTION_MANAGE_WRITE_SETTINGS, next.getAction());
        assertEquals(0, fake.settingsWrites);
    }
    @Test public void missingPlatformPermissionNeverShowsAnAllowControl() {
        fake.unavailable = true; TestConfirm activity = vpn();
        assertNull(button(activity.getWindow().getDecorView(), "允许")); assertEquals(0, fake.vpnWrites);
    }
    @Test public void reflectiveFailureShowsUnderlyingPermissionReasonWithoutGranting() {
        TestWrite activity = settings(settingsIntent());
        activity.unavailable(new java.lang.reflect.InvocationTargetException(
            new SecurityException("android.permission.UPDATE_APP_OPS_STATS")));
        assertTrue(containsText(activity.getWindow().getDecorView(),
            "SecurityException: android.permission.UPDATE_APP_OPS_STATS"));
        assertNull(button(activity.getWindow().getDecorView(), "允许"));
        assertEquals(0, fake.settingsWrites);
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
    }
    private boolean containsText(View view, String expected) {
        if (view instanceof android.widget.TextView && ((android.widget.TextView) view).getText().toString().contains(expected)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (containsText(((ViewGroup) view).getChildAt(i), expected)) return true;
        return false;
    }
    @Test public void failedVpnGrantNeverReportsSuccess() {
        fake.failWrite = true; TestConfirm activity = vpn(); click(activity, "允许");
        assertFalse(activity.isFinishing());
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
    }
    @Test public void writePermissionChangesOnlyAfterAllow() {
        TestWrite activity = settings(settingsIntent()); assertEquals(0, fake.settingsWrites);
        click(activity, "允许"); assertTrue(fake.allowed); assertEquals(1, fake.settingsWrites);
        assertEquals("com.shihab.diplay.hudtest", fake.changedPackage); assertEquals(0, fake.vpnWrites);
    }
    @Test public void writePermissionCanBeRevokedExplicitly() {
        fake.allowed = true; TestWrite activity = settings(settingsIntent());
        click(activity, "关闭授权"); assertFalse(fake.allowed); assertEquals(1, fake.settingsWrites);
    }
    @Test public void cancellingWritePageMakesNoChanges() {
        TestWrite activity = settings(settingsIntent()); click(activity, "取消");
        assertEquals(0, fake.settingsWrites);
    }
    @Test public void malformedSettingsRequestsAndOtherPackagesAreRejected() {
        for (Intent intent : new Intent[]{new Intent(), new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:com.other.app")), new Intent("untrusted", Uri.parse("package:com.shihab.diplay"))}) {
            TestWrite activity = settings(intent);
            assertNull(button(activity.getWindow().getDecorView(), "允许")); assertEquals(0, fake.settingsWrites);
            activity.finish();
        }
    }
    @Test public void failedWriteChangeNeverReportsSuccess() {
        fake.failWrite = true; TestWrite activity = settings(settingsIntent()); click(activity, "允许");
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
    }
    @Test public void activityRecreationDoesNotGrantPermission() {
        ActivityController<TestConfirm> controller = Robolectric.buildActivity(TestConfirm.class).setup();
        controller.recreate(); assertEquals(0, fake.vpnWrites); controller.pause().stop().destroy();
    }
    @Test @Config(sdk = 33) public void realBackendRejectsAnotherAndroidVersion() throws Exception {
        Android9Backend backend = new Android9Backend(org.robolectric.RuntimeEnvironment.getApplication());
        try { backend.checkEnvironment(true); fail("must reject Android 13"); } catch (SecurityException expected) { }
    }
    @Test public void realBackendRejectsAnUnmatchedPlatformCertificate() throws Exception {
        Android9Backend backend = new Android9Backend(org.robolectric.RuntimeEnvironment.getApplication());
        try { backend.checkEnvironment(false); fail("must reject missing certificate"); } catch (Exception expected) { }
    }
    @Test public void bluetoothProxyClientAllowlistStillContainsOnlyTheTwoCarPlayPackages() {
        assertTrue(Android9Backend.supportedPackage("com.shihab.diplay"));
        assertTrue(Android9Backend.supportedPackage("com.shihab.diplay.hudtest"));
        assertFalse(Android9Backend.supportedPackage("com.shihab.diplay.evil"));
        assertFalse(Android9Backend.supportedPackage(null));
    }
}
