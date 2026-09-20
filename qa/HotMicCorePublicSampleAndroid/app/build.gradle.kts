plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.hotmic.core.publicsample"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.hotmic.core.publicsample"
        minSdk = 23 // README: "Android minSdk 23 (Android 6.0) or newer"
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { viewBinding = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true // N3: the sample must build with 0 warnings
    }
}

dependencies {
    // README "Installation" block, verbatim coordinates.
    implementation("io.hotmic.core:hotmic-core-android:0.1.0-SNAPSHOT")

    // Your player — Core does not bundle one.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // README "Requirements": Kotlin 2.1+ with kotlinx.coroutines. Not listed by the README
    // (README-GAPS #2): Core's own coroutines dependency is `implementation`, so a host that
    // calls the suspend API needs to declare coroutines itself to get lifecycleScope etc.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}
