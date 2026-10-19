package com.android.vpndialogs;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.UserManager;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** Exercise real package eligibility and writes, bypassing only the device platform environment. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = NativeAppOpsManagerShadow.class)
public class ThirdPartyAuthorizationTest {
    private static final String PACKAGE = "com.example.thirdparty";
    private Context context;
    private PackageInfo info;
    private Android9Backend backend;
    private AdditionalAuthorizationBackend overlay;
    private SpecialAccessBackend special;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        backend = new Android9Backend(context) { @Override public void checkEnvironment(boolean vpn) { } };
        overlay = new AdditionalAuthorizationBackend(context) { @Override public void checkEnvironment(boolean vpn) { } };
        special = new SpecialAccessBackend(context) { @Override public void checkEnvironment(boolean vpn) { } };
        info = new PackageInfo(); info.packageName = PACKAGE; info.versionCode = 1;
        info.signatures = new Signature[]{new Signature("1234")};
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.packageName = PACKAGE;
        info.applicationInfo.uid = 10234; info.applicationInfo.enabled = true;
        info.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        info.requestedPermissions = new String[]{Manifest.permission.WRITE_SETTINGS};
        install();
        for (String operation : new String[]{"OP_WRITE_SETTINGS", "OP_SYSTEM_ALERT_WINDOW"})
            AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class).invoke(
                context.getSystemService(AppOpsManager.class), AppOpsManager.class.getField(operation).getInt(null),
                info.applicationInfo.uid, PACKAGE, AppOpsManager.MODE_ERRORED);
    }
    private void install() {
        info.requestedPermissionsFlags = new int[info.requestedPermissions.length];
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
    }
    private List<SpecialAccessBackend.Entry> entries(SpecialAccessBackend.Kind kind) throws Exception {
        return special.entries(kind, PACKAGE);
    }
    @Test public void ordinaryThirdPartySignerCanGrantAndRevokeWriteSettings() throws Exception {
        AuthorizationBackend.Target target = backend.target(PACKAGE, false);
        assertFalse(Android9Backend.CARPLAY_CERT.equals(target.certificate));
        assertTrue(backend.setWriteAllowed(target, true)); assertTrue(backend.writeAllowed(target));
        assertTrue(backend.setWriteAllowed(target, false)); assertFalse(backend.writeAllowed(target));
        assertEquals(1, entries(SpecialAccessBackend.Kind.WRITE).size());
    }
    @Test public void overlayOnlyAppDoesNotNeedWriteSettingsPermission() throws Exception {
        info.requestedPermissions = new String[]{Manifest.permission.SYSTEM_ALERT_WINDOW}; install();
        AuthorizationBackend.Target target = overlay.overlayTarget(PACKAGE);
        assertTrue(overlay.setOverlayAllowed(target, true)); assertTrue(overlay.overlayAllowed(target));
        assertTrue(overlay.setOverlayAllowed(target, false)); assertFalse(overlay.overlayAllowed(target));
        assertEquals(1, entries(SpecialAccessBackend.Kind.OVERLAY).size());
        assertTrue(entries(SpecialAccessBackend.Kind.WRITE).isEmpty());
        assertThrows(SecurityException.class, () -> backend.target(PACKAGE, false));
    }
    @Test public void installedAppsMustDeclareTheSelectedPermission() throws Exception {
        info.requestedPermissions = new String[0]; install();
        assertThrows(SecurityException.class, () -> backend.target(PACKAGE, false));
        assertThrows(SecurityException.class, () -> overlay.overlayTarget(PACKAGE));
        assertTrue(entries(SpecialAccessBackend.Kind.WRITE).isEmpty());
        assertTrue(entries(SpecialAccessBackend.Kind.OVERLAY).isEmpty());
    }
    @Test public void disabledSuspendedAndOtherUserAppsAreIneligible() throws Exception {
        info.applicationInfo.enabled = false; install();
        assertThrows(SecurityException.class, () -> backend.target(PACKAGE, false));
        assertTrue(entries(SpecialAccessBackend.Kind.WRITE).isEmpty());
        info.applicationInfo.enabled = true; info.applicationInfo.flags |= ApplicationInfo.FLAG_SUSPENDED; install();
        assertThrows(SecurityException.class, () -> backend.target(PACKAGE, false));
        assertTrue(entries(SpecialAccessBackend.Kind.WRITE).isEmpty());
        info.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED; info.applicationInfo.uid = 110234; install();
        assertThrows(SecurityException.class, () -> backend.target(PACKAGE, false));
        assertTrue(entries(SpecialAccessBackend.Kind.WRITE).isEmpty());
    }
    @Test public void sameUidUpdateAndSignerChangesInvalidatePendingWriteConsent() throws Exception {
        AuthorizationBackend.Target selected = backend.target(PACKAGE, false);
        info.versionCode = 2; install();
        assertThrows(SecurityException.class, () -> backend.setWriteAllowed(selected, true));
        info.versionCode = 1; info.signatures = new Signature[]{new Signature("5678")}; install();
        assertThrows(SecurityException.class, () -> backend.setWriteAllowed(selected, true));
        assertFalse(backend.writeAllowed(selected));
    }
    @Test public void sameUidUpdateInvalidatesOverlayConsent() throws Exception {
        info.requestedPermissions = new String[]{Manifest.permission.SYSTEM_ALERT_WINDOW}; install();
        AuthorizationBackend.Target selected = overlay.overlayTarget(PACKAGE);
        info.versionCode = 2; install();
        assertThrows(SecurityException.class, () -> overlay.setOverlayAllowed(selected, true));
        assertFalse(overlay.overlayAllowed(selected));
    }
    @Test public void thirdPartyVpnRequiresAnEnabledExportedProtectedService() throws Exception {
        assertThrows(SecurityException.class, () -> backend.target(PACKAGE, true));
        ServiceInfo service = new ServiceInfo(); service.packageName = PACKAGE; service.name = PACKAGE + ".Vpn";
        service.enabled = true; service.exported = true; service.permission = Manifest.permission.BIND_VPN_SERVICE;
        info.services = new ServiceInfo[]{service}; install();
        assertEquals(PACKAGE, backend.target(PACKAGE, true).packageName);
        service.permission = null; install();
        assertThrows(SecurityException.class, () -> backend.target(PACKAGE, true));
        service.permission = Manifest.permission.BIND_VPN_SERVICE; service.exported = false; install();
        assertThrows(SecurityException.class, () -> backend.target(PACKAGE, true));
        service.exported = true; service.enabled = false; install();
        assertThrows(SecurityException.class, () -> backend.target(PACKAGE, true));
    }
    @Test public void lateSystemPolicyBlocksBothDirectAndSelectorWrites() throws Exception {
        AuthorizationBackend.Target target = backend.target(PACKAGE, false);
        SpecialAccessBackend.Entry entry = entries(SpecialAccessBackend.Kind.WRITE).get(0);
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserRestriction(
            android.os.Process.myUserHandle(), UserManager.DISALLOW_APPS_CONTROL, true);
        assertThrows(SecurityException.class, () -> backend.setWriteAllowed(target, true));
        assertThrows(SecurityException.class, () -> special.setAllowed(SpecialAccessBackend.Kind.WRITE, entry, true));
        assertFalse(backend.writeAllowed(target));
    }
    @Test public void overlaySelectorAlsoHonorsWindowPolicy() throws Exception {
        info.requestedPermissions = new String[]{Manifest.permission.SYSTEM_ALERT_WINDOW}; install();
        SpecialAccessBackend.Entry entry = entries(SpecialAccessBackend.Kind.OVERLAY).get(0);
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserRestriction(
            android.os.Process.myUserHandle(), UserManager.DISALLOW_CREATE_WINDOWS, true);
        assertTrue(special.blocked(SpecialAccessBackend.Kind.OVERLAY, entry));
        assertThrows(SecurityException.class, () -> special.setAllowed(SpecialAccessBackend.Kind.OVERLAY, entry, true));
    }
}
