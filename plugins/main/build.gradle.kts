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
    implementation(files("libs/cc.buessow.glumagic.input-1.0.jar"))
    implementation(project(":core:data"))
    implementation(project(":core:graph"))
    implementation(project(":core:graphview"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    implementation(project(":core:validators"))
    implementation(project(":shared:impl"))
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-api:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    testImplementation(project(":implementation"))
    testImplementation(project(":plugins:aps"))
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