package com.android.vpndialogs;

import static org.junit.Assert.*;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Test renders only: fake backend, never installed-APK or vehicle screenshots. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AuthorizationLayoutTest {
    @Test public void fullLandscapeLargeFont() throws Exception { capture("full-font150", 160, false); }
    @Test @Config(qualifiers = "zh-rCN-w640dp-h240dp-land-xxhdpi")
    public void compactLandscapeLargeFont() throws Exception { capture("compact-font150", 480, false); }
    @Test @Config(qualifiers = "ar-w1920dp-h720dp-land-mdpi")
    public void rtlLandscapeLargeFont() throws Exception { capture("rtl-font150", 160, true); }
    private void capture(String name, int density, boolean rtl) throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.fontScale = 1.5f;
        if (rtl) config.setLocale(new Locale("ar"));
        app.getResources().updateConfiguration(config, app.getResources().getDisplayMetrics());
        AuthorizationConsentTest.fake = new AuthorizationConsentTest.FakeBackend();
        AuthorizationConsentTest.caller = "com.shihab.diplay.hudtest";
        new AdditionalAuthorizationTest().reset();
        for (String page : new String[]{"vpn", "write", "overlay"}) {
            boolean vpn = page.equals("vpn");
            Intent request = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:com.shihab.diplay.hudtest"));
            if (page.equals("overlay")) request.setAction(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            ActivityController<? extends AuthorizationActivity> controller = vpn
                ? Robolectric.buildActivity(AuthorizationConsentTest.TestConfirm.class).setup()
                : page.equals("write") ? Robolectric.buildActivity(AuthorizationConsentTest.TestWrite.class, request).setup()
                : Robolectric.buildActivity(AdditionalAuthorizationTest.TestActivity.class, request).setup();
            try {
                View decor = controller.get().getWindow().getDecorView();
                decor.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
                decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, 1920, 720);
                Button allow = AuthorizationConsentTest.button(decor, "允许");
                Button cancel = AuthorizationConsentTest.button(decor, "取消");
                assertNotNull(page + " allow", allow); assertNotNull(page + " cancel", cancel);
                assertEquals(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR, allow.getLayoutDirection());
                assertTrue(allow.getWidth() > 0); assertTrue(allow.getHeight() >= Math.round(64 * density / 160f));
                assertEquals(allow.getHeight(), cancel.getHeight());
                assertTrue(allow.getMeasuredHeight() >= allow.getLayout().getHeight() + allow.getCompoundPaddingTop() + allow.getCompoundPaddingBottom());
                File directory = new File("../agent_docs/screenshots/authorization-components-test-renders");
                directory.mkdirs();
                Bitmap bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888);
                decor.draw(new Canvas(bitmap));
                try (FileOutputStream output = new FileOutputStream(new File(directory, page + "-" + name + ".png"))) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                }
                bitmap.recycle();
                if (density > 160) {
                    ScrollView scroll = scrollView(decor);
                    assertNotNull(scroll);
                    scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                    int[] position = new int[2];
                    allow.getLocationOnScreen(position);
                    assertTrue("Allow must be reachable by scrolling", position[1] >= 0 && position[1] + allow.getHeight() <= 720);
                    cancel.getLocationOnScreen(position);
                    assertTrue("Cancel must be reachable by scrolling", position[1] >= 0 && position[1] + cancel.getHeight() <= 720);
                    bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888);
                    decor.draw(new Canvas(bitmap));
                    try (FileOutputStream output = new FileOutputStream(new File(directory, page + "-" + name + "-actions.png"))) {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                    }
                    bitmap.recycle();
                }
            } finally { controller.pause().stop().destroy(); }
        }
    }
    private ScrollView scrollView(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            ScrollView found = scrollView(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
