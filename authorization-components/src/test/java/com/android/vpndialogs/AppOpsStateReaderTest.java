package com.android.vpndialogs;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = NativeAppOpsManagerShadow.class)
public class AppOpsStateReaderTest {
    private static final int UID = 10123;
    private static final String PACKAGE = "com.shihab.diplay.hudtest";
    private static final int WRITE = 23;

    private Object op(int operation, int mode) throws Exception {
        return Class.forName("android.app.AppOpsManager$OpEntry")
            .getConstructor(int.class, int.class, long.class, long.class, int.class, int.class, String.class)
            .newInstance(operation, mode, 0L, 0L, 0, -1, null);
    }

    private Object record(int uid, String packageName, Object... entries) throws Exception {
        return Class.forName("android.app.AppOpsManager$PackageOps")
            .getConstructor(String.class, int.class, List.class)
            .newInstance(packageName, uid, Arrays.asList(entries));
    }

    private int read(List<?> records) throws Exception {
        return AppOpsStateReader.readMode(UID, PACKAGE, WRITE, (uid, packageName, operations) -> {
            assertEquals(UID, uid); assertEquals(PACKAGE, packageName);
            assertArrayEquals(new int[]{WRITE}, operations);
            return records;
        });
    }

    @Test public void readsOnlyTheRequestedUidPackageAndOperationThroughTheManagerServiceFixture() throws Exception {
        AppOpsManager manager = RuntimeEnvironment.getApplication().getSystemService(AppOpsManager.class);
        AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class)
            .invoke(manager, WRITE, UID, PACKAGE, AppOpsManager.MODE_ALLOWED);
        assertEquals(AppOpsManager.MODE_ALLOWED, AppOpsStateReader.readMode(manager, UID, PACKAGE, WRITE));
        assertEquals(AppOpsManager.MODE_DEFAULT, AppOpsStateReader.readMode(manager, UID + 1, PACKAGE, WRITE));
        assertEquals(AppOpsManager.MODE_DEFAULT, AppOpsStateReader.readMode(manager, UID, "com.other.app", WRITE));
    }

    @Test public void absentOrEmptyRecordsKeepTheNativeDefault() throws Exception {
        assertEquals(AppOpsManager.MODE_DEFAULT, read(null));
        assertEquals(AppOpsManager.MODE_DEFAULT, read(Collections.emptyList()));
        assertEquals(AppOpsManager.MODE_DEFAULT, read(Collections.singletonList(record(UID, PACKAGE))));
    }

    @Test public void explicitAllowedDeniedAndDefaultModesArePreserved() throws Exception {
        for (int mode : new int[]{AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_IGNORED,
                AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_DEFAULT})
            assertEquals(mode, read(Collections.singletonList(record(UID, PACKAGE, op(WRITE, mode)))));
    }

    @Test public void mismatchedReturnedUidPackageOrOperationCannotGrantAccess() throws Exception {
        for (Object returned : new Object[]{record(UID + 1, PACKAGE, op(WRITE, AppOpsManager.MODE_ALLOWED)),
                record(UID, "com.other.app", op(WRITE, AppOpsManager.MODE_ALLOWED)),
                record(UID, PACKAGE, op(24, AppOpsManager.MODE_ALLOWED)), null})
            assertThrows(SecurityException.class, () -> read(Collections.singletonList(returned)));
        assertThrows(SecurityException.class, () -> read(Arrays.asList(record(UID, PACKAGE, op(WRITE, AppOpsManager.MODE_ALLOWED)),
            record(UID + 1, PACKAGE, op(WRITE, AppOpsManager.MODE_ALLOWED)))));
    }

    @Test public void duplicateStateIsNotReportedAsSuccessfulAuthorization() throws Exception {
        assertThrows(IllegalStateException.class, () -> read(Collections.singletonList(record(UID, PACKAGE,
            op(WRITE, AppOpsManager.MODE_ALLOWED), op(WRITE, AppOpsManager.MODE_ERRORED)))));
    }

    @Test public void missingServiceInterfaceAndDeniedQueriesFailInsteadOfInventingDefaultState() {
        assertThrows(IllegalStateException.class, () -> AppOpsStateReader.readMode((AppOpsManager) null, UID, PACKAGE, WRITE));
        assertThrows(NoSuchMethodException.class, () -> AppOpsStateReader.readMode(UID, PACKAGE, WRITE,
            (uid, packageName, operations) -> { throw new NoSuchMethodException("getOpsForPackage"); }));
        assertThrows(SecurityException.class, () -> AppOpsStateReader.readMode(UID, PACKAGE, WRITE,
            (uid, packageName, operations) -> { throw new SecurityException("GET_APP_OPS_STATS denied"); }));
    }

    @Test public void defaultModesUseStaticPermissionsWhileExplicitDenialStillWins() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String[] permissions = {Manifest.permission.WRITE_SETTINGS, Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.REQUEST_INSTALL_PACKAGES, Manifest.permission.PACKAGE_USAGE_STATS};
        int[] operations = {WRITE, 24, 66, 43};
        AuthorizationBackend.Target target = new AuthorizationBackend.Target(PACKAGE, UID, "CarPlay");
        SpecialAccessBackend.Entry entry = new SpecialAccessBackend.Entry(PACKAGE, UID, 1, "certificate", "CarPlay", null);
        for (boolean granted : new boolean[]{false, true}) {
            PackageInfo info = new PackageInfo();
            info.packageName = PACKAGE;
            info.applicationInfo = new ApplicationInfo();
            info.applicationInfo.packageName = PACKAGE;
            info.applicationInfo.uid = UID;
            info.requestedPermissions = permissions;
            info.requestedPermissionsFlags = new int[permissions.length];
            if (granted) Arrays.fill(info.requestedPermissionsFlags, PackageInfo.REQUESTED_PERMISSION_GRANTED);
            Shadows.shadowOf(context.getPackageManager()).installPackage(info);
            for (String permission : permissions) assertEquals(granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED,
                context.getPackageManager().checkPermission(permission, PACKAGE));
            for (int mode : new int[]{AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_ERRORED}) {
                for (int operation : operations) AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class)
                    .invoke(context.getSystemService(AppOpsManager.class), operation, UID, PACKAGE, mode);
                boolean expected = mode == AppOpsManager.MODE_ALLOWED || (mode == AppOpsManager.MODE_DEFAULT && granted);
                assertEquals(expected, new Android9Backend(context).writeAllowed(target));
                assertEquals(expected, new AdditionalAuthorizationBackend(context).overlayAllowed(target));
                assertEquals(expected, new SpecialAccessBackend(context).allowed(SpecialAccessBackend.Kind.INSTALL, entry));
                assertEquals(expected, new SpecialAccessBackend(context).allowed(SpecialAccessBackend.Kind.USAGE, entry));
            }
        }
    }

}
