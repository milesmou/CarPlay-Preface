# ECARX media center compile-only declarations

These handwritten signatures let the application's own classes extend the installed
E02 `MusicClient` and `MusicPlaybackInfo`, using the same package-name registration
overload as the user's Linkco reference APK. They contain no vendor implementation.

`common` depends on this module with `compileOnly`; it must never be a runtime APK
dependency. Unit tests add it explicitly as `testImplementation` to exercise the
application bridge without the head-unit boot class path. Runtime availability and
signature checks precede bridge loading. Missing APIs retain the interface path;
permission/readiness/registration failures do not trigger an alternate registration.

Signatures were checked against the local `media-center-classes.json` index for the
IHU519G/FS11A1 build 327 reference, documented in
`docs/LINKCO_APK_MEDIA_KEYS_REFERENCE.md`. This is not verification of IHU353G access.
