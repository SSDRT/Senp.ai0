import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

    buildFeatures {
        compose = true
    }

    androidResources { noCompress += "task" }
    sourceSets["main"].assets.srcDir(rootProject.layout.buildDirectory.dir("generated/pose-model-assets"))
    sourceSets["androidTest"].assets.srcDir(rootProject.layout.buildDirectory.dir("generated/live-pose-fixtures"))
    if (providers.gradleProperty("includeHeavyPoseModel").orNull == "true") {
        sourceSets["main"].assets.srcDir(rootProject.layout.buildDirectory.dir("generated/heavy-pose-model-assets"))
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(rootProject.tasks.named("preparePoseModelAsset"))
    if (name.contains("AndroidTest")) dependsOn(rootProject.tasks.named("prepareLivePoseFixtures"))
    if (providers.gradleProperty("includeHeavyPoseModel").orNull == "true") dependsOn(rootProject.tasks.named("prepareHeavyPoseModelAsset"))
}

// Lint model/report tasks also inspect the generated main model asset directly.
tasks.matching { it.name.contains("Lint") || it.name.startsWith("lintAnalyze") }.configureEach {
    dependsOn(rootProject.tasks.named("preparePoseModelAsset"))
    dependsOn(rootProject.tasks.named("prepareLivePoseFixtures"))
    if (providers.gradleProperty("includeHeavyPoseModel").orNull == "true") dependsOn(rootProject.tasks.named("prepareHeavyPoseModelAsset"))
}

// Instrumentation suites share one emulator; simultaneous runners steal camera/activity focus.
tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
    mustRunAfter(project(":android-pose-mediapipe").tasks.matching {
        it.name.startsWith("connected") && it.name.endsWith("AndroidTest")
    })
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("qualifiedReferenceReplayRoot").orNull?.let {
        systemProperty("qualifiedReferenceReplayRoot",rootProject.file(it).absolutePath)
        maxHeapSize = "1500m"
    }
      providers.gradleProperty("qualifiedReferenceReplayRegistry").orNull?.let {
          systemProperty("qualifiedReferenceReplayRegistry",it)
      }
}

dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":core-pipeline"))
    implementation(project(":core-cache"))
    implementation(project(":core-motion"))
    implementation(project(":core-alignment"))
    implementation(project(":sync-v2-integration"))
    implementation(project(":android-video"))
    implementation(project(":android-pose-mediapipe"))
    implementation(project(":core-review"))
    implementation(project(":android-codex"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.guava.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.lottie.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)

    testImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
