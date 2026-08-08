package ai.senp.sync.validation

import ai.senp.core.contracts.SynchronizationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
    classDiscriminator = "type"
    ignoreUnknownKeys = false
}

fun main(args: Array<String>) {
    require(args.isNotEmpty()) {
        "usage: generate <output-dir> [seed] | validate <scenario-id> <result-json> <report-json> [seed]"
    }
    when (args[0]) {
        "generate" -> generate(args)
        "validate" -> validate(args)
        else -> error("unknown validation command: " + args[0])
    }
}

private fun generate(args: Array<String>) {
    require(args.size in 2..3) { "generate requires <output-dir> [seed]" }
    val output = Path.of(args[1]).toAbsolutePath().normalize()
    val seed = args.getOrNull(2)?.toLong() ?: SyntheticScenarioGenerator.DEFAULT_SEED
    val suite = SyntheticScenarioGenerator.generate(seed)
    Files.createDirectories(output)
    output.resolve("synthetic-suite.json").writeText(json.encodeToString(suite))
    val scenarioDir = output.resolve("scenarios")
    Files.createDirectories(scenarioDir)
    suite.scenarios.forEach { scenario ->
        scenarioDir.resolve(scenario.scenarioId + ".json").writeText(json.encodeToString(scenario))
    }
    output.resolve("coverage-matrix.json").writeText(json.encodeToString(suite.coverage))
    println(json.encodeToString(mapOf("output" to output.toString(), "scenario_count" to suite.scenarioCount.toString(), "seed" to seed.toString())))
}

private fun validate(args: Array<String>) {
    require(args.size in 4..5) { "validate requires <scenario-id> <result-json> <report-json> [seed]" }
    val scenarioId = args[1]
    val resultPath = Path.of(args[2])
    val reportPath = Path.of(args[3])
    val seed = args.getOrNull(4)?.toLong() ?: SyntheticScenarioGenerator.DEFAULT_SEED
    val scenario = SyntheticScenarioGenerator.generate(seed).scenarios.single { it.scenarioId == scenarioId }
    val result = json.decodeFromString<SynchronizationResult>(Files.readString(resultPath))
    val report = InvariantValidator.validate(scenario, result)
    reportPath.parent?.let(Files::createDirectories)
    reportPath.writeText(json.encodeToString(report))
    println(json.encodeToString(report))
    check(report.passed) { "Sync-v2 invariant validation failed for " + scenarioId }
}
