import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.Exec

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

allprojects {
    group = "ai.senp"
    version = "0.1.0"
}

val generatedPoseModelAssets = layout.buildDirectory.dir("generated/pose-model-assets")
val generatedPoseModelFile = generatedPoseModelAssets.map { it.file("pose_landmarker_full.task") }
val expectedPoseModelSha256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
fun verifiedPoseModel(file: File): Boolean {
    if (!file.isFile || file.length() != 9398198L) return false
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } == expectedPoseModelSha256
}

val preparePoseModelAsset by tasks.registering {
    group = "build setup"
    description = "Fetches and SHA-256 verifies the pinned MediaPipe Pose Landmarker model for APK/test assets."
    inputs.file(layout.projectDirectory.file("models/pose_landmarker_full.provenance.json"))
    outputs.file(generatedPoseModelFile)
    outputs.upToDateWhen { verifiedPoseModel(generatedPoseModelFile.get().asFile) }
    doLast {
        val outputFile = generatedPoseModelFile.get().asFile
        outputFile.parentFile.mkdirs()
        val modelUrl = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task"
        if (!verifiedPoseModel(outputFile)) {
            val temporary = File.createTempFile("pose-model-", ".download", outputFile.parentFile)
            try {
                val connection = java.net.URI(modelUrl).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                }
                connection.getInputStream().use { input -> temporary.outputStream().use(input::copyTo) }
                check(verifiedPoseModel(temporary)) { "Downloaded pose model size/SHA-256 does not match pinned provenance" }
                java.nio.file.Files.move(temporary.toPath(), outputFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }
    }
}

val prepareLivePoseFixtures by tasks.registering {
    group = "build setup"
    description = "Fetches hash-pinned upstream pose regression fixtures for native tests only."
    inputs.file(layout.projectDirectory.file("models/live-pose-fixtures.provenance.json"))
    val directory = layout.buildDirectory.dir("generated/live-pose-fixtures")
    outputs.dir(directory)
    doLast {
        val fixtures = mapOf(
            "pose.jpg" to "c8a830ed683c0276d713dd5aeda28f415f10cd6291972084a40d0d8b934ed62b",
            "pose_landmarks.pbtxt" to "69c79cdf3964d7819776eab1172e47e70684139d72a6d7edcbdd62dbb2ca5527",
        )
        directory.get().asFile.mkdirs()
        fixtures.forEach { (name, expected) ->
            val file = directory.get().file(name).asFile
            fun valid() = file.isFile && java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) } == expected
            if (!valid()) {
                java.net.URI("https://storage.googleapis.com/mediapipe-assets/$name").toURL().openStream().use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
            check(valid()) { "Upstream fixture $name failed SHA-256 verification" }
        }
    }
    // Also verify the bytes of a previously generated fixture when rerunning native tests.
    outputs.upToDateWhen { false }
}

val allowedProjectDependencies = mapOf(
    ":core-contracts" to emptySet(),
    ":core-pipeline" to setOf(":core-contracts"),
    ":core-motion" to setOf(":core-contracts", ":core-pipeline"),
    ":core-cache" to setOf(":core-contracts", ":core-pipeline"),
    ":core-alignment" to setOf(":core-contracts", ":core-pipeline"),
    // Review is a post-analysis concern and deliberately depends on nothing in the frozen pipeline.
    ":core-review" to emptySet(),
    ":headless-runner" to setOf(":core-contracts", ":core-pipeline", ":core-cache"),
    ":sync-v2-integration" to setOf(":core-contracts", ":core-pipeline", ":core-motion", ":core-alignment"),
)

val forbiddenCoreImports = listOf(
    "android.",
    "androidx.",
    "com.google.mediapipe.",
    "org.opencv.",
    "java.awt.",
    "java.net.",
    "javafx.",
    "javax.swing.",
    "io.ktor.client.",
    "okhttp3.",
    "org.bytedeco.",
    "com.arthenica.ffmpegkit.",
)

val forbiddenCoreDependencyGroups = listOf(
    "androidx",
    "com.android",
    "com.google.mediapipe",
    "org.opencv",
    "io.ktor",
    "com.squareup.okhttp3",
    "org.bytedeco",
    "com.arthenica.ffmpegkit",
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

        listOf(
            ":core-contracts",
            ":core-pipeline",
            ":core-motion",
            ":core-alignment",
            ":core-cache",
            ":core-review",
            ":sync-v2-integration",
        ).forEach { projectPath ->
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

        listOf(
            ":core-contracts",
            ":core-pipeline",
            ":core-motion",
            ":core-alignment",
            ":core-cache",
            ":core-review",
            ":sync-v2-integration",
        ).forEach { projectPath ->
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

        val duplicateCanonicalType = Regex(
            """^\s*(?:public\s+)?(?:data\s+|sealed\s+|enum\s+|value\s+)*(?:class|interface|object|typealias)\s+""" +
                """(PoseFrame|PoseLandmark|Landmark|VideoRole|FrameValidity|FrameValidityStatus|MotionSeries)\b""",
        )
        val motionSourceRoot = project(":core-motion").projectDir.resolve("src/main")
        if (motionSourceRoot.exists()) {
            motionSourceRoot.walkTopDown()
                .filter { file -> file.isFile && file.extension == "kt" }
                .forEach { file ->
                    file.useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            if (duplicateCanonicalType.containsMatchIn(line)) {
                                violations += file.relativeTo(rootDir).toString() + ":" +
                                    (index + 1) + " declares a public duplicate canonical DTO"
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
