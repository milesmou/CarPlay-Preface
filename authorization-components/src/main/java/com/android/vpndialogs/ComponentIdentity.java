package com.android.vpndialogs;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Process;
import android.os.UserHandle;

/** Verifies the installed identity as well as the manifest declaration, never changes a UID. */
final class ComponentIdentity {
    static final String SHARED_USER = "android.uid.system";
    private static final int SYSTEM_APP_ID = 1000;

    private ComponentIdentity() { }

    static boolean isSystemIdentity(String sharedUser, int uid) throws Exception {
        if (!SHARED_USER.equals(sharedUser) || uid < 0) return false;
        int appId = (Integer) UserHandle.class.getMethod("getAppId", int.class).invoke(null, uid);
        return appId == SYSTEM_APP_ID;
    }

    static void requireSystemIdentity(Context context) throws Exception {
        PackageInfo own = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        if (own.applicationInfo == null || own.applicationInfo.uid != Process.myUid() ||
                !isSystemIdentity(own.sharedUserId, Process.myUid()))
            throw new SecurityException("system shared UID required; reinstall the old authorization component");
    }
}
