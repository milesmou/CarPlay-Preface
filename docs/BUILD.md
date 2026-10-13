# Build DiPlay

Use this procedure to build the DiPlay app for an Android head unit.
Run the commands from the repository root, where `gradlew` and `settings.gradle.kts` are located.

## Select the application

This repository contains several Android applications.
Select `mobile` to build the main DiPlay app.

| Gradle module | Purpose | Debug APK |
| --- | --- | --- |
| `:mobile` | Main DiPlay app for Android head units | `mobile/build/outputs/apk/debug/mobile-debug.apk` |
| `:maphost` | Sample application that displays a map from DiPlay | `samples/maphost/build/outputs/apk/debug/maphost-debug.apk` |
| `:home` | Optional DiPlay Home launcher | `samples/home/build/outputs/apk/debug/home-debug.apk` |
| `:automotive` | Separate Android Automotive application inherited from xcertplay | `automotive/build/outputs/apk/debug/automotive-debug.apk` |

The `:common` and `:shared` modules are libraries.
The applications use these libraries.

If the installed app is **DiPlay map host**, you selected `:maphost` or its APK.
Build `:mobile` and select `mobile-debug.apk` to install the main app.
A command such as `./gradlew assembleDebug` can build more than one application.
Use the module name in each build command to select the required application.

## Prepare the build environment

Install these tools:

- JDK 25.
- Android SDK Platform 37 (`platforms;android-37.0`).
- Android SDK Build-Tools 36.0.0.
- Android SDK Platform-Tools.
- Android NDK 28.2.13676358.

Use the Gradle wrapper included in this repository.
The first build needs an internet connection to download dependencies.

1. Open the repository root in Android Studio.
2. Install the Android SDK packages with **Tools → SDK Manager**.
3. Set **Gradle JDK** to JDK 25 in the Android Studio Gradle settings.
4. Wait for Gradle sync to finish.

For a terminal build, set `JAVA_HOME` to your JDK 25 directory.
Set `ANDROID_HOME` to your Android SDK directory.
You can also specify the SDK directory in the root `local.properties` file:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Keep `local.properties` outside Git.
Check the Java version before the build:

```sh
java -version
```

The output must identify Java 25.
On Windows, use `gradlew.bat` in place of `./gradlew` in the commands below.

## Build the main app

For a main-app build with the repository's bundled experimental runtime assets:

```sh
./gradlew :mobile:assembleDebug
```

Check that this file exists:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
```

The debug application ID is `com.shihab.diplay.hudtest`.
The release application ID is `com.shihab.diplay`.

The main APK includes the two reviewed files from `runtime-assets/offline-mfi/` by default.
They were imported from the official DiPlay v0.2.18 APK; see [resource provenance](CARPLAY_AUTH_RESOURCES.md).
They enable local authentication testing, but do not establish iPhone or vehicle acceptance.
Tests generate synthetic identities at runtime.
Keep test private keys outside Git.

### Use Android Studio

1. Open **Run → Edit Configurations**.
2. Select an **Android App** configuration, or create one.
3. Set **Module** to `mobile`.
4. Select this configuration in the toolbar.
5. Select the Android device.
6. Click **Run** to install the main debug app.

To build an APK without installation, run `:mobile:assembleDebug` in the Android Studio terminal.
Select the APK from the `mobile` output directory.

## Check the source build

Run the unit tests, lint checks, and main debug build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

Check that Gradle reports `BUILD SUCCESSFUL`.
For the complete CI procedure, see [the Android workflow](../.github/workflows/android.yml).
That workflow also checks and builds the `home` and `maphost` applications.

## Build a standalone car-test APK

The repository already contains the runtime authentication files:

```text
runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

The public-tree check permits only these two fixed paths and their reviewed SHA-256 values.
APK signing keys and other credentials remain excluded. The build rejects unexpected credential containers in APK assets.

Run the standalone debug task to verify the selected pair and build the APK:

```sh
./gradlew :mobile:assembleStandaloneDebug
```

The task stops if either required file is absent or empty.
Its APK output is `mobile/build/outputs/apk/debug/mobile-debug.apk`.
Check both files in the APK against the selected input directory:

- `assets/offline-mfi/identity.pk8`.
- `assets/offline-mfi/certificate.p7b`.

Use this APK for a standalone connection test.
Update the existing test app with the same signing key to keep its settings.
Different signing keys cannot update the same installed application.

To use a different locally provisioned pair, set `DIPLAY_AUTH_ASSETS_DIR` to an external assets directory with the same structure. It replaces the default runtime directory. Keep that replacement outside Git; the repository exception does not apply to it.

## Build a release APK

The reviewed runtime assets are selected by default. Set the signing environment variables locally:

| Variable | Value |
| --- | --- |
| `DIPLAY_AUTH_ASSETS_DIR` | Optional external runtime assets directory; defaults to repository `runtime-assets/` |
| `ANDROID_KEYSTORE_PATH` | Path to your Android signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Keep the keystore and passwords outside Git.
Run the release checks and build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

The output is `mobile/build/outputs/apk/release/mobile-release.apk`.
The APK contains the selected runtime identity.
Recipients can extract that identity from the APK.
The APK excludes the Android signing key.
See [the project notices](THIRD_PARTY_NOTICES.md) for the experimental identity used in public releases.

The public source archive matches the release tag.
For this branch it includes the two reviewed experimental runtime assets and their provenance. It excludes APK signing keys, other credentials, local configuration, and build output. The already-published v0.1.0 source-preview tag predates this import.
The retired `build-beta.py` helper is no longer part of the build procedure.

## Correct build problems

| Symptom | Action |
| --- | --- |
| Installed app is DiPlay map host | Select `mobile` in Android Studio. Build and install the APK from the `mobile` output directory. |
| Gradle cannot find the Android SDK | Set `ANDROID_HOME` or the `sdk.dir` value in `local.properties`. |
| A required SDK or NDK package is absent | Install the listed package with SDK Manager. |
| Java or Gradle JDK version is incorrect | Set the terminal JDK and Android Studio Gradle JDK to Java 25. |
| Main APK cannot load local authentication | Check the selected pair and its hashes; use `:mobile:assembleStandaloneDebug`. An external directory must contain both files. |
| Standalone task reports missing authentication files | Check the external directory path and both required files. |
| Android rejects an app update | Use the same application ID and signing key as the installed app. |
