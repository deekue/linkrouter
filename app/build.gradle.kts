plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing is driven entirely by external secrets (never hardcoded):
// RELEASE_KEYSTORE, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD,
// RELEASE_STORE_PASSWORD — each read from a `gradle.properties` key or an
// environment variable of the same name (see gradle.properties.example).
// If any is missing the release build type is simply left unsigned so
// local/CI builds keep working.
private fun releaseSecret(name: String): String? =
    project.findProperty(name)?.toString()?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "net.chaosengine.linkrouter"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "net.chaosengine.linkrouter"
        minSdk = 26
        targetSdk = 36
        versionCode = (project.findProperty("VERSION_CODE") as? String)?.toIntOrNull() ?: 201
        versionName = (project.findProperty("VERSION_NAME") as? String) ?: "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Applied only when ALL RELEASE_* secrets are present (helper above);
        // otherwise this config is never used and the build stays unsigned.
        create("release") {
            val keystore = releaseSecret("RELEASE_KEYSTORE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = releaseSecret("RELEASE_STORE_PASSWORD")
                keyAlias = releaseSecret("RELEASE_KEY_ALIAS")
                keyPassword = releaseSecret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8/minify on for release only; debug stays unminified.
            isMinifyEnabled = true
            // Keep-rules for Moshi/Room can be added here if R8 needs them.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val hasSigning = listOf("RELEASE_KEYSTORE", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD", "RELEASE_STORE_PASSWORD")
                .all { releaseSecret(it) != null }
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
        // Modern AGP (>= 8.0) does not generate BuildConfig by default; enable it
        // so DispatcherActivity can reference BuildConfig.DEBUG (DESIGN.md §11).
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Persistence (D3: Room)
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Browser icons
    implementation("io.coil-kt:coil-compose:2.7.0")

    // JSON import/export (local only, no network)
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:2.7.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compose UI tests
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
