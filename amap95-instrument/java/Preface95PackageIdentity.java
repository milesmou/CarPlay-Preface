import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;

/** Local test-build identity check. No server identity/token/license changes. */
public final class Preface95PackageIdentity {
    @SuppressWarnings("deprecation")
    public static boolean check(Context context) {
        try {
            if (context == null || !"com.autonavi.amapauto".equals(context.getPackageName())) return false;
            PackageInfo info = context.getPackageManager().getPackageInfo("com.autonavi.amapauto", PackageManager.GET_SIGNATURES);
            boolean verified = info.applicationInfo != null && info.applicationInfo.uid == Process.myUid() &&
                    info.signatures != null && info.signatures.length == 1 &&
                    Preface95CertificatePin.matches(info.signatures[0].toByteArray());
            Log.i("Preface95Hints", verified ? "TEST_BUILD_PACKAGE_CERTIFICATE_VERIFIED" : "TEST_BUILD_PACKAGE_CERTIFICATE_REJECTED");
            return verified;
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            Log.e("Preface95Hints", "TEST_BUILD_PACKAGE_IDENTITY_FAILED: " + error.getClass().getSimpleName());
            return false;
        }
    }
}
