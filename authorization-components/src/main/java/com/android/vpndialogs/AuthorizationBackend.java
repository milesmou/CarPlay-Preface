package com.android.vpndialogs;

interface AuthorizationBackend {
    final class Target {
        final String packageName;
        final int uid;
        final CharSequence label;
        final long version;
        final String certificate;
        Target(String packageName, int uid, CharSequence label) {
            this(packageName, uid, label, 0, "");
        }
        Target(String packageName, int uid, CharSequence label, long version, String certificate) {
            this.packageName = packageName; this.uid = uid; this.label = label;
            this.version = version; this.certificate = certificate;
        }
        boolean sameIdentity(Target other) {
            return packageName.equals(other.packageName) && uid == other.uid && version == other.version &&
                certificate.equals(other.certificate);
        }
    }
    void checkEnvironment(boolean vpn) throws Exception;
    Target target(String packageName, boolean vpn) throws Exception;
    boolean vpnBlocked(Target target) throws Exception;
    boolean vpnAuthorized(Target target) throws Exception;
    boolean allowVpn(Target target) throws Exception;
    boolean writeAllowed(Target target) throws Exception;
    boolean setWriteAllowed(Target target, boolean allowed) throws Exception;
}
