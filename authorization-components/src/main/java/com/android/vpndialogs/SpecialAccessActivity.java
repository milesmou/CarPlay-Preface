package com.android.vpndialogs;

import android.net.Uri;
import android.os.Bundle;
import java.util.List;

/** Lists eligible apps/services, then asks for an explicit grant or revocation. No install is started. */
public class SpecialAccessActivity extends AuthorizationActivity {
    private int requestGeneration;
    @Override protected SpecialAccessBackend createBackend() { return new SpecialAccessBackend(this); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            SpecialAccessBackend.Kind kind = SpecialAccessBackend.Kind.fromAction(getIntent().getAction());
            Uri data = getIntent().getData();
            if (data != null && (!"package".equals(data.getScheme()) || data.getSchemeSpecificPart().isEmpty() ||
                    data.getQuery() != null || data.getFragment() != null)) throw new SecurityException("invalid target URI");
            load(kind, data == null ? null : data.getSchemeSpecificPart());
        } catch (Exception failure) { unavailable(failure); }
    }

    private void load(SpecialAccessBackend.Kind kind, String selectedPackage) {
        screen(kind.title, getString(R.string.special_loading));
        action(R.string.cancel, this::finish);
        int generation = ++requestGeneration;
        new Thread(() -> {
            try {
                List<SpecialAccessBackend.Entry> entries = ((SpecialAccessBackend) backend).entries(kind, selectedPackage);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != requestGeneration) return;
                    if (selectedPackage != null && entries.size() == 1) { confirm(kind, entries.get(0)); return; }
                    screen(kind.title, getString(entries.isEmpty() ? R.string.special_empty : R.string.special_choose));
                    for (SpecialAccessBackend.Entry entry : entries)
                        action(entry.label + "\n" + entry.packageName, () -> confirm(kind, entry));
                    action(R.string.close, this::finish);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed() && generation == requestGeneration) unavailable(failure); });
            }
        }, "preface-special-access-list").start();
    }

    private void confirm(SpecialAccessBackend.Kind kind, SpecialAccessBackend.Entry entry) {
        try {
            SpecialAccessBackend service = (SpecialAccessBackend) backend;
            service.checkSpecialEnvironment(kind);
            boolean allowed = service.allowed(kind, entry);
            boolean blocked = service.blocked(kind, entry);
            String identity = entry.label + "\n" + entry.packageName +
                (entry.service == null ? "" : "\n" + entry.service.flattenToShortString());
            screen(kind.title, getString(kind.body, identity, getString(allowed ? R.string.allowed : R.string.denied)));
            if (blocked) action(R.string.special_policy_blocked, this::finish);
            else action(allowed ? R.string.revoke : R.string.allow, () -> {
                try {
                    if (!service.setAllowed(kind, entry, !allowed)) throw new IllegalStateException("authorization not confirmed");
                    setResult(RESULT_OK); finish();
                } catch (Exception failure) { unavailable(failure); }
            });
            action(R.string.cancel, this::finish);
        } catch (Exception failure) { unavailable(failure); }
    }
}
