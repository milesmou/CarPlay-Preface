package com.android.vpndialogs;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import java.security.MessageDigest;
import java.util.Arrays;

/** Android 9 interfaces checked against the cached E02 firmware and AOSP android-9.0.0_r1. */
final class Android9Backend implements AuthorizationBackend {
    static final String PLATFORM_CERT = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";
    static final String CARPLAY_CERT = "75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8";
    static final String[] PACKAGES = {"com.shihab.diplay", "com.shihab.diplay.hudtest"};
    private final Context context;
    Android9Backend(Context context) { this.context = context; }

    static boolean supportedPackage(String name) {
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
        if (Build.VERSION.SDK_INT != 28 || !PLATFORM_CERT.equals(certificate("android")) ||
                !PLATFORM_CERT.equals(certificate(context.getPackageName()))) {
            throw new SecurityException("firmware signature or API mismatch");
        }
        String[] permissions = vpn ? new String[]{"android.permission.CONTROL_VPN", "android.permission.CONNECTIVITY_INTERNAL"}
            : new String[]{"android.permission.MANAGE_APP_OPS_MODES", "android.permission.GET_APP_OPS_STATS"};
        for (String permission : permissions) if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("component permission missing");
    }

    @Override public Target target(String name, boolean vpn) throws Exception {
        if (!supportedPackage(name)) throw new SecurityException("unsupported target");
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES | PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES);
        if (!CARPLAY_CERT.equals(certificate(info)) || !info.applicationInfo.enabled ||
                !UserHandle.getUserHandleForUid(info.applicationInfo.uid).equals(Process.myUserHandle())) {
            throw new SecurityException("target identity mismatch");
        }
        if (vpn) {
            boolean found = false;
            if (info.services != null) for (ServiceInfo service : info.services)
                if (service.enabled && Manifest.permission.BIND_VPN_SERVICE.equals(service.permission)) found = true;
            if (!found) throw new SecurityException("VPN service missing");
        } else if (info.requestedPermissions == null || !Arrays.asList(info.requestedPermissions).contains(Manifest.permission.WRITE_SETTINGS)) {
            throw new SecurityException("WRITE_SETTINGS not declared");
        }
        return new Target(name, info.applicationInfo.uid, pm.getApplicationLabel(info.applicationInfo));
    }

    private void recheck(Target expected, boolean vpn) throws Exception {
        checkEnvironment(vpn);
        if (target(expected.packageName, vpn).uid != expected.uid) throw new SecurityException("target changed");
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

    private Object vpnCall(String name, Class<?>[] types, Object... arguments) throws Exception {
        return Class.forName("android.net.IConnectivityManager").getMethod(name, types).invoke(connectivity(), arguments);
    }

    @Override public boolean vpnBlocked(Target target) throws Exception {
        if (context.getSystemService(UserManager.class).hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)) return true;
        String alwaysOn = (String) vpnCall("getAlwaysOnVpnPackage", new Class<?>[]{int.class}, user());
        return alwaysOn != null && !alwaysOn.equals(target.packageName);
    }

    @Override public boolean vpnPrepared(Target target) throws Exception {
        return (Boolean) vpnCall("prepareVpn", new Class<?>[]{String.class, String.class, int.class}, target.packageName, null, user());
    }

    @Override public boolean allowVpn(Target target) throws Exception {
        recheck(target, true);
        if (vpnBlocked(target)) return false;
        if (!(Boolean) vpnCall("prepareVpn", new Class<?>[]{String.class, String.class, int.class}, null, target.packageName, user())) return false;
        vpnCall("setVpnPackageAuthorization", new Class<?>[]{String.class, int.class, boolean.class}, target.packageName, user(), true);
        return vpnPrepared(target);
    }

    @Override public boolean writeAllowed(Target target) {
        int mode = context.getSystemService(AppOpsManager.class).checkOpNoThrow(AppOpsManager.OPSTR_WRITE_SETTINGS, target.uid, target.packageName);
        return mode == AppOpsManager.MODE_ALLOWED || (mode == AppOpsManager.MODE_DEFAULT &&
            context.getPackageManager().checkPermission(Manifest.permission.WRITE_SETTINGS, target.packageName) == PackageManager.PERMISSION_GRANTED);
    }

    @Override public boolean setWriteAllowed(Target target, boolean allowed) throws Exception {
        recheck(target, false);
        AppOpsManager manager = context.getSystemService(AppOpsManager.class);
        int operation = AppOpsManager.class.getField("OP_WRITE_SETTINGS").getInt(null);
        AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class)
            .invoke(manager, operation, target.uid, target.packageName, allowed ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_ERRORED);
        return writeAllowed(target) == allowed;
    }
}
