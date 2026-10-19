package com.android.vpndialogs;

import android.net.Uri;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

public class AdditionalAuthorizationActivity extends AuthorizationActivity {
    @Override protected AdditionalAuthorizationBackend createBackend() { return new AdditionalAuthorizationBackend(this); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            AdditionalAuthorizationBackend service = (AdditionalAuthorizationBackend) backend;
            if (!Settings.ACTION_MANAGE_OVERLAY_PERMISSION.equals(getIntent().getAction()))
                throw new SecurityException("unsupported request");
            service.checkOverlayEnvironment();
            Uri data = getIntent().getData();
            if (data == null) {
                startActivity(new Intent(this, SpecialAccessActivity.class).setAction(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                finish(); return;
            }
            if (!"package".equals(data.getScheme()) || data.getSchemeSpecificPart().isEmpty() ||
                    data.getQuery() != null || data.getFragment() != null) throw new SecurityException("invalid target URI");
            AuthorizationBackend.Target target = service.overlayTarget(data.getSchemeSpecificPart());
            boolean allowed = service.overlayAllowed(target);
            screen(R.string.overlay_title, getString(R.string.overlay_body, target.label + "\n" + target.packageName, getString(allowed ? R.string.allowed : R.string.denied)));
            action(allowed ? R.string.revoke : R.string.allow, () -> {
                try {
                    if (!service.setOverlayAllowed(target, !allowed)) throw new IllegalStateException("authorization not confirmed");
                    setResult(RESULT_OK); finish();
                } catch (Exception failure) { unavailable(failure); }
            });
            action(R.string.cancel, this::finish);
        } catch (Exception failure) { unavailable(failure); }
    }
}
