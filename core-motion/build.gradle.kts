plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
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

tasks.register("verifyMotionCore") {
    group = "verification"
    description = "Run tests, verify committed artifacts, and execute the ten-second synthetic benchmark."
    dependsOn(tasks.test, checkMotionArtifacts, microbenchmark)
}
