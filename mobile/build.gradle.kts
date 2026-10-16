plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The reviewed upstream experimental pair is bundled by default. An explicit local input can replace it.
val authenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile } ?: rootProject.file("runtime-assets").canonicalFile

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
        applicationId = "com.shihab.diplay"
        minSdk = 25
        targetSdk = 37
        versionCode = 68
        versionName = "0.1.1"

    }


    sourceSets.getByName("main").assets.srcDir(authenticationAssets)

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .filter { it.isNotBlank() }
                    .getOrElse(repositoryKeystore.absolutePath),
            )
            storeType = providers.environmentVariable("ANDROID_KEYSTORE_TYPE")
                .filter { it.isNotBlank() }
                .getOrElse(repositorySigning["keystoreType"] as String)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
                .filter { it.isNotBlank() }
                .getOrElse(repositorySigning["storePassword"] as String)
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
                .filter { it.isNotBlank() }
                .getOrElse(repositorySigning["keyAlias"] as String)
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
                .filter { it.isNotBlank() }
                .getOrElse(repositorySigning["keyPassword"] as String)
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".hudtest"
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Only the selected accessory pair is allowed; APK signing keys and other credentials remain excluded.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = listOf("identity.pk8", "certificate.p7b")
        .map { authenticationAssets.resolve("offline-mfi/$it").canonicalFile }.toSet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile && it.length() > 0 }) { "Runtime authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Both ordinary and standalone main-app builds include the selected pair.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Verify runtime authentication input for a standalone car-test APK."
    val directory = authenticationAssets
    doLast {
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
