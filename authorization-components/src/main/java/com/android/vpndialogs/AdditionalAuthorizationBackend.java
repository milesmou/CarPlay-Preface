package com.android.vpndialogs;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserManager;

/** App-scoped replacement for the Android 9 overlay permission page. */
class AdditionalAuthorizationBackend extends Android9Backend {
    private final Context context;
    AdditionalAuthorizationBackend(Context context) { super(context); this.context = context; }

    void checkOverlayEnvironment() throws Exception {
        checkEnvironment(false);
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || users.hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL) || users.hasUserRestriction(UserManager.DISALLOW_CREATE_WINDOWS))
            throw new SecurityException("system policy restriction");
    }

    Target overlayTarget(String name) throws Exception {
        return installedTarget(name, Manifest.permission.SYSTEM_ALERT_WINDOW, false);
    }

    boolean overlayAllowed(Target target) throws Exception {
        int mode = appOpMode("OP_SYSTEM_ALERT_WINDOW", target.uid, target.packageName);
        return mode == AppOpsManager.MODE_ALLOWED || (mode == AppOpsManager.MODE_DEFAULT &&
            context.getPackageManager().checkPermission(Manifest.permission.SYSTEM_ALERT_WINDOW, target.packageName) == PackageManager.PERMISSION_GRANTED);
    }

    boolean setOverlayAllowed(Target expected, boolean value) throws Exception {
        checkOverlayEnvironment();
        Target actual = overlayTarget(expected.packageName);
        if (!expected.sameIdentity(actual)) throw new SecurityException("target changed");
        AppOpsManager.class.getMethod("setMode", String.class, int.class, String.class, int.class).invoke(
            context.getSystemService(AppOpsManager.class), AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            actual.uid, actual.packageName, value ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_ERRORED);
        if (!expected.sameIdentity(overlayTarget(expected.packageName))) throw new SecurityException("target changed");
        return overlayAllowed(actual) == value;
    }
}
