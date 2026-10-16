package com.android.vpndialogs;

interface AuthorizationBackend {
    final class Target {
        final String packageName;
        final int uid;
        final CharSequence label;
        Target(String packageName, int uid, CharSequence label) {
            this.packageName = packageName; this.uid = uid; this.label = label;
        }
    }
    void checkEnvironment(boolean vpn) throws Exception;
    Target target(String packageName, boolean vpn) throws Exception;
    boolean vpnBlocked(Target target) throws Exception;
    boolean vpnPrepared(Target target) throws Exception;
    boolean allowVpn(Target target) throws Exception;
    boolean writeAllowed(Target target) throws Exception;
    boolean setWriteAllowed(Target target, boolean allowed) throws Exception;
}
