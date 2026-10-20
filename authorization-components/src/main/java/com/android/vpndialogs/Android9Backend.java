package com.android.vpndialogs;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Android 9 interfaces checked against the cached E02 firmware and AOSP android-9.0.0_r1. */
class Android9Backend implements AuthorizationBackend {
    static final String PLATFORM_CERT = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";
    static final String CARPLAY_CERT = "75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8";
    static final String[] PACKAGES = {"com.shihab.diplay", "com.shihab.diplay.hudtest"};
    private final Context context;
    Android9Backend(Context context) { this.context = context; }

    static boolean supportedPackage(String name) {
        // The Bluetooth music proxy remains restricted to the existing CarPlay clients.
        return Arrays.asList(PACKAGES).contains(name);
    }

    static String certificate(PackageInfo info) throws Exception {
        if (info.signatures == null || info.signatures.length != 1) throw new SecurityException("signer count");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray());
        StringBuilder value = new StringBuilder();
        for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private String certificate(String name) throws Exception {
        return certificate(context.getPackageManager().getPackageInfo(name, PackageManager.GET_SIGNATURES));
    }

    @Override public void checkEnvironment(boolean vpn) throws Exception {
        if (Build.VERSION.SDK_INT != 28) throw new SecurityException("Android 9 required; actual API=" + Build.VERSION.SDK_INT);
        if (!PLATFORM_CERT.equals(certificate("android"))) throw new SecurityException("platform certificate mismatch");
        if (!PLATFORM_CERT.equals(certificate(context.getPackageName()))) throw new SecurityException("component certificate mismatch");
        ComponentIdentity.requireSystemIdentity(context);
        checkPermissions(vpn);
    }

    void checkPermissions(boolean vpn) {
        String[] permissions = vpn ? new String[]{"android.permission.CONTROL_VPN", "android.permission.CONNECTIVITY_INTERNAL", "android.permission.GET_APP_OPS_STATS"}
            : new String[]{"android.permission.MANAGE_APP_OPS_MODES", "android.permission.GET_APP_OPS_STATS"};
        for (String permission : permissions) if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("component permission missing: " + permission);
    }

    static String identityCertificate(PackageInfo info) throws Exception {
        if (info.signatures == null || info.signatures.length == 0) throw new SecurityException("missing signer");
        List<String> digests = new ArrayList<>();
        for (android.content.pm.Signature signature : info.signatures) {
            StringBuilder digest = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))
                digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            digests.add(digest.toString());
        }
        Collections.sort(digests);
        return android.text.TextUtils.join(":", digests);
    }

    Target installedTarget(String name, String permission, boolean vpn) throws Exception {
        if (name == null || name.isEmpty()) throw new SecurityException("missing target");
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES | PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES);
        if (info.applicationInfo == null || !info.applicationInfo.enabled ||
                (info.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0 ||
                (info.applicationInfo.flags & ApplicationInfo.FLAG_SUSPENDED) != 0 ||
                !UserHandle.getUserHandleForUid(info.applicationInfo.uid).equals(Process.myUserHandle())) {
            throw new SecurityException("application unavailable for current user");
        }
        if (vpn) {
            boolean found = false;
            if (info.services != null) for (ServiceInfo service : info.services)
                // Android 9 resolves and binds the explicit service as the system. It need not
                // be exported to other apps (CarPlay deliberately keeps its local bridge private).
                if (service.enabled && Manifest.permission.BIND_VPN_SERVICE.equals(service.permission)) {
                    int state = pm.getComponentEnabledSetting(new android.content.ComponentName(name, service.name));
                    if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
                            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) found = true;
                }
            if (!found) throw new SecurityException("VPN service missing");
        } else if (info.requestedPermissions == null || !Arrays.asList(info.requestedPermissions).contains(permission)) {
            throw new SecurityException("target permission not declared: " + permission);
        }
        return new Target(name, info.applicationInfo.uid, pm.getApplicationLabel(info.applicationInfo),
            info.getLongVersionCode(), identityCertificate(info));
    }

    @Override public Target target(String name, boolean vpn) throws Exception {
        return installedTarget(name, Manifest.permission.WRITE_SETTINGS, vpn);
    }

    private void recheck(Target expected, boolean vpn) throws Exception {
        checkEnvironment(vpn);
        if (!expected.sameIdentity(target(expected.packageName, vpn))) throw new SecurityException("target changed");
    }

    private int user() throws Exception {
        return (Integer) UserHandle.class.getMethod("myUserId").invoke(null);
    }

    private Object connectivity() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, Context.CONNECTIVITY_SERVICE);
        if (binder == null) throw new IllegalStateException("connectivity unavailable");
        return Class.forName("android.net.IConnectivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    Object vpnCall(String name, Class<?>[] types, Object... arguments) throws Exception {
        return Class.forName("android.net.IConnectivityManager").getMethod(name, types).invoke(connectivity(), arguments);
    }

    @Override public boolean vpnBlocked(Target target) throws Exception {
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || users.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)) return true;
        String alwaysOn = (String) vpnCall("getAlwaysOnVpnPackage", new Class<?>[]{int.class}, user());
        return alwaysOn != null && !alwaysOn.equals(target.packageName);
    }

    @Override public boolean vpnAuthorized(Target target) throws Exception {
        // prepareVpn(target, null) checks Binder.getCallingUid(), which is THIS component's UID.
        // It is not a read-only query and can unprepare the target. Read its actual app-op instead.
        return appOpMode("OP_ACTIVATE_VPN", target.uid, target.packageName) == AppOpsManager.MODE_ALLOWED;
    }

    @Override public boolean allowVpn(Target target) throws Exception {
        recheck(target, true);
        if (vpnBlocked(target)) return false;
        if (!(Boolean) vpnCall("prepareVpn", new Class<?>[]{String.class, String.class, int.class}, null, target.packageName, user())) return false;
        recheck(target, true);
        vpnCall("setVpnPackageAuthorization", new Class<?>[]{String.class, int.class, boolean.class}, target.packageName, user(), true);
        // Read back the target's app-op without preparing a VPN under the helper's UID.
        // The requesting app can recheck VpnService.prepare() under its own UID after RESULT_OK.
        recheck(target, true);
        return vpnAuthorized(target);
    }

    final int appOpMode(String operationField, int uid, String packageName) throws Exception {
        int operation = AppOpsManager.class.getField(operationField).getInt(null);
        return AppOpsStateReader.readMode(context.getSystemService(AppOpsManager.class), uid, packageName, operation);
    }

    @Override public boolean writeAllowed(Target target) throws Exception {
        int mode = appOpMode("OP_WRITE_SETTINGS", target.uid, target.packageName);
        return mode == AppOpsManager.MODE_ALLOWED || (mode == AppOpsManager.MODE_DEFAULT &&
            context.getPackageManager().checkPermission(Manifest.permission.WRITE_SETTINGS, target.packageName) == PackageManager.PERMISSION_GRANTED);
    }

    @Override public boolean setWriteAllowed(Target target, boolean allowed) throws Exception {
        recheck(target, false);
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || users.hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL))
            throw new SecurityException("system policy restriction");
        AppOpsManager manager = context.getSystemService(AppOpsManager.class);
        int operation = AppOpsManager.class.getField("OP_WRITE_SETTINGS").getInt(null);
        AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class)
            .invoke(manager, operation, target.uid, target.packageName, allowed ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_ERRORED);
        recheck(target, false);
        return writeAllowed(target) == allowed;
    }
}
