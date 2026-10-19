package com.android.vpndialogs;

import android.net.Uri;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

public class WriteSettingsActivity extends AuthorizationActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            backend.checkEnvironment(false);
            Uri data = getIntent().getData();
            if (!Settings.ACTION_MANAGE_WRITE_SETTINGS.equals(getIntent().getAction())) throw new SecurityException("invalid settings request");
            if (data == null) {
                startActivity(new Intent(this, SpecialAccessActivity.class).setAction(Settings.ACTION_MANAGE_WRITE_SETTINGS));
                finish(); return;
            }
            if (!"package".equals(data.getScheme()) || data.getSchemeSpecificPart().isEmpty() ||
                    data.getQuery() != null || data.getFragment() != null) throw new SecurityException("invalid target URI");
            AuthorizationBackend.Target target = backend.target(data.getSchemeSpecificPart(), false);
            boolean allowed = backend.writeAllowed(target);
            screen(R.string.write_title, getString(R.string.write_body, target.label + "\n" + target.packageName, getString(allowed ? R.string.allowed : R.string.denied)));
            action(allowed ? R.string.revoke : R.string.allow, () -> {
                try {
                    if (!backend.setWriteAllowed(target, !allowed)) throw new IllegalStateException("authorization not confirmed");
                    setResult(RESULT_OK); finish();
                } catch (Exception failure) { unavailable(failure); }
            });
            action(R.string.cancel, this::finish);
        } catch (Exception failure) { unavailable(failure); }
    }
}
