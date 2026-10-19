package com.android.vpndialogs;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class Android9AppOpsPermissionTest {
    private static final String READ_PERMISSION = "android.permission.GET_APP_OPS_STATS";

    @Test public void manifestRequestsPermissionRequiredByNativeSettingsQueries() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String[] requested = context.getPackageManager().getPackageInfo(context.getPackageName(),
            PackageManager.GET_PERMISSIONS).requestedPermissions;
        assertNotNull(requested);
        assertTrue(java.util.Arrays.asList(requested).contains(READ_PERMISSION));
    }

    private Android9Backend backend(String deniedPermission) {
        return new Android9Backend(new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public int checkSelfPermission(String permission) {
                return permission.equals(deniedPermission)
                    ? PackageManager.PERMISSION_DENIED : PackageManager.PERMISSION_GRANTED;
            }
        });
    }

    @Test public void bothVpnAndSettingsRejectMissingReadPermissionBeforeReadingTargetState() {
        for (boolean vpn : new boolean[]{false, true}) {
            SecurityException failure = assertThrows(SecurityException.class, () -> backend(READ_PERMISSION).checkPermissions(vpn));
            assertTrue(failure.getMessage().contains(READ_PERMISSION));
        }
    }

    @Test public void nativeReadDoesNotRequireTheOldCheckOperationPermission() {
        backend("android.permission.UPDATE_APP_OPS_STATS").checkPermissions(false);
        backend("android.permission.UPDATE_APP_OPS_STATS").checkPermissions(true);
    }

    @Test public void eachWritePrivilegeIsStillRequired() {
        assertThrows(SecurityException.class, () -> backend("android.permission.MANAGE_APP_OPS_MODES").checkPermissions(false));
        assertThrows(SecurityException.class, () -> backend("android.permission.CONTROL_VPN").checkPermissions(true));
        assertThrows(SecurityException.class, () -> backend("android.permission.CONNECTIVITY_INTERNAL").checkPermissions(true));
    }

    @Test public void grantedPermissionsPermitBothChecks() {
        backend(null).checkPermissions(false);
        backend(null).checkPermissions(true);
    }
}
