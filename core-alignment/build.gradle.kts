plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":core-pipeline"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("ai.senp.alignment.TraceKt")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("microbenchmark") {
    group = "verification"
    description = "Runs reproducible 10-second canonical alignment tracks at 10/15/20/30 FPS"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ai.senp.alignment.MicrobenchmarkKt")
}


tasks.register<JavaExec>("canonicalTrace") {
    group = "verification"
    description = "Writes deterministic canonical alignment JSON/CSV QA artifacts"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ai.senp.alignment.CanonicalTraceKt")
}

tasks.register<JavaExec>("canonicalMicrobenchmark") {
    group = "verification"
    description = "Benchmarks the canonical phase/alignment adapters at 10/15/20/30 FPS"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ai.senp.alignment.CanonicalMicrobenchmarkKt")
}
