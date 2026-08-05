import org.gradle.api.artifacts.ProjectDependency

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "ai.senp"
    version = "0.1.0"
}

val allowedProjectDependencies = mapOf(
    ":core-contracts" to emptySet(),
    ":core-pipeline" to setOf(":core-contracts"),
    ":core-cache" to setOf(":core-contracts", ":core-pipeline"),
    ":headless-runner" to setOf(":core-contracts", ":core-pipeline", ":core-cache"),
)

val forbiddenCoreImports = listOf(
    "android.",
    "androidx.",
    "com.google.mediapipe.",
    "org.opencv.",
    "java.awt.",
    "java.net.",
    "io.ktor.client.",
    "okhttp3.",
)

val forbiddenCoreDependencyGroups = listOf(
    "androidx",
    "com.android",
    "com.google.mediapipe",
    "org.opencv",
    "io.ktor",
    "com.squareup.okhttp3",
)

val checkCoreBoundaries by tasks.registering {
    group = "verification"
    description = "Enforces project dependency direction and pure Kotlin/JVM core imports."

    doLast {
        val violations = mutableListOf<String>()

        allowedProjectDependencies.forEach { (projectPath, allowed) ->
            val target = project(projectPath)
            val actual = target.configurations
                .flatMap { configuration ->
                    configuration.dependencies
                        .withType(ProjectDependency::class.java)
                        .map(ProjectDependency::getPath)
                }
                .toSet()

            (actual - allowed).sorted().forEach { dependency ->
                violations += "$projectPath must not depend on $dependency"
            }
        }

        listOf(":core-contracts", ":core-pipeline", ":core-cache").forEach { projectPath ->
            val target = project(projectPath)
            target.configurations
                .flatMap { configuration -> configuration.dependencies }
                .filterNot { dependency -> dependency is ProjectDependency }
                .distinctBy { dependency -> Triple(dependency.group, dependency.name, dependency.version) }
                .forEach { dependency ->
                    val group = dependency.group
                    if (group != null && forbiddenCoreDependencyGroups.any { prefix ->
                            group == prefix || group.startsWith("$prefix.")
                        }
                    ) {
                        violations += "$projectPath must not depend on $group:${dependency.name}"
                    }
                }
        }

        listOf(":core-contracts", ":core-pipeline", ":core-cache").forEach { projectPath ->
            val sourceRoot = project(projectPath).projectDir.resolve("src/main")
            if (!sourceRoot.exists()) return@forEach

            sourceRoot.walkTopDown()
                .filter { file -> file.isFile && file.extension in setOf("kt", "java") }
                .forEach { file ->
                    file.useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            val importedName = line.trim()
                                .takeIf { it.startsWith("import ") }
                                ?.removePrefix("import ")
                                ?.substringBefore(" as ")
                            if (importedName != null && forbiddenCoreImports.any(importedName::startsWith)) {
                                violations += "${file.relativeTo(rootDir)}:${index + 1} imports forbidden type $importedName"
                            }
                        }
                    }
                }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString(prefix = "Core boundary violations:\n", separator = "\n"))
        }
    }
}

tasks.named("check") {
    dependsOn(subprojects.map { subproject -> "${subproject.path}:check" })
    dependsOn(checkCoreBoundaries)
}
