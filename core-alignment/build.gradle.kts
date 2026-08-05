import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("ai.senp.alignment.TraceKt")
}

tasks.register<JavaExec>("microbenchmark") {
    group = "verification"
    description = "Runs reproducible 10-second alignment tracks at 10/15/20/30 FPS"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ai.senp.alignment.MicrobenchmarkKt")
}
