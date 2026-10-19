package com.android.vpndialogs;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.pm.PackageInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ComponentIdentityTest {
    @Test public void bothSharedUserDeclarationAndSystemAppIdAreRequired() throws Exception {
        assertTrue(ComponentIdentity.isSystemIdentity("android.uid.system", 1000));
        assertTrue(ComponentIdentity.isSystemIdentity("android.uid.system", 101000));
        assertFalse(ComponentIdentity.isSystemIdentity(null, 1000));
        assertFalse(ComponentIdentity.isSystemIdentity("android.uid.other", 1000));
        assertFalse(ComponentIdentity.isSystemIdentity("android.uid.system", 10123));
        assertFalse(ComponentIdentity.isSystemIdentity("android.uid.system", -1));
    }

    @Test public void installedManifestDeclaresSystemSharedUser() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo own = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        assertEquals(ComponentIdentity.SHARED_USER, own.sharedUserId);
    }
}
