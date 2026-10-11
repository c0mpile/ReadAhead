plugins {
    alias(libs.plugins.readahead.android.library)
}

android {
    namespace = "com.c0mpile.readahead.narration"
}

dependencies {
    // BookHandle.zip() hands out commons-compress's ZipFile.
    api(libs.commons.compress)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
