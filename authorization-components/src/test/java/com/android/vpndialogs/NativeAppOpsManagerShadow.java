package com.android.vpndialogs;

import android.app.AppOpsManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowAppOpsManager;

/**
 * Android 9 service fixture for mode readback. Robolectric 4.17's getOpsForPackage()
 * uses only noted operations and hardcodes their mode to ALLOWED, independently of
 * its setMode() store. Keep its real mode store and expose the written records as
 * native AppOpsService does; never use this fixture to claim device authorization.
 */
@Implements(AppOpsManager.class)
public class NativeAppOpsManagerShadow extends ShadowAppOpsManager {
    private static final Map<String, Set<Integer>> recorded = new HashMap<>();

    @Resetter public static void resetRecordedModes() { recorded.clear(); }

    @Implementation public void setMode(int operation, int uid, String packageName, int mode) {
        super.setMode(operation, uid, packageName, mode);
        recorded.computeIfAbsent(uid + ":" + packageName, ignored -> new LinkedHashSet<>()).add(operation);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Implementation public List getOpsForPackage(int uid, String packageName, int[] operations) {
        List<Object> entries = new ArrayList<>();
        try {
            for (int operation : recorded.getOrDefault(uid + ":" + packageName, Collections.emptySet())) {
                boolean selected = operations == null;
                if (operations != null) for (int requested : operations) if (requested == operation) selected = true;
                if (!selected) continue;
                int mode = super.checkOpNoThrow(operation, uid, packageName);
                entries.add(Class.forName("android.app.AppOpsManager$OpEntry")
                    .getConstructor(int.class, int.class, long.class, long.class, int.class, int.class, String.class)
                    .newInstance(operation, mode, 0L, 0L, 0, -1, null));
            }
            if (entries.isEmpty()) return null;
            return Collections.singletonList(Class.forName("android.app.AppOpsManager$PackageOps")
                .getConstructor(String.class, int.class, List.class).newInstance(packageName, uid, entries));
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Android 9 test fixture unavailable", failure); }
    }
}
