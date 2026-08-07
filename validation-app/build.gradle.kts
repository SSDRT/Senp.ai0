import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    androidResources { noCompress += "task" }
    sourceSets["main"].assets.srcDir(rootProject.layout.projectDirectory.dir("local-models"))
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":core-pipeline"))
    implementation(project(":core-cache"))
    implementation(project(":core-motion"))
    implementation(project(":core-alignment"))
    implementation(project(":android-video"))
    implementation(project(":android-pose-mediapipe"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
