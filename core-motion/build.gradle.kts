plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":core-pipeline"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
}

sourceSets.test {
    resources.srcDir(project(":core-contracts").file("src/test/resources"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

val committedMotionResources = layout.projectDirectory.dir("src/test/resources")

val updateMotionArtifacts by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Regenerate committed traces and the native MP33 parity fixture."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.MotionArtifactTool")
    args("update", committedMotionResources.asFile.absolutePath)
    outputs.upToDateWhen { false }
}

val checkMotionArtifacts by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fail when committed motion traces or fixtures differ from deterministic generation."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.MotionArtifactTool")
    val generated = layout.buildDirectory.dir("generated/motion-artifacts")
    args("check", committedMotionResources.asFile.absolutePath, generated.get().asFile.absolutePath)
    outputs.dir(generated)
    outputs.upToDateWhen { false }
}

val microbenchmark by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run the deterministic synthetic ten-second motion-core microbenchmark."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.Microbenchmark")
    val report = layout.buildDirectory.file("reports/microbenchmark/motion-core-10s.json")
    args(report.get().asFile.absolutePath, "120")
    outputs.file(report)
    outputs.upToDateWhen { false }
}

val spatialMicrobenchmark by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run the synchronization-v2 spatial kernel on a deterministic ten-second 15 FPS pose pair."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.SpatialMicrobenchmark")
    val report = layout.buildDirectory.file("reports/microbenchmark/spatial-sync-v2-10s.json")
    args(report.get().asFile.absolutePath, "120")
    outputs.file(report)
    outputs.upToDateWhen { false }
}

tasks.register("verifySpatialSynchronization") {
    group = "verification"
    description = "Run motion tests plus the synchronization-v2 spatial microbenchmark."
    dependsOn(tasks.test, spatialMicrobenchmark)
}

tasks.register("verifyMotionCore") {
    group = "verification"
    description = "Run tests, verify committed artifacts, and execute the ten-second synthetic benchmark."
    dependsOn(tasks.test, checkMotionArtifacts, microbenchmark)
}

tasks.register<JavaExec>("livePoseBenchmark") {
    group = "verification"
    description = "Compare production live filters against deterministic synthetic truth at 10/15/30/60 FPS."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.LivePoseTuningBenchmark")
    args(layout.buildDirectory.file("reports/live-pose/tuning-sweep.json").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("exerciseTechniqueBenchmark") {
    group = "verification"
    description = "Check every exercise policy across oracle phases and frame rates; not human accuracy."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.ExerciseTechniqueBenchmark")
    args(layout.buildDirectory.file("reports/exercise-techniques/catalog-mechanism.json").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("workoutPrecisionBenchmark") {
    group = "verification"
    description = "Sweep persistence thresholds on synthetic known-phase workout measurement scenarios."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.WorkoutPrecisionBenchmark")
    args(layout.buildDirectory.file("reports/workout-precision/threshold-sweep.json").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("qualifiedRepBenchmark") {
    group = "verification"
    description = "Exercise the causal accepted-rep engine on generated motions, faults, gaps and jitter."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.QualifiedRepBenchmark")
    args(layout.buildDirectory.file("reports/qualified-reps/mechanism-sweep.json").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("qualifiedRepVideoReplay") {
    group = "verification"
    description = "Replay original high-cadence source observations; does not claim expert rep accuracy."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.QualifiedRepReplayTool")
    args(rootProject.file(providers.gradleProperty("qualifiedReplayInput").getOrElse("test-artifacts/exercise-knowledge/rep-world-feature-cache")).absolutePath,
        rootProject.file(providers.gradleProperty("qualifiedReplayOutput").getOrElse("test-artifacts/exercise-knowledge/qualified-video-replay")).absolutePath)
    args(providers.gradleProperty("qualifiedReplayConfig").orNull?.let { rootProject.file(it).absolutePath } ?: "-")
    args(providers.gradleProperty("qualifiedReferenceCurveNodes").getOrElse("6"))
    args(providers.gradleProperty("qualifiedRequireObservedPath").getOrElse("true"))
    args(providers.gradleProperty("qualifiedReferenceCurveFit").getOrElse("ORDINARY_LEAST_SQUARES"))
    args(providers.gradleProperty("qualifiedObservedDriverEnvelope").getOrElse("true"))
    args(providers.gradleProperty("qualifiedReplayReferences").orNull?.let { rootProject.file(it).absolutePath } ?: "-")
    maxHeapSize = "1500m"
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("qualifiedRepCalibrationAudit") {
    group = "verification"
    description = "Explain unique-reference failures; diagnostic self-gate counterfactuals are not usable references."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.QualifiedRepCalibrationAudit")
    args(rootProject.file(providers.gradleProperty("qualifiedAuditInput").getOrElse("test-artifacts/exercise-knowledge/rep-world-feature-cache")).absolutePath,
        rootProject.file(providers.gradleProperty("qualifiedAuditOutput").getOrElse("test-artifacts/exercise-knowledge/qualified-calibration-audit")).absolutePath)
    maxHeapSize = "1500m"
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("qualifiedRepPathBenchmark") {
    group = "verification"
    description = "Compare endpoint jumps and observed intermediate motion across all rep policies, sides, mirrors and cadences."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.QualifiedRepPathBenchmark")
    args(rootProject.file("test-artifacts/exercise-knowledge/observed-path-mechanism.json").absolutePath)
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("qualifiedRepDepartureBenchmark") {
    group = "verification"
    description = "Verify adjacent peak support at attempt departure across all qualified-rep policies."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.QualifiedRepDepartureBenchmark")
    args(rootProject.file(providers.gradleProperty("qualifiedDepartureOutput").getOrElse("test-artifacts/exercise-knowledge/departure-peak-controls.json")).absolutePath)
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("qualifiedRepAutotune") {
    group = "verification"
    description = "Search experimental parameters on generated oracle motions; does not qualify human form accuracy."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.QualifiedRepAutotuner")
    args(rootProject.file("test-artifacts/exercise-knowledge/autonomous-parameter-search.json").absolutePath)
    maxHeapSize = "1200m"
    outputs.upToDateWhen { false }
}
