package ai.senp.alignment

import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

object AlignmentTraceWriter {
    fun writeCsv(result: AlignmentResult, file: File) {
        file.parentFile?.mkdirs()
        file.printWriter().use { output ->
            output.println(
                "user_timestamp_ms,reference_timestamp_ms,confidence,coverage,slope," +
                    "raw_difference,maximum_difference,weighted_difference,blind,active",
            )
            for (point in result.mapping) {
                output.println(
                    listOf(
                        point.userTimestampMs,
                        point.referenceTimestampMs,
                        formatDouble(point.alignmentConfidence),
                        formatDouble(point.commonCoverage),
                        formatDouble(point.pathSlope),
                        formatDouble(point.rawDifference),
                        formatDouble(point.maximumDifference),
                        formatDouble(point.weightedDifference),
                        point.blind,
                        point.active,
                    ).joinToString(","),
                )
            }
        }
    }

    fun writeJson(result: AlignmentResult, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(buildString {
            append("{\n")
            append("  \"mode\": \"").append(result.mode.name).append("\",\n")
            append("  \"meanAlignmentConfidence\": ")
                .append(formatDouble(result.meanAlignmentConfidence)).append(",\n")
            append("  \"userPhase\": ")
            appendPhase(result.userPhase, "  ")
            append(",\n")
            append("  \"referencePhase\": ")
            appendPhase(result.referencePhase, "  ")
            append(",\n")
            append("  \"mapping\": [\n")
            result.mapping.forEachIndexed { index, point ->
                append("    {")
                append("\"userTimestampMs\":").append(point.userTimestampMs).append(',')
                append("\"referenceTimestampMs\":").append(point.referenceTimestampMs).append(',')
                append("\"confidence\":").append(formatDouble(point.alignmentConfidence)).append(',')
                append("\"coverage\":").append(formatDouble(point.commonCoverage)).append(',')
                append("\"slope\":").append(formatDouble(point.pathSlope)).append(',')
                append("\"rawDifference\":").append(formatDouble(point.rawDifference)).append(',')
                append("\"maximumDifference\":").append(formatDouble(point.maximumDifference)).append(',')
                append("\"weightedDifference\":").append(formatDouble(point.weightedDifference)).append(',')
                append("\"blind\":").append(point.blind).append(',')
                append("\"active\":").append(point.active)
                append('}')
                if (index != result.mapping.lastIndex) append(',')
                append('\n')
            }
            append("  ],\n")
            append("  \"windows\": [\n")
            result.windows.forEachIndexed { index, window ->
                append("    {")
                append("\"kind\":\"").append(window.kind.name).append("\",")
                append("\"userStartMs\":").append(window.userStartMs).append(',')
                append("\"userEndMs\":").append(window.userEndMs).append(',')
                append("\"referenceStartMs\":").append(window.referenceStartMs).append(',')
                append("\"referenceEndMs\":").append(window.referenceEndMs).append(',')
                append("\"peakDifference\":").append(formatDouble(window.peakDifference)).append(',')
                append("\"meanDifference\":").append(formatDouble(window.meanDifference)).append(',')
                append("\"meanConfidence\":").append(formatDouble(window.meanConfidence))
                append('}')
                if (index != result.windows.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        })
    }

    private fun StringBuilder.appendPhase(phase: PhaseDiagnostics, indentation: String) {
        append("{\n")
        append(indentation).append("  \"activeStartMs\":").append(phase.activeStartMs ?: "null").append(",\n")
        append(indentation).append("  \"activeEndMs\":").append(phase.activeEndMs ?: "null").append(",\n")
        append(indentation).append("  \"phaseShiftMs\":").append(phase.phaseShiftMs).append(",\n")
        append(indentation).append("  \"insufficientMotion\":").append(phase.insufficientMotion).append(",\n")
        append(indentation).append("  \"motionStrength\":").append(formatDouble(phase.motionStrength)).append(",\n")
        append(indentation).append("  \"repBoundariesMs\":")
            .append(phase.repBoundariesMs.joinToString(prefix = "[", postfix = "]")).append(",\n")
        append(indentation).append("  \"anchorsMs\":")
            .append(phase.anchorsMs.joinToString(prefix = "[", postfix = "]")).append('\n')
        append(indentation).append('}')
    }

    private fun formatDouble(value: Double): String =
        if (value.isFinite()) String.format(Locale.ROOT, "%.6f", value) else "null"
}

internal fun syntheticTrack(
    fps: Int,
    durationMs: Long = 10_000L,
    shiftMs: Long = 0L,
    timingExponent: Double = 1.0,
    errorRange: LongRange? = null,
    invalidRange: LongRange? = null,
    reps: Double = 4.0,
): MotionTrack {
    require(fps > 0)
    require(durationMs > 0L)
    require(timingExponent > 0.0)
    val stepMs = 1000.0 / fps
    val timestamps = generateSequence(0) { it + 1 }
        .map { (it * stepMs).toLong() }
        .takeWhile { it <= durationMs }
        .toMutableList()
    if (timestamps.last() != durationMs) timestamps += durationMs

    val motionDuration = (durationMs - shiftMs).coerceAtLeast(1L)
    return MotionTrack(timestamps.distinct().map { timestampMs ->
        val normalized = ((timestampMs - shiftMs).coerceAtLeast(0L).toDouble() / motionDuration)
            .coerceIn(0.0, 1.0)
            .pow(timingExponent)
        val phase = normalized * reps * 2.0 * PI
        val invalid = invalidRange?.contains(timestampMs) == true
        val formError = if (errorRange?.contains(timestampMs) == true) 60.0 else 0.0
        MotionFrame(
            timestampMs = timestampMs,
            features = mapOf(
                "primary" to FeatureSample(
                    if (invalid) null else 90.0 + 35.0 * sin(phase),
                ),
                "secondary" to FeatureSample(
                    if (invalid) null else 70.0 + 20.0 * cos(phase),
                ),
                "stable" to FeatureSample(
                    if (invalid) null else 40.0 + 5.0 * sin(phase * 0.5),
                ),
                "form" to FeatureSample(
                    if (invalid) null else 55.0 + 6.0 * sin(phase * 0.5) + formError,
                ),
            ),
        )
    })
}

internal val DEFAULT_PROFILE = ExerciseProfile(
    id = "synthetic-curl",
    featureRules = linkedMapOf(
        "primary" to FeatureRule(
            weight = 1.0,
            distanceScale = 35.0,
            phaseWeight = 1.0,
            minimumMotionRange = 12.0,
        ),
        "secondary" to FeatureRule(
            weight = 0.70,
            distanceScale = 25.0,
            phaseWeight = 0.30,
            minimumMotionRange = 8.0,
        ),
        "stable" to FeatureRule(
            weight = 0.30,
            distanceScale = 15.0,
            phaseWeight = 0.0,
            minimumMotionRange = 3.0,
        ),
        "form" to FeatureRule(
            weight = 1.0,
            distanceScale = 35.0,
            phaseWeight = 0.0,
            minimumMotionRange = 5.0,
        ),
    ),
)

private fun uncertainClassificationTrace(): AlignmentResult {
    val base = AlignmentEngine().align(
        syntheticTrack(10, durationMs = 3_000L, reps = 3.0),
        syntheticTrack(10, durationMs = 3_000L, reps = 3.0),
        DEFAULT_PROFILE,
    )
    val mapping = base.mapping.map { point ->
        val error = point.userTimestampMs in 400L..800L
        point.copy(
            alignmentConfidence = if (error) 0.45 else point.alignmentConfidence,
            pathSlope = if (error) 1.60 else point.pathSlope,
            rawDifference = if (error) 26.0 else 0.0,
            maximumDifference = if (error) 32.0 else 0.0,
            weightedDifference = if (error) 11.7 else 0.0,
            blind = false,
            active = true,
        )
    }
    val boundaries = listOf(0L, 1_000L, 2_000L, 3_000L)
    return base.copy(
        mapping = mapping,
        windows = WindowEngine(AlignmentConfig()).detect(mapping, boundaries),
        userPhase = base.userPhase.copy(repBoundariesMs = boundaries),
    )
}

fun main(args: Array<String>) {
    val outputDirectory = File(args.firstOrNull() ?: "build/traces")
    outputDirectory.mkdirs()
    val scenarios = linkedMapOf(
        "equal-motion" to AlignmentEngine().align(
            syntheticTrack(15),
            syntheticTrack(20),
            DEFAULT_PROFILE,
        ),
        "deliberate-error" to AlignmentEngine().align(
            syntheticTrack(15, errorRange = 4_000L..5_000L),
            syntheticTrack(20),
            DEFAULT_PROFILE,
        ),
        "long-invalid-span" to AlignmentEngine().align(
            syntheticTrack(20, invalidRange = 3_000L..5_000L),
            syntheticTrack(15),
            DEFAULT_PROFILE,
        ),
        "speed-only" to AlignmentEngine().align(
            syntheticTrack(15, timingExponent = 1.18),
            syntheticTrack(20),
            DEFAULT_PROFILE,
        ),
        "single-cycle" to AlignmentEngine().align(
            syntheticTrack(20, reps = 1.0),
            syntheticTrack(15, reps = 1.0),
            DEFAULT_PROFILE,
        ),
        "uncertain-classification" to uncertainClassificationTrace(),
    )

    val summary = buildString {
        appendLine("scenario,mode,mapping_points,mean_confidence,genuine_windows,uncertain_windows")
        for ((name, result) in scenarios) {
            AlignmentTraceWriter.writeJson(result, File(outputDirectory, "$name.json"))
            AlignmentTraceWriter.writeCsv(result, File(outputDirectory, "$name.csv"))
            append(name).append(',')
                .append(result.mode).append(',')
                .append(result.mapping.size).append(',')
                .append(String.format(Locale.ROOT, "%.6f", result.meanAlignmentConfidence)).append(',')
                .append(result.windows.count { it.kind == WindowKind.GENUINE_FORM_ERROR }).append(',')
                .append(result.windows.count { it.kind == WindowKind.UNCERTAIN_ALIGNMENT }).appendLine()
        }
    }
    File(outputDirectory, "summary.csv").writeText(summary)
    print(summary)
    println("trace_directory=${outputDirectory.absolutePath}")
}
