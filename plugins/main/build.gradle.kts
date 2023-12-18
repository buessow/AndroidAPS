plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-kapt")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.plugins.main"
}

var okhttp3_version = "4.11.0"

dependencies {
    implementation(project(":shared:impl"))
    implementation(project(":database:entities"))
    implementation(project(":database:impl"))
    implementation(project(":core:graphview"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:main"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    implementation(project(":core:validators"))
    implementation("org.tensorflow:tensorflow-core-platform:0.3.3")
    implementation("org.tensorflow:tensorflow-lite:2.4.0")
    // implementation("org.tensorflow:tensorflow-lite-gpu:2.3.0")

    testImplementation(project(":implementation"))
    testImplementation(project(":plugins:insulin"))
    testImplementation(project(":shared:tests"))
    testImplementation(Libs.Squareup.Okhttp3.mockWebServer)
    androidTestImplementation(project(":shared:tests"))

    api(Libs.AndroidX.appCompat)
    api(Libs.Google.Android.material)

    // Actions
    api(Libs.AndroidX.gridLayout)

    //SmsCommunicator
    api(Libs.javaOtp)
    api(Libs.qrGen)

    // Overview
    api(Libs.Google.Android.flexbox)

    // Food
    api(Libs.AndroidX.Work.runtimeKtx)
    androidTestImplementation(project(":shared:tests"))

    kapt(Libs.Dagger.compiler)
    kapt(Libs.Dagger.androidProcessor)
}