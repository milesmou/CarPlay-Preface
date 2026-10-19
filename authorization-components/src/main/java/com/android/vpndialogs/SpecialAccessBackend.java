package com.android.vpndialogs;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Fixed-purpose, current-user special access. Other applications are selected by the user. */
class SpecialAccessBackend extends Android9Backend {
    enum Kind {
        WRITE(Settings.ACTION_MANAGE_WRITE_SETTINGS, R.string.write_title, R.string.write_body),
        OVERLAY(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, R.string.overlay_title, R.string.overlay_body),
        INSTALL(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, R.string.special_install_title, R.string.special_install_body),
        USAGE(Settings.ACTION_USAGE_ACCESS_SETTINGS, R.string.special_usage_title, R.string.special_usage_body),
        ACCESSIBILITY(Settings.ACTION_ACCESSIBILITY_SETTINGS, R.string.special_accessibility_title, R.string.special_accessibility_body);

        final String action;
        final int title, body;
        Kind(String action, int title, int body) { this.action = action; this.title = title; this.body = body; }
        static Kind fromAction(String action) {
            for (Kind kind : values()) if (kind.action.equals(action)) return kind;
            throw new SecurityException("unsupported special access");
        }
    }

    static final class Entry {
        final String packageName, certificate, label;
        final int uid;
        final long version;
        final ComponentName service;
        Entry(String name, int uid, long version, String certificate, String label, ComponentName service) {
            this.packageName = name; this.uid = uid; this.version = version;
            this.certificate = certificate; this.label = label; this.service = service;
        }
        boolean sameIdentity(Entry other) {
            return packageName.equals(other.packageName) && uid == other.uid && version == other.version &&
                certificate.equals(other.certificate) && java.util.Objects.equals(service, other.service);
        }
    }

    private static final Object ACCESSIBILITY_LOCK = new Object();
    private final Context context;
    SpecialAccessBackend(Context context) { super(context); this.context = context; }

    void checkSpecialEnvironment(Kind kind) throws Exception {
        checkEnvironment(false);
        if (kind == Kind.ACCESSIBILITY) {
            for (String permission : new String[]{"android.permission.WRITE_SECURE_SETTINGS", "android.permission.MANAGE_USERS"})
                if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("accessibility management permission missing");
        }
    }

    private static String requiredPermission(Kind kind) {
        if (kind == Kind.WRITE) return Manifest.permission.WRITE_SETTINGS;
        if (kind == Kind.OVERLAY) return Manifest.permission.SYSTEM_ALERT_WINDOW;
        if (kind == Kind.INSTALL) return Manifest.permission.REQUEST_INSTALL_PACKAGES;
        if (kind == Kind.USAGE) return Manifest.permission.PACKAGE_USAGE_STATS;
        throw new SecurityException("not an application app-op");
    }

