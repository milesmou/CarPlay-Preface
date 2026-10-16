package com.android.vpndialogs;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

public class AuthorizationHomeActivity extends AuthorizationActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        screen(R.string.app_name, getString(R.string.home_body));
        try {
            backend.checkEnvironment(false);
            for (String name : Android9Backend.PACKAGES) {
                try {
                    AuthorizationBackend.Target target = backend.target(name, false);
                    action(getString(R.string.open_write, target.label), () -> {
                        startActivity(new Intent(this, WriteSettingsActivity.class)
                            .setAction(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(Uri.parse("package:" + name)));
                        finish();
                    });
                } catch (Exception ignored) { /* Only show installed, matching CarPlay targets. */ }
            }
            action(R.string.close, this::finish);
        } catch (Exception failure) { unavailable(failure); }
    }
}
