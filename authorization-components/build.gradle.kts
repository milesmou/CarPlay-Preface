plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.android.vpndialogs"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.android.vpndialogs"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "preface-android9-1"
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
