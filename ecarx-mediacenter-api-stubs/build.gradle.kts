plugins { id("com.android.library") }

android {
    namespace = "com.shilapi.xcertplay.ecarxstubs"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 25 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
