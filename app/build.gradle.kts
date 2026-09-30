plugins {
    id("com.android.application")
}

android {
    namespace = "com.deepseek.rootagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deepseek.rootagent"
        minSdk = 26
        targetSdk = 35
        versionCode = 81
        versionName = "2.4.5"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/deepseek-agent.jks")
            storePassword = "deepseek2026"
            keyAlias = "deepseek-agent"
            keyPassword = "deepseek2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
}