    private Entry entry(String name, ComponentName service, CharSequence serviceLabel) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES | PackageManager.GET_PERMISSIONS);
        ApplicationInfo app = info.applicationInfo;
        if (app == null || !app.enabled || (app.flags & ApplicationInfo.FLAG_INSTALLED) == 0 ||
                (app.flags & ApplicationInfo.FLAG_SUSPENDED) != 0 ||
                !UserHandle.getUserHandleForUid(app.uid).equals(Process.myUserHandle()))
            throw new SecurityException("application unavailable for current user");
        String label = pm.getApplicationLabel(app).toString();
        if (serviceLabel != null) label += " · " + serviceLabel;
        return new Entry(name, app.uid, info.getLongVersionCode(), identityCertificate(info), label, service);
    }

    List<Entry> entries(Kind kind, String selectedPackage) throws Exception {
        checkSpecialEnvironment(kind);
        List<Entry> result = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        if (kind == Kind.ACCESSIBILITY) {
            AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
            if (manager == null) throw new IllegalStateException("accessibility unavailable");
            for (AccessibilityServiceInfo accessibility : manager.getInstalledAccessibilityServiceList()) {
                ServiceInfo service = accessibility.getResolveInfo().serviceInfo;
                if (!service.enabled || !service.exported || !Manifest.permission.BIND_ACCESSIBILITY_SERVICE.equals(service.permission) ||
                        (selectedPackage != null && !selectedPackage.equals(service.packageName))) continue;
                try {
                    ComponentName component = new ComponentName(service.packageName, service.name);
                    int state = pm.getComponentEnabledSetting(component);
                    if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) continue;
                    result.add(entry(service.packageName, component, service.loadLabel(pm)));
                } catch (PackageManager.NameNotFoundException | SecurityException ignored) { /* Skip unavailable identities. */ }
            }
        } else {
            List<PackageInfo> packages = selectedPackage == null ? pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                : Collections.singletonList(pm.getPackageInfo(selectedPackage, PackageManager.GET_PERMISSIONS));
            for (PackageInfo info : packages) {
                if (info.requestedPermissions == null || !Arrays.asList(info.requestedPermissions).contains(requiredPermission(kind))) continue;
                try { result.add(entry(info.packageName, null, null)); }
                catch (PackageManager.NameNotFoundException | SecurityException ignored) { /* Skip unavailable identities. */ }
            }
        }
        result.sort(Comparator.comparing((Entry value) -> value.label).thenComparing(value -> value.packageName));
        return result;
    }

    private Entry recheck(Kind kind, Entry expected) throws Exception {
        for (Entry actual : entries(kind, expected.packageName)) if (expected.sameIdentity(actual)) return actual;
        throw new SecurityException("selected application or service changed");
    }

    boolean blocked(Kind kind, Entry entry) throws Exception {
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || users.hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL)) return true;
        if (kind == Kind.OVERLAY && users.hasUserRestriction(UserManager.DISALLOW_CREATE_WINDOWS)) return true;
        if (kind == Kind.INSTALL && (users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS) ||
                users.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
                users.hasUserRestriction("no_install_unknown_sources_globally"))) return true;
        if (kind == Kind.ACCESSIBILITY) {
            DevicePolicyManager policy = context.getSystemService(DevicePolicyManager.class);
            if (policy == null) throw new IllegalStateException("device policy unavailable");
            int userId = (Integer) UserHandle.class.getMethod("myUserId").invoke(null);
            @SuppressWarnings("unchecked")
            List<String> permitted = (List<String>) DevicePolicyManager.class.getMethod("getPermittedAccessibilityServices", int.class).invoke(policy, userId);
            return permitted != null && !permitted.contains(entry.packageName);
        }
        return false;
    }

    private static String operationField(Kind kind) {
        switch (kind) {
            case WRITE: return "OP_WRITE_SETTINGS";
            case OVERLAY: return "OP_SYSTEM_ALERT_WINDOW";
            case INSTALL: return "OP_REQUEST_INSTALL_PACKAGES";
            case USAGE: return "OP_GET_USAGE_STATS";
            default: throw new SecurityException("not an application app-op");
        }
    }

    boolean allowed(Kind kind, Entry entry) throws Exception {
        if (kind == Kind.ACCESSIBILITY)
            return containsComponent(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), entry.service);
        int mode = appOpMode(operationField(kind), entry.uid, entry.packageName);
        return mode == AppOpsManager.MODE_ALLOWED || (mode == AppOpsManager.MODE_DEFAULT &&
            context.getPackageManager().checkPermission(requiredPermission(kind), entry.packageName) == PackageManager.PERMISSION_GRANTED);
    }

    boolean setAllowed(Kind kind, Entry expected, boolean value) throws Exception {
        Entry actual = recheck(kind, expected);
        if (blocked(kind, actual)) throw new SecurityException("system policy restriction");
        if (allowed(kind, actual) == value) return true;
        if (kind == Kind.ACCESSIBILITY) {
            synchronized (ACCESSIBILITY_LOCK) {
                // Merge the latest list. Do not overwrite unrelated services or the system's master state.
                String current = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (!Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        updateComponentList(current, actual.service, value))) return false;
            }
        } else {
            int mode = value ? AppOpsManager.MODE_ALLOWED : kind == Kind.USAGE ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ERRORED;
            int operation = AppOpsManager.class.getField(operationField(kind)).getInt(null);
            AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class).invoke(
                context.getSystemService(AppOpsManager.class), operation, actual.uid, actual.packageName, mode);
        }
        // A package update during the operation is not reported as a successful grant to the selected identity.
        actual = recheck(kind, expected);
        return allowed(kind, actual) == value;
    }

    static boolean containsComponent(String list, ComponentName selected) {
        if (list == null || selected == null) return false;
        for (String item : list.split(":")) if (selected.equals(ComponentName.unflattenFromString(item))) return true;
        return false;
    }

    static String updateComponentList(String list, ComponentName selected, boolean enabled) {
        if (selected == null) throw new SecurityException("missing service");
        List<String> result = new ArrayList<>();
        if (list != null) for (String item : list.split(":"))
            if (!item.isEmpty() && !selected.equals(ComponentName.unflattenFromString(item))) result.add(item);
        if (enabled) result.add(selected.flattenToString());
        return android.text.TextUtils.join(":", result);
    }
}
