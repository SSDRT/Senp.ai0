plugins {
    kotlin("jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

// ponytail: no jvmToolchain pin — builds against the local JDK. Lane 1 owns Gradle
// structure and should pin a toolchain once the team's JDK baseline is agreed.

tasks.test {
    useJUnitPlatform()
}
