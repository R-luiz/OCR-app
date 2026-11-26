import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Optional local defaults for debug builds only. Release credentials are always entered
// at runtime, since any value in BuildConfig can be recovered from the APK.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localStringLiteral(key: String): String = "\"" + localProperties.getProperty(key).orEmpty()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n") + "\""

// A release is signed with the keystore these four environment variables describe, when
// they are set — the release workflow fills them from repository secrets. Without them
// it is signed with the debug key instead, so `assembleRelease` works on any machine;
// but Android only installs an update over a build signed with the same key.
val releaseKeystore: String? = System.getenv("OCR_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

// The phones a release is meant for. The x86 libraries only serve emulators and are a
// third of the native code; `-Pocr.releaseAbis=x86_64` builds a release for one anyway.
val releaseAbis: List<String> =
    (providers.gradleProperty("ocr.releaseAbis").orNull ?: "arm64-v8a,armeabi-v7a").split(",")

android {
    namespace = "com.ocrapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ocrapp"
        // API 28 lets image decoding go through a single ImageDecoder path with
        // correct EXIF handling, instead of a BitmapFactory + ExifInterface fallback.
        minSdk = 28
        targetSdk = 35
        // Kept in step with the release tags cut by .github/workflows/release.yml, so
        // the version the Settings screen reports can be matched to a GitHub release.
        versionCode = 4
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "RUNPOD_ENDPOINT_ID", "\"\"")
        buildConfigField("String", "RUNPOD_API_KEY", "\"\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("OCR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OCR_KEY_ALIAS")
                keyPassword = System.getenv("OCR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "RUNPOD_ENDPOINT_ID", localStringLiteral("runpod.endpointId"))
            buildConfigField("String", "RUNPOD_API_KEY", localStringLiteral("runpod.apiKey"))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            ndk {
                abiFilters += releaseAbis
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    sourceSets {
        // Test doubles that both the JVM and the on-device tests use.
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.barcode.scanning)
    // Pure-Java QR encoder — no Android dependency, so the maker works without
    // any network or Play Services model download.
    implementation(libs.zxing.core)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // Referenced from AppModule in the main source set (guarded by BuildConfig.DEBUG),
    // so it has to be on the release compile classpath too.
    implementation(libs.okhttp.logging)

    implementation(libs.androidx.security.crypto)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
