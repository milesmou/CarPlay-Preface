package com.android.vpndialogs;

import static org.junit.Assert.*;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import com.android.vpndialogs.SpecialAccessBackend.Entry;
import com.android.vpndialogs.SpecialAccessBackend.Kind;

/** Prepared regression coverage; run only when testing is requested. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = NativeAppOpsManagerShadow.class)
public class SpecialAccessBackendTest {
    private static final String PACKAGE = "com.example.selected";
    private static final ComponentName SERVICE = new ComponentName(PACKAGE, PACKAGE + ".Assist");
    private static final Entry TARGET = new Entry(PACKAGE, 10234, 7, "selected-certificate", "Selected app", SERVICE);
    private Backend backend;
    private Context context;
    private static class Backend extends SpecialAccessBackend {
        Entry current = TARGET;
        boolean restricted, missingPermission;
        Backend(Context context) { super(context); }
        @Override void checkSpecialEnvironment(Kind kind) {
            if (missingPermission) throw new SecurityException("permission missing");
        }
        @Override List<Entry> entries(Kind kind, String selectedPackage) {
            checkSpecialEnvironment(kind);
            return current == null ? Collections.emptyList() : Collections.singletonList(current);
        }
        @Override boolean blocked(Kind kind, Entry selected) { return restricted; }
    }
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication(); backend = new Backend(context);
        mode("OP_REQUEST_INSTALL_PACKAGES", TARGET.uid, AppOpsManager.MODE_ERRORED);
        mode("OP_GET_USAGE_STATS", TARGET.uid, AppOpsManager.MODE_IGNORED);
        mode("OP_WRITE_SETTINGS", TARGET.uid, AppOpsManager.MODE_ERRORED);
        mode("OP_SYSTEM_ALERT_WINDOW", TARGET.uid, AppOpsManager.MODE_ERRORED);
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, null);
    }
    private void mode(String field, int uid, int mode) throws Exception {
        AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class).invoke(
            context.getSystemService(AppOpsManager.class), AppOpsManager.class.getField(field).getInt(null), uid, PACKAGE, mode);
    }
    @Test public void onlyTheFiveSupportedSpecialAccessActionsAreAccepted() {
        assertEquals(5, Kind.values().length);
        for (Kind kind : Kind.values()) assertSame(kind, Kind.fromAction(kind.action));
        assertThrows(SecurityException.class, () -> Kind.fromAction(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        assertThrows(SecurityException.class, () -> Kind.fromAction(null));
    }
    @Test public void thirdPartyWriteAndOverlayCanBeGrantedAndRevokedIndependently() throws Exception {
        for (Kind kind : new Kind[]{Kind.WRITE, Kind.OVERLAY}) {
            assertFalse(backend.allowed(kind, TARGET));
            assertTrue(backend.setAllowed(kind, TARGET, true));
            assertTrue(backend.allowed(kind, TARGET));
            assertFalse(backend.allowed(kind == Kind.WRITE ? Kind.OVERLAY : Kind.WRITE, TARGET));
            assertTrue(backend.setAllowed(kind, TARGET, false));
            assertFalse(backend.allowed(kind, TARGET));
        }
    }
    @Test public void installationSourceCanBeGrantedAndRevokedWithoutInstallingAnything() throws Exception {
        assertFalse(backend.allowed(Kind.INSTALL, TARGET));
        assertTrue(backend.setAllowed(Kind.INSTALL, TARGET, true));
        assertTrue(backend.allowed(Kind.INSTALL, TARGET));
        assertTrue(backend.setAllowed(Kind.INSTALL, TARGET, false));
        assertFalse(backend.allowed(Kind.INSTALL, TARGET));
    }
    @Test public void usageReadsTheSelectedUidAndSupportsExplicitRevocation() throws Exception {
        mode("OP_GET_USAGE_STATS", TARGET.uid + 1, AppOpsManager.MODE_ALLOWED);
        assertFalse(backend.allowed(Kind.USAGE, TARGET));
        assertTrue(backend.setAllowed(Kind.USAGE, TARGET, true));
        assertTrue(backend.allowed(Kind.USAGE, TARGET));
        assertTrue(backend.setAllowed(Kind.USAGE, TARGET, false));
        assertFalse(backend.allowed(Kind.USAGE, TARGET));
    }
    @Test public void policyAndMissingPrivilegesCannotBeBypassedByConfirmation() throws Exception {
        backend.restricted = true;
        assertThrows(SecurityException.class, () -> backend.setAllowed(Kind.INSTALL, TARGET, true));
        assertFalse(backend.allowed(Kind.INSTALL, TARGET));
        backend.restricted = false; backend.missingPermission = true;
        assertThrows(SecurityException.class, () -> backend.setAllowed(Kind.INSTALL, TARGET, true));
        assertFalse(backend.allowed(Kind.INSTALL, TARGET));
    }
    @Test public void uidVersionCertificateAndServiceChangesInvalidateTheSelection() throws Exception {
        for (Entry changed : new Entry[]{
            new Entry(PACKAGE, TARGET.uid + 1, 7, TARGET.certificate, "Changed", SERVICE),
            new Entry(PACKAGE, TARGET.uid, 8, TARGET.certificate, "Changed", SERVICE),
            new Entry(PACKAGE, TARGET.uid, 7, "different-certificate", "Changed", SERVICE),
            new Entry(PACKAGE, TARGET.uid, 7, TARGET.certificate, "Changed", new ComponentName(PACKAGE, PACKAGE + ".Other"))}) {
            backend.current = changed;
            assertThrows(SecurityException.class, () -> backend.setAllowed(Kind.USAGE, TARGET, true));
            assertFalse(backend.allowed(Kind.USAGE, TARGET));
        }
        backend.current = null;
        assertThrows(SecurityException.class, () -> backend.setAllowed(Kind.USAGE, TARGET, true));
    }
    @Test public void accessibilityMergesTheLatestListAndLeavesTheMasterSettingAlone() throws Exception {
        String other = "com.other.app/.Service";
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, other);
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        assertTrue(backend.setAllowed(Kind.ACCESSIBILITY, TARGET, true));
        String current = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        assertTrue(current.contains(other)); assertTrue(SpecialAccessBackend.containsComponent(current, SERVICE));
        assertEquals(0, Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, -1));
        assertTrue(backend.setAllowed(Kind.ACCESSIBILITY, TARGET, false));
        assertEquals(other, Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
    }
    @Test public void accessibilityRemovalHandlesShortNamesWithoutDroppingOtherEntries() {
        String original = "com.other/.Service:com.example.selected/.Assist:unparsed-entry";
        assertEquals("com.other/.Service:unparsed-entry", SpecialAccessBackend.updateComponentList(original, SERVICE, false));
        String enabled = SpecialAccessBackend.updateComponentList(original, SERVICE, true);
        assertTrue(SpecialAccessBackend.containsComponent(enabled, SERVICE));
        assertEquals(enabled, SpecialAccessBackend.updateComponentList(enabled, SERVICE, true));
    }
}
