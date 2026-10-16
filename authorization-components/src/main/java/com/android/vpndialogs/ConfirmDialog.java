package com.android.vpndialogs;

import android.os.Bundle;

/** Restores the framework's exact Android 9 consent component; never starts a VPN or TUN. */
public class ConfirmDialog extends AuthorizationActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            backend.checkEnvironment(true);
            // Android supplies this identity through startActivityForResult, never an Intent extra.
            AuthorizationBackend.Target target = backend.target(getCallingPackage(), true);
            if (backend.vpnBlocked(target)) {
                screen(R.string.unavailable_title, getString(R.string.blocked_body));
                action(R.string.close, this::finish);
                return;
            }
            if (backend.vpnPrepared(target)) { setResult(RESULT_OK); finish(); return; }
            screen(R.string.vpn_title, getString(R.string.vpn_body, target.label));
            action(R.string.allow, () -> {
                try {
                    if (!backend.allowVpn(target)) throw new IllegalStateException("authorization not confirmed");
                    setResult(RESULT_OK); finish();
                } catch (Exception failure) { unavailable(failure); }
            });
            action(R.string.cancel, this::finish);
        } catch (Exception failure) { unavailable(failure); }
    }
}
