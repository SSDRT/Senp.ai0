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

val generateTraces by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generate deterministic human-inspectable motion traces."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.senp.motion.TraceGenerator")
    val traceDirectory = layout.projectDirectory.dir("src/test/resources/traces")
    args(traceDirectory.asFile.absolutePath)
    outputs.dir(traceDirectory)
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
    description = "Run tests, regenerate traces, and execute the ten-second synthetic benchmark."
    dependsOn(tasks.test, generateTraces, microbenchmark)
}
