import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.senp.pose.mediapipe"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    androidResources { noCompress += "task" }
    sourceSets["androidTest"].assets.srcDir(rootProject.layout.buildDirectory.dir("generated/pose-model-assets"))
    testOptions { unitTests.isReturnDefaultValues = true }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }.configureEach {
    dependsOn(rootProject.tasks.named("preparePoseModelAsset"))
}

// Lint inspects the generated androidTest model asset outside mergeAndroidTestAssets.
tasks.matching { it.name.contains("Lint") || it.name.startsWith("lintAnalyze") }.configureEach {
    dependsOn(rootProject.tasks.named("preparePoseModelAsset"))
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":core-pipeline"))
    implementation(project(":android-video"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
