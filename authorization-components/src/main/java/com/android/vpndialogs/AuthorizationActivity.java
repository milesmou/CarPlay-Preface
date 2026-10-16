package com.android.vpndialogs;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

abstract class AuthorizationActivity extends Activity {
    private static final int PAGE_PADDING_DP = 24;
    private static final int BLOCK_GAP_DP = 20;
    private static final int CONTENT_MAX_WIDTH_DP = 960;
    private static final int CONTROL_HEIGHT_DP = 64;
    private LinearLayout content;
    protected AuthorizationBackend backend;
    protected AuthorizationBackend createBackend() { return new Android9Backend(this); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        backend = createBackend();
        setFinishOnTouchOutside(false);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    protected void screen(int title, CharSequence message) {
        FrameLayout frame = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(PAGE_PADDING_DP), dp(PAGE_PADDING_DP), dp(PAGE_PADDING_DP), dp(PAGE_PADDING_DP));
        scroll.addView(content);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
            Math.min(getResources().getDisplayMetrics().widthPixels, dp(CONTENT_MAX_WIDTH_DP)), -1, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        frame.addView(scroll, layout);
        setContentView(frame);
        TextView heading = new TextView(this);
        heading.setText(title); heading.setTextSize(26); heading.setGravity(Gravity.START);
        content.addView(heading);
        TextView body = new TextView(this);
        body.setText(message); body.setTextSize(20); body.setGravity(Gravity.START);
        LinearLayout.LayoutParams bodyLayout = new LinearLayout.LayoutParams(-1, -2);
        bodyLayout.topMargin = dp(BLOCK_GAP_DP);
        content.addView(body, bodyLayout);
    }

    protected Button action(int text, Runnable clicked) { return action(getString(text), clicked); }
    protected Button action(CharSequence text, Runnable clicked) {
        Button button = new Button(this);
        button.setText(text); button.setTextSize(20); button.setAllCaps(false);
        button.setMinHeight(dp(CONTROL_HEIGHT_DP));
        button.setFilterTouchesWhenObscured(true);
        button.setOnClickListener(view -> { button.setEnabled(false); clicked.run(); });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(BLOCK_GAP_DP);
        content.addView(button, layout);
        return button;
    }

    protected void unavailable(Exception failure) {
        Log.w("PrefaceAuthorization", "Authorization unavailable: " + failure.getClass().getSimpleName());
        screen(R.string.unavailable_title, getString(R.string.unavailable_body));
        action(R.string.close, this::finish);
    }
}
