package com.android.vpndialogs;

import static org.junit.Assert.*;
import android.app.AppOpsManager;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = NativeAppOpsManagerShadow.class)
public class Android9VpnAuthorizationTest {
    private static final AuthorizationBackend.Target TARGET = new AuthorizationBackend.Target("com.preface.carplay", 10123, "CarPlay");
    private Context context;
    private Backend backend;
    private class Backend extends Android9Backend {
        final List<String> calls = new ArrayList<>();
        boolean canPrepare = true;
        boolean blocked;
        Backend(Context context) { super(context); }
        @Override public void checkEnvironment(boolean vpn) { }
        @Override public Target target(String name, boolean vpn) { return TARGET; }
        @Override public boolean vpnBlocked(Target target) { return blocked; }
        @Override Object vpnCall(String name, Class<?>[] types, Object... args) throws Exception {
            calls.add(name);
            if (name.equals("prepareVpn")) {
                assertNull("A helper-UID prepare(oldPackage, null) would mischeck or revoke the target", args[0]);
                assertEquals(TARGET.packageName, args[1]);
                return canPrepare;
            }
            if (name.equals("setVpnPackageAuthorization")) {
                assertEquals(TARGET.packageName, args[0]); assertEquals(true, args[2]);
                setMode(TARGET.uid, AppOpsManager.MODE_ALLOWED);
                return null;
            }
            throw new AssertionError("Unexpected VPN call: " + name);
        }
    }
    private int operation() throws Exception { return AppOpsManager.class.getField("OP_ACTIVATE_VPN").getInt(null); }
    private void setMode(int uid, int mode) throws Exception {
        AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class)
            .invoke(context.getSystemService(AppOpsManager.class), operation(), uid, TARGET.packageName, mode);
    }
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication(); backend = new Backend(context);
        setMode(TARGET.uid, AppOpsManager.MODE_IGNORED);
    }
    @Test public void authorizationQueryUsesTheTargetsUidWithoutPreparingOrRevokingIt() throws Exception {
        assertFalse(backend.vpnAuthorized(TARGET));
        setMode(TARGET.uid, AppOpsManager.MODE_ALLOWED);
        assertTrue(backend.vpnAuthorized(TARGET));
        assertTrue(backend.calls.isEmpty());
    }
    @Test public void confirmingConsentDoesNotRecheckPrepareUnderTheHelperUid() throws Exception {
        assertTrue(backend.allowVpn(TARGET));
        assertEquals(java.util.Arrays.asList("prepareVpn", "setVpnPackageAuthorization"), backend.calls);
        assertTrue(backend.vpnAuthorized(TARGET));
        assertEquals(2, backend.calls.size());
    }
    @Test public void anotherUidsAllowedModeDoesNotCountAsTargetAuthorization() throws Exception {
        setMode(TARGET.uid + 1, AppOpsManager.MODE_ALLOWED);
        assertFalse(backend.vpnAuthorized(TARGET));
    }
    @Test public void defaultVpnModeDoesNotCountAsAuthorization() throws Exception {
        setMode(TARGET.uid, AppOpsManager.MODE_DEFAULT);
        assertFalse(backend.vpnAuthorized(TARGET));
        assertTrue(backend.calls.isEmpty());
    }
    @Test public void failedPreparationDoesNotAuthorize() throws Exception {
        backend.canPrepare = false;
        assertFalse(backend.allowVpn(TARGET));
        assertEquals(java.util.Arrays.asList("prepareVpn"), backend.calls);
        assertFalse(backend.vpnAuthorized(TARGET));
    }
    @Test public void policyRestrictionCannotBeBypassedByConfirmation() throws Exception {
        backend.blocked = true;
        assertFalse(backend.allowVpn(TARGET));
        assertTrue(backend.calls.isEmpty());
    }
}
