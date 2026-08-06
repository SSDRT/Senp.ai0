package ai.senp.core.pipeline

import ai.senp.core.contracts.AlignmentResult
import ai.senp.core.contracts.ProblemWindow
import java.io.File
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** QA-facing canonical JSON/CSV artifact writer with strict mapping validation. */
object CanonicalAlignmentArtifacts {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun writeJson(analysis: AlignmentAnalysis, file: File) {
        validate(analysis)
        file.parentFile?.mkdirs()
        file.writeText(
            json.encodeToString(
                CanonicalAlignmentArtifact(
                    alignment = analysis.alignment,
                    problems = analysis.problems,
                ),
            ),
        )
    }

    fun writeCsv(alignment: AlignmentResult, file: File) {
        validateAlignment(alignment)
        file.parentFile?.mkdirs()
        file.printWriter().use { output ->
            output.println("source_timestamp_ms,reference_timestamp_ms,local_cost,confidence")
            alignment.points.forEach { point ->
                output.println(
                    listOf(
                        point.sourceTimestamp.value,
                        point.referenceTimestamp.value,
                        formatDouble(point.localCost),
                        formatDouble(point.confidence),
                    ).joinToString(","),
                )
            }
        }
    }

    fun validate(analysis: AlignmentAnalysis) {
        validateAlignment(analysis.alignment)
        analysis.problems.forEach { window -> validateProblem(window) }
    }

    private fun validateAlignment(alignment: AlignmentResult) {
        require(alignment.points.isNotEmpty()) { "canonical alignment artifact requires a non-empty mapping" }
        require(alignment.aggregateConfidence in 0.0..1.0)
        require(alignment.points.zipWithNext().all { (left, right) ->
            left.sourceTimestamp < right.sourceTimestamp &&
                left.referenceTimestamp <= right.referenceTimestamp
        }) { "canonical mapping must be source-strict and reference-monotonic" }
        alignment.points.forEach { point ->
            require(point.localCost.isFinite() && point.localCost >= 0.0)
            require(point.confidence in 0.0..1.0)
        }
    }

    private fun validateProblem(window: ProblemWindow) {
        require(window.sourceEndExclusive > window.sourceStart)
        val referenceStart = window.referenceStart
        val referenceEndExclusive = window.referenceEndExclusive
        require((referenceStart == null) == (referenceEndExclusive == null))
        if (referenceStart != null && referenceEndExclusive != null) {
            require(referenceEndExclusive > referenceStart)
        }
        require(window.meanDeviation.isFinite() && window.meanDeviation >= 0.0)
        require(window.peakDeviation.isFinite() && window.peakDeviation >= window.meanDeviation)
        require(window.severity in 0.0..1.0)
        require(window.alignmentConfidence in 0.0..1.0)
    }

    private fun formatDouble(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
}

@Serializable
private data class CanonicalAlignmentArtifact(
    val schemaVersion: Int = 1,
    val alignment: AlignmentResult,
    val problems: List<ProblemWindow>,
)
