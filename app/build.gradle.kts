plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.orangetiger.otstudio"
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.orangetiger.otstudio"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildTypes {
        release {
            // Phones run arm64. The x86_64 engine stays in debug builds for the emulator.
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17"; allWarningsAsErrors = true }
    lint { abortOnError = true; checkReleaseBuilds = true }
    // Native libraries are extracted at install, so NativeEngine can point the loader at the fast engine build.
    packaging { jniLibs { useLegacyPackaging = true } }
    testOptions { unitTests.isReturnDefaultValues = false; unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    // llama.cpp for on-device chat (MIT). Model weights are never bundled; users download them at runtime.
    implementation("net.ladenthin:llama-android:5.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
dependencyLocking { lockAllConfigurations() }
