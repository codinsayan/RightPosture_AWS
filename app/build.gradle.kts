import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Supabase's URL and anon key live in local.properties, which is gitignored. The anon key is
// designed to be public - it ships inside every APK and is constrained by row-level security, not
// by secrecy - but keeping it out of source control means a leaked repo does not also name the
// project it points at. Absent values leave the app fully functional and entirely local.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.example.rightposture"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        // LiteRT-LM ships a 21 MB arm64 JNI library and a 25 MB x86_64 one, and MediaPipe ships
        // four more. Without this the debug APK is 125 MB, over half of it native code for
        // architectures no phone runs - the target device is arm64, as is every Android handset
        // this decade.
        //
        // The cost is that an x86 emulator can no longer install the app. That is an acceptable
        // trade here: the test suite is JVM and Robolectric, so nothing automated depends on an
        // emulator, and the device gates all need real hardware anyway.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        applicationId = "com.example.rightposture"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${localProperties.getProperty("supabase.url", "")}\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${localProperties.getProperty("supabase.anonKey", "")}\"",
        )
    }

    buildTypes {
        // A debug build that installs beside the regular one instead of over it, as "RytPosture".
        // The test phone also carries a build signed on another machine, and Android will not
        // replace an app with one signed by a different key without uninstalling it - which wipes
        // its exercises and sets. A second application id sidesteps that. Its launcher name comes
        // from src/ryt/res. Install with `gradlew installRyt`.
        create("ryt") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".ryt"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Robolectric needs the merged manifest and resources to stand up an Application for the
    // in-memory Room database. Without this the DAO tests fail on a missing manifest, not on
    // anything to do with the schema.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    // 8.3/8.4's on-device model runtime. Pinned in the version catalog for a toolchain reason
    // recorded there - the newest release cannot be read by any KSP that exists.
    //
    // `kotlin-reflect` is excluded because LiteRT-LM only needs it for annotation-driven tool
    // calling, which this app does not use, and because it is the one dependency that can drag the
    // Kotlin version forward. That is not hypothetical: it is exactly how supabase-kt broke this
    // build, with a `[MissingType]` on `RightPostureDatabase` that named our code rather than the
    // conflict that caused it.
    implementation(libs.litertlm.android) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
