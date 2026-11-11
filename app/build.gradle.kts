import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Credentials live in the git-ignored local.properties, never in the repo.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// Release signing key, also git-ignored (release-signing/); without it the release APK stays unsigned.
val signingProps = Properties().apply {
    rootProject.file("release-signing/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.mytravel"
    // Current AndroidX/Compose require compiling against 37.2; runtime target stays 36.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.mytravel"
        // 31: RenderEffect blur for the glass UI; main target is Android 16 (API 36).
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // AMap SDK 10+ ships only ARM native libs; the Xiaomi 14 is arm64.
        ndk { abiFilters += listOf("arm64-v8a") }
        manifestPlaceholders["AMAP_KEY"] = localProps.getProperty("AMAP_KEY", "")
        // Screen-recording builds only: a fixed "lat,lng" (GCJ-02) replaces the real position, e.g.
        // ./gradlew assembleRelease -PdemoLocation=39.9150,116.4110. Empty (the default) = real location.
        buildConfigField("String", "DEMO_LOCATION", "\"${(project.findProperty("demoLocation") as String?).orEmpty()}\"")
        // No upstream credential (Bailian, DeepSeek, QWeather) is built into any APK: the app's own
        // server holds them. The phone gets only the server's address.
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Debug talks to the server on this host (USB `adb reverse`, see server/README.md) and is
        // named apart, so a private debug APK isn't mistaken for a release one. The package name
        // stays com.mytravel because the AMap key is bound to it.
        debug {
            buildConfigField("String", "SERVER_URL", "\"${localProps.getProperty("MYTRAVEL_SERVER_URL", "https://localhost:18443")}\"")
        }
        // No fallback: without a configured server the release app only reports that it can't connect.
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // -PreleaseServerUrl=... overrides it for a one-off build (e.g. an emulator recording through adb reverse).
            buildConfigField("String", "SERVER_URL", "\"${(project.findProperty("releaseServerUrl") as String?) ?: localProps.getProperty("MYTRAVEL_RELEASE_SERVER_URL", "")}\"")
        }
    }
    // Host-specific resources (the server's LAN / Tailscale addresses in network_security_config.xml) live in
    // the git-ignored app/local-res/; when present they override src/main for every build type.
    sourceSets {
        getByName("debug").res.srcDir("local-res")
        getByName("release").res.srcDir("local-res")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.amap.map)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Backdrop blur for glass panels; captures TextureMapView (SurfaceView cannot be captured).
    implementation(libs.haze)
    implementation(libs.haze.blur)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

ksp {
    // Exported schemas are committed so future migrations can be verified.
    arg("room.schemaLocation", "$projectDir/schemas")
}
