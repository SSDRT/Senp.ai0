package ai.senp.motion

import java.io.File
import java.util.Locale

object MotionArtifactTool {
    @JvmStatic
    fun main(args: Array<String>) {
        Locale.setDefault(Locale.US)
        require(args.size >= 2) { "usage: MotionArtifactTool <update|check> <committed-resources> [generated-output]" }
        val mode = args[0]
        val committedResources = File(args[1])
        when (mode) {
            "update" -> update(committedResources)
            "check" -> {
                require(args.size == 3) { "check mode requires a generated-output directory" }
                check(committedResources, File(args[2]))
            }
            else -> error("unknown mode: $mode")
        }
    }

    fun update(committedResources: File) {
        val traceDirectory = File(committedResources, "traces")
        TraceGenerator.generate(traceDirectory)
        val fixtureDirectory = File(committedResources, "fixtures").apply { mkdirs() }
        File(fixtureDirectory, MotionFixtureGenerator.NATIVE_FIXTURE_FILE)
            .writeText(MotionFixtureGenerator.generateNativeFixture())
        println("updated_motion_artifacts=${committedResources.absolutePath}")
    }

    fun check(committedResources: File, generatedOutput: File) {
        if (generatedOutput.exists()) generatedOutput.deleteRecursively()
        generatedOutput.mkdirs()
        val generatedTraces = File(generatedOutput, "traces")
        TraceGenerator.generate(generatedTraces)
        val generatedFixtures = File(generatedOutput, "fixtures").apply { mkdirs() }
        File(generatedFixtures, MotionFixtureGenerator.NATIVE_FIXTURE_FILE)
            .writeText(MotionFixtureGenerator.generateNativeFixture())

        val generatedPaths = generatedOutput.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(generatedOutput).invariantSeparatorsPath }
            .toSortedSet()
        val committedPaths = buildSet {
            File(committedResources, "traces").listFiles()?.filter(File::isFile)?.forEach {
                add("traces/${it.name}")
            }
            val nativeFixture = File(committedResources, "fixtures/${MotionFixtureGenerator.NATIVE_FIXTURE_FILE}")
            if (nativeFixture.isFile) add("fixtures/${nativeFixture.name}")
        }
        val pathDifferences = (generatedPaths union committedPaths) - (generatedPaths intersect committedPaths)
        val contentDifferences = (generatedPaths intersect committedPaths).filter { relativePath ->
            val committed = File(committedResources, relativePath)
            val generated = File(generatedOutput, relativePath)
            !committed.readBytes().contentEquals(generated.readBytes())
        }
        val differences = (pathDifferences + contentDifferences).sorted()
        check(differences.isEmpty()) {
            "motion artifacts are stale, extra, or missing: ${differences.joinToString()}. " +
                "Run :core-motion:updateMotionArtifacts and commit the result."
        }
        println("motion_artifacts_verified=${generatedPaths.size}")
    }
}
