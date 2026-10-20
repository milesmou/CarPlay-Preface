package com.android.vpndialogs;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/** Robolectric API28 renders at 1920x720; these are not installed-APK or vehicle screenshots. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi", shadows = NativeAppOpsManagerShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ThirdPartyAuthorizationUiTest {
    private static final String PACKAGE = "com.example.thirdparty.navigation";
    private static final String LABEL = "第三方导航与工具";
    private static final File OUTPUT = new File("../agent_docs/screenshots/authorization-other-apps-test-renders");
    public static class TestActivity extends SpecialAccessActivity {
        @Override protected SpecialAccessBackend createBackend() {
            return new SpecialAccessBackend(this) { @Override public void checkEnvironment(boolean vpn) { } };
        }
    }
    @Test public void fullLandscapeLargeFont() throws Exception { capture("full-font150", 160); }
    @Test @Config(qualifiers = "zh-rCN-w640dp-h240dp-land-xxhdpi")
    public void compactLandscapeLargeFont() throws Exception { capture("compact-font150", 480); }

    private void capture(String name, int density) throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(app.getResources().getConfiguration());
        configuration.fontScale = 1.5f;

        app.getResources().updateConfiguration(configuration, app.getResources().getDisplayMetrics());
        PackageInfo info = new PackageInfo(); info.packageName = PACKAGE; info.versionCode = 1;
        info.signatures = new Signature[]{new Signature("1234")};
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.packageName = PACKAGE;
        info.applicationInfo.nonLocalizedLabel = LABEL; info.applicationInfo.uid = 10234;
        info.applicationInfo.enabled = true; info.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        info.requestedPermissions = new String[]{android.Manifest.permission.WRITE_SETTINGS, android.Manifest.permission.SYSTEM_ALERT_WINDOW};
        info.requestedPermissionsFlags = new int[info.requestedPermissions.length];
        Shadows.shadowOf(app.getPackageManager()).installPackage(info);
        OUTPUT.mkdirs();
        for (SpecialAccessBackend.Kind kind : new SpecialAccessBackend.Kind[]{SpecialAccessBackend.Kind.WRITE, SpecialAccessBackend.Kind.OVERLAY}) {
            ActivityController<TestActivity> controller = Robolectric.buildActivity(TestActivity.class, new Intent(kind.action)).setup();
            try {
                TestActivity activity = controller.get();
                Button select = awaitButton(activity, LABEL + "\n" + PACKAGE);
                assertTrue(select.getFilterTouchesWhenObscured());
                render(activity, kind.name().toLowerCase(Locale.ROOT) + "-list-" + name);
                select.performClick();
                Button allow = AuthorizationConsentTest.button(activity.getWindow().getDecorView(), "允许");
                assertNotNull(allow);
                render(activity, kind.name().toLowerCase(Locale.ROOT) + "-confirm-" + name);
                Button cancel = AuthorizationConsentTest.button(activity.getWindow().getDecorView(), "取消");
                assertNotNull(cancel); assertEquals(allow.getHeight(), cancel.getHeight());
                assertTrue(allow.getHeight() >= Math.round(64 * density / 160f));
                ScrollView scroll = scrollView(activity.getWindow().getDecorView());
                scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                for (Button button : new Button[]{allow, cancel}) {
                    int[] position = new int[2]; button.getLocationOnScreen(position);
                    assertTrue("Confirmation actions must remain reachable", position[1] >= 0 && position[1] + button.getHeight() <= 720);
                }
                render(activity, kind.name().toLowerCase(Locale.ROOT) + "-actions-" + name);
                SpecialAccessBackend backend = (SpecialAccessBackend) activity.backend;
                SpecialAccessBackend.Entry entry = backend.entries(kind, PACKAGE).get(0);
                boolean beforeCancel = backend.allowed(kind, entry);
                cancel.performClick();
                assertEquals(beforeCancel, backend.allowed(kind, entry));
                assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
            } finally { controller.pause().stop().destroy(); }
        }
        Files.write(new File(OUTPUT, name + ".json").toPath(),
            ("{\"source\":\"Robolectric/API28 test render; not installed APK\",\"width\":1920,\"height\":720," +
                "\"densityDpi\":" + density + ",\"fontScale\":1.5}").getBytes(StandardCharsets.UTF_8));
    }
    private Button awaitButton(Activity activity, String text) throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            ShadowLooper.idleMainLooper();
            Button button = AuthorizationConsentTest.button(activity.getWindow().getDecorView(), text);
            if (button != null) return button;
            Thread.sleep(10);
        }
        throw new AssertionError("Application selector did not load");
    }
    private void render(Activity activity, String name) throws Exception {
        View decor = activity.getWindow().getDecorView();
        decor.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1920, 720);
        Bitmap bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(OUTPUT, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
    private ScrollView scrollView(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            ScrollView found = scrollView(((ViewGroup) view).getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }
}
