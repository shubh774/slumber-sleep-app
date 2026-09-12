plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.slumber.sleepsounds"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.slumber.sleepsounds"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("slumber_release.jks")
            storePassword = "slumberpassword123"
            keyAlias = "slumber"
            keyPassword = "slumberpassword123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Google AdMob & Google Play Billing
    implementation("com.google.android.gms:play-services-ads:23.0.0")
    implementation("com.android.billingclient:billing-ktx:6.2.1")

    // Needed for MediaSessionCompat / MediaButtonReceiver (lock-screen controls,
    // notification media style) used by SleepSoundService.
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
}
