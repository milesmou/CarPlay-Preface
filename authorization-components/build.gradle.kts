plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.android.vpndialogs"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.android.vpndialogs"
        minSdk = 28
        targetSdk = 28
        versionCode = 8
        versionName = "preface-android9-8"
    }
    // Firmware repair for API 28, distributed locally; this is not a Google Play application.
    lint { disable += "ExpiredTargetSdkVersion" }
    // A separate firmware component, signed after building with the verified matching platform
    // test key. Never reuse the CarPlay signing key or silently create a debug identity.
    buildTypes {
        getByName("debug") { signingConfig = null }
        getByName("release") { signingConfig = null; isDebuggable = false; isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "1g" }
    }
}
dependencies {
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}

// Sign/verify once even when multiple CarPlay modules are built in parallel.
abstract class PrepareDistributionAssets : Exec() {
    @get:OutputDirectory abstract val assetOutput: DirectoryProperty
}
val prepareDistributionAssets by tasks.registering(PrepareDistributionAssets::class) {
    group = "build"
    dependsOn("assembleRelease")
    val unsigned = layout.buildDirectory.file("outputs/apk/release/authorization-components-release-unsigned.apk")
    val metadata = layout.buildDirectory.file("outputs/apk/release/output-metadata.json")
    val script = rootProject.layout.projectDirectory.file("scripts/android9_authorization.py")
    inputs.files(unsigned, metadata, script,
        rootProject.file("agent_docs/android9-authorization/platform-test-key/platform.pk8"),
        rootProject.file("agent_docs/android9-authorization/platform-test-key/platform.x509.pem"))
    assetOutput.set(layout.buildDirectory.dir("generated/distributionAssets"))
    workingDir(rootProject.projectDir)
    commandLine("python", script.asFile.absolutePath, "--sign", "--asset-output", assetOutput.get().asFile.absolutePath)
}
