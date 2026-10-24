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
    args(providers.gradleProperty("qualifiedReplayMirror").getOrElse("DIRECT"))
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

tasks.register<JavaExec>("qualifiedRepEvidenceBenchmark") {
    group = "verification"
    description = "Measure compatible path evidence and asymmetric return-branch handling on constructed controls."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.QualifiedRepEvidenceBenchmark")
    args(rootProject.file(providers.gradleProperty("qualifiedEvidenceOutput").getOrElse("test-artifacts/exercise-knowledge/evidence-controls.json")).absolutePath)
    if (providers.gradleProperty("qualifiedEvidenceReportOnly").getOrElse("false").toBoolean()) args("report-only")
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("qualifiedRepCounterfactualBenchmark") {
    group = "verification"
    description = "Corrupt accepted real-timestamp attempts with explicit feature faults, missing evidence and identity failures."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.QualifiedRepCounterfactualBenchmark")
    args(rootProject.file("test-artifacts/exercise-knowledge").absolutePath,
        rootProject.file(providers.gradleProperty("qualifiedCounterfactualOutput").getOrElse("test-artifacts/exercise-knowledge/mirror-calibration-counterfactuals.json")).absolutePath)
    args(providers.gradleProperty("qualifiedCounterfactualRegistry").getOrElse("mirror-calibration-summary"),
        providers.gradleProperty("qualifiedCounterfactualReplayPrefix").getOrElse("mirror-calibration"))
    maxHeapSize = "1500m"
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("qualifiedRepReferenceContextAudit") {
    group = "verification"
    description = "Inspect subject/segment contexts in frozen references and a controlled cross-person pooling case."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.QualifiedRepReferenceContextAudit")
    args(rootProject.file("test-artifacts/exercise-knowledge").absolutePath,
        rootProject.file("test-artifacts/exercise-knowledge/readiness-reference-context-audit.json").absolutePath)
    maxHeapSize = "1500m"
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("uiprmdDevelopmentReplay") {
    group = "verification"
    description = "Replay development-only independent Vicon marker descriptors through the production rep engine."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.UiprmdDevelopmentReplay")
    args(rootProject.file("test-artifacts/exercise-knowledge/uiprmd/development-vicon").absolutePath,
        rootProject.file(providers.gradleProperty("uiprmdDevelopmentOutput").getOrElse("test-artifacts/exercise-knowledge/uiprmd/development-replay.json")).absolutePath)
    maxHeapSize = "1200m"
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("uiprmdValidationReplay") {
    group = "verification"
    description = "Replay the frozen validation cohort; final-test subjects are forbidden."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.UiprmdValidationReplay")
    args(rootProject.file("test-artifacts/exercise-knowledge/uiprmd/validation-vicon").absolutePath,
        rootProject.file("test-artifacts/exercise-knowledge/uiprmd/validation-replay.json").absolutePath,
        rootProject.file("test-artifacts/exercise-knowledge/uiprmd/development-vicon").absolutePath,
        rootProject.file("models/uiprmd-quality-validation-protocol.json").absolutePath)
    maxHeapSize = "1200m"
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("uiprmdSideBindingReplay") {
    group = "verification"
    description = "Research-only causal interchangeable-side variant on development/already-opened validation; never final test."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.UiprmdSideBindingReplay")
    val scope = providers.gradleProperty("uiprmdSideBindingScope").getOrElse("development")
    val mode = providers.gradleProperty("uiprmdSideBindingMode").getOrElse("causal")
    require(scope in setOf("development","validation"))
    require(mode in setOf("fixed","causal"))
    args(rootProject.file("test-artifacts/exercise-knowledge/uiprmd/$scope-vicon").absolutePath,
        rootProject.file("test-artifacts/exercise-knowledge/uiprmd/side-binding-$scope-$mode-replay.json").absolutePath,
        rootProject.file("test-artifacts/exercise-knowledge/uiprmd/development-vicon").absolutePath,
        rootProject.file("models/uiprmd-side-binding-protocol.json").absolutePath,scope,mode)
    maxHeapSize = "1200m"
    outputs.upToDateWhen { false }
}
