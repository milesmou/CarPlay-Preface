package com.android.vpndialogs;

import android.content.Intent;
import android.os.Bundle;

public class AuthorizationHomeActivity extends AuthorizationActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        screen(R.string.app_name, getString(R.string.home_body));
        try {
            backend.checkEnvironment(false);
            for (SpecialAccessBackend.Kind kind : SpecialAccessBackend.Kind.values()) {
                navigation(kind.title, () -> startActivity(new Intent(this, SpecialAccessActivity.class).setAction(kind.action)));
            }
            action(R.string.close, this::finish);
        } catch (Exception failure) { unavailable(failure); }
    }
}
