package com.android.vpndialogs;

import android.app.AppOpsManager;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

/** Read-only Android 9 Settings app-op query, scoped to one checked package and UID. */
final class AppOpsStateReader {
    interface Query {
        List<?> getOpsForPackage(int uid, String packageName, int[] operations) throws Exception;
    }

    private AppOpsStateReader() { }

    static int readMode(AppOpsManager manager, int uid, String packageName, int operation) throws Exception {
        if (manager == null) throw new IllegalStateException("app-ops service unavailable");
        return readMode(uid, packageName, operation, (targetUid, targetPackage, operations) -> {
            try {
                return (List<?>) AppOpsManager.class.getMethod("getOpsForPackage", int.class, String.class, int[].class)
                    .invoke(manager, targetUid, targetPackage, operations);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw failure;
            }
        });
    }

    static int readMode(int uid, String packageName, int operation, Query query) throws Exception {
        if (uid < 0 || packageName == null || packageName.isEmpty() || operation < 0)
            throw new SecurityException("invalid app-op target");
        List<?> records = query.getOpsForPackage(uid, packageName, new int[]{operation});
        // Native Settings starts at MODE_DEFAULT when there is no recorded package/op.
        if (records == null || records.isEmpty()) return AppOpsManager.MODE_DEFAULT;
        Class<?> packageOps = Class.forName("android.app.AppOpsManager$PackageOps");
        Class<?> opEntry = Class.forName("android.app.AppOpsManager$OpEntry");
        int mode = AppOpsManager.MODE_DEFAULT;
        boolean found = false;
        for (Object record : records) {
            if (record == null || !packageOps.isInstance(record) ||
                    (Integer) packageOps.getMethod("getUid").invoke(record) != uid ||
                    !packageName.equals(packageOps.getMethod("getPackageName").invoke(record)))
                throw new SecurityException("app-op package identity mismatch");
            List<?> entries = (List<?>) packageOps.getMethod("getOps").invoke(record);
            if (entries == null) throw new IllegalStateException("app-op entries unavailable");
            for (Object entry : entries) {
                if (entry == null || !opEntry.isInstance(entry) ||
                        (Integer) opEntry.getMethod("getOp").invoke(entry) != operation)
                    throw new SecurityException("app-op operation mismatch");
                if (found) throw new IllegalStateException("duplicate app-op state");
                mode = (Integer) opEntry.getMethod("getMode").invoke(entry);
                found = true;
            }
        }
        return mode;
    }
}
