plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core-contracts"))
    testImplementation(kotlin("test"))
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
