plugins {
    alias(libs.plugins.readahead.android.application)
    alias(libs.plugins.readahead.android.compose)
}

android {
    namespace = "com.c0mpile.readahead"
    defaultConfig {
        applicationId = "com.c0mpile.readahead"
        versionCode = 4
        versionName = "0.2.2"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(projects.narration)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
