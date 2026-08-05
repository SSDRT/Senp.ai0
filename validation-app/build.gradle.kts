plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "ai.senp.validation"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "ai.senp.validation"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    androidResources {
        noCompress += "task"
    }

    sourceSets["main"].assets.srcDir(rootProject.layout.projectDirectory.dir("local-models"))
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":android-video"))
    implementation(project(":android-pose-mediapipe"))
    implementation(project(":pose-contracts"))
}
