plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val repositorySigning = groovy.json.JsonSlurper().parseText(
    providers.fileContents(rootProject.layout.projectDirectory.file("signing/signing.json")).asText.get(),
) as Map<*, *>
val repositoryKeystore = rootProject.file("signing/${repositorySigning["keystoreFile"]}").canonicalFile

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shilapi.xcertplay"
        minSdk = 28
        targetSdk = 37
        versionCode = 1201
        versionName = "1.2.1"

    }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .filter { it.isNotBlank() }.getOrElse(repositoryKeystore.absolutePath),
            )
            storeType = providers.environmentVariable("ANDROID_KEYSTORE_TYPE")
                .filter { it.isNotBlank() }.getOrElse(repositorySigning["keystoreType"] as String)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
                .filter { it.isNotBlank() }.getOrElse(repositorySigning["storePassword"] as String)
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
                .filter { it.isNotBlank() }.getOrElse(repositorySigning["keyAlias"] as String)
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
                .filter { it.isNotBlank() }.getOrElse(repositorySigning["keyPassword"] as String)
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.automotive)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
