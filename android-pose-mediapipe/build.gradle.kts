plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "ai.senp.pose.mediapipe"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        noCompress += "task"
    }

    sourceSets["androidTest"].assets.srcDir(rootProject.layout.projectDirectory.dir("local-models"))

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":pose-contracts"))
    implementation("com.google.mediapipe:tasks-vision:0.10.32")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
