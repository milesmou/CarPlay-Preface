import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded startup diagnostics: fixed templates and primitive values only. */
public final class Preface95StartupTrace {
    private static final AtomicInteger COUNT = new AtomicInteger();
    private Preface95StartupTrace() { }

    public static void onLog(String tag, String template, Object[] values) {
        try {
            if (!"Eaglet".equals(tag) || template == null || template.length() > 180 ||
                    !template.matches("[A-Za-z0-9_ :.,{}?=()<>-]+") ||
                    !(template.startsWith("mInitAppReceiver:") || template.startsWith("handle") ||
                      template.startsWith("onEaglet") || template.startsWith("onInit") ||
                      template.startsWith("isFirstRunUi") || template.startsWith("StartUpData") ||
                      template.startsWith("startAutoService") || template.startsWith("isBackground") ||
                      template.startsWith("getBoolFunConfig") || template.startsWith("loadCode") ||
                      template.startsWith("initAuto") || template.startsWith("checkPermissions") ||
                      template.startsWith("hasPermission"))) return;
            if (COUNT.incrementAndGet() > 120) return;
            StringBuilder message = new StringBuilder(template);
            if (values != null) for (int i = 0; i < Math.min(values.length, 6); i++) {
                Object value = values[i];
                message.append(' ').append(i).append('=');
                if (value instanceof Boolean || value instanceof Integer || value instanceof Long)
                    message.append(value);
                else message.append("<omitted>");
            }
            Log.i("Preface95Startup", message.toString());
        } catch (RuntimeException ignored) { /* Diagnostics do not control startup. */ }
    }
}
