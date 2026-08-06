package ai.senp.motion

import java.io.File
import java.util.Locale
import kotlin.math.abs

object TraceGenerator {
    private val config = MotionConfig(
        maxRepairGapMs = 220L,
        continuityBreakGapMs = 300L,
        emaHalfLifeMs = 120L,
        blindEnterDurationMs = 200L,
        recoverDurationMs = 150L,
    )

    private val bothArms = ExerciseProfile(
        id = "trace_both_arms",
        required = setOf(
            LandmarkId.LEFT_SHOULDER,
            LandmarkId.LEFT_ELBOW,
            LandmarkId.LEFT_WRIST,
            LandmarkId.RIGHT_SHOULDER,
            LandmarkId.RIGHT_ELBOW,
            LandmarkId.RIGHT_WRIST,
        ),
        sidePolicy = SidePolicy.BOTH,
        minimumRequiredCoverage = 0.75,
    )

    private val leftCurl = ExerciseProfiles.bicepsCurl.copy(sidePolicy = SidePolicy.LEFT_ONLY)

    @JvmStatic
    fun main(args: Array<String>) {
        Locale.setDefault(Locale.US)
        generate(File(args.firstOrNull() ?: "build/traces"))
    }

    fun generate(output: File) {
        output.mkdirs()
        output.listFiles()?.filter { it.extension in setOf("csv", "json") }?.forEach { it.delete() }

        val scenarios = linkedMapOf<String, Pair<List<PoseFrame>, ExerciseProfile>>()
        scenarios["clean"] = SyntheticPose.sequence(fps = 15) to leftCurl
        scenarios["jittered"] = SyntheticPose.sequence(fps = 15, noise = 0.025) to leftCurl
        scenarios["short-gap"] = SyntheticPose.withMissing(
            SyntheticPose.sequence(fps = 15),
            4000L,
            4080L,
            setOf(LandmarkId.LEFT_WRIST),
        ) to leftCurl
        scenarios["long-blind"] = SyntheticPose.withMissing(
            SyntheticPose.sequence(fps = 15),
            3500L,
            4500L,
            bothArms.required,
        ) to bothArms
        for (fps in listOf(10, 15, 20)) {
            scenarios["fps-$fps"] = SyntheticPose.withMissing(
                SyntheticPose.sequence(fps = fps),
                4000L,
                4080L,
                setOf(LandmarkId.LEFT_WRIST),
            ) to leftCurl
        }

        val summaries = scenarios.map { (name, definition) ->
            val (frames, profile) = definition
            val result = MotionEngine(config).analyze(frames, profile)
            writeCsv(File(output, "$name.csv"), result)
            summarize(name, result)
        }
        File(output, "summary.json").writeText(renderSummary(summaries))
        println("trace_dir=${output.absolutePath} files=${scenarios.size + 1}")
    }

    private data class Summary(
        val scenario: String,
        val frames: Int,
        val durationMs: Long,
        val meanScore: Double,
        val minScore: Double,
        val maxScore: Double,
        val validityCounts: Map<FrameValidity, Int>,
        val repairedFrames: Int,
        val continuityBreakFrames: Int,
        val nullAngleFrames: Int,
        val angleRangeDeg: Double?,
        val maxAbsAngularVelocityDegPerSecond: Double?,
        val commonWristX: Map<Long, Double>,
    )

    private fun writeCsv(file: File, result: List<ProcessedFrame>) {
        file.printWriter().use { output ->
            output.println(
                "timestamp_ms,score,validity,selected_side,required_coverage,required_visibility," +
                    "required_presence,preferred_quality,repaired_fraction,clipping,instability," +
                    "left_elbow_deg,left_elbow_velocity_deg_s,left_wrist_x,left_wrist_repaired," +
                    "continuity_break,swap_applied,impossible_proportions",
            )
            var previousAngle: Double? = null
            var previousTimestampMs = 0L
            for (item in result) {
                val angle = MotionFeatures.angles(item.frame, item.quality.validity)["left_elbow"]
                val velocity = MotionFeatures.angularVelocity(
                    previousAngle,
                    angle,
                    previousTimestampMs,
                    item.frame.timestampMs,
                )
                output.println(
                    listOf(
                        item.frame.timestampMs,
                        decimal(item.quality.score),
                        item.quality.validity,
                        item.quality.selectedSide ?: "",
                        decimal(item.quality.requiredCoverage),
                        decimal(item.quality.requiredVisibility),
                        decimal(item.quality.requiredPresence),
                        decimal(item.quality.preferredQuality),
                        decimal(item.quality.repairedFraction),
                        decimal(item.quality.clipping),
                        decimal(item.quality.instability),
                        angle?.let(::decimal) ?: "",
                        velocity?.let(::decimal) ?: "",
                        item.frame[LandmarkId.LEFT_WRIST].image?.x?.let(::decimal) ?: "",
                        LandmarkId.LEFT_WRIST in item.repairedLandmarks,
                        item.continuityBreakLandmarks.isNotEmpty(),
                        item.guardrails.leftRightSwapApplied,
                        item.guardrails.impossibleProportions,
                    ).joinToString(","),
                )
                previousAngle = angle
                previousTimestampMs = item.frame.timestampMs
            }
        }
    }

    private fun summarize(name: String, result: List<ProcessedFrame>): Summary {
        val angles = result.map { MotionFeatures.angles(it.frame, it.quality.validity)["left_elbow"] }
        val velocities = angles.indices.drop(1).mapNotNull { index ->
            MotionFeatures.angularVelocity(
                angles[index - 1],
                angles[index],
                result[index - 1].frame.timestampMs,
                result[index].frame.timestampMs,
            )
        }
        val finiteAngles = angles.filterNotNull()
        val commonWristX = listOf(2000L, 4000L, 6000L, 8000L).mapNotNull { timestampMs ->
            result.firstOrNull { it.frame.timestampMs == timestampMs }
                ?.frame
                ?.get(LandmarkId.LEFT_WRIST)
                ?.image
                ?.x
                ?.let { timestampMs to it }
        }.toMap()
        return Summary(
            scenario = name,
            frames = result.size,
            durationMs = if (result.isEmpty()) 0L else result.last().frame.timestampMs - result.first().frame.timestampMs,
            meanScore = result.map { it.quality.score }.averageOrZero(),
            minScore = result.minOfOrNull { it.quality.score } ?: 0.0,
            maxScore = result.maxOfOrNull { it.quality.score } ?: 0.0,
            validityCounts = FrameValidity.entries.associateWith { validity ->
                result.count { it.quality.validity == validity }
            },
            repairedFrames = result.count { it.repairedLandmarks.isNotEmpty() },
            continuityBreakFrames = result.count { it.continuityBreakLandmarks.isNotEmpty() },
            nullAngleFrames = angles.count { it == null },
            angleRangeDeg = if (finiteAngles.isEmpty()) null else finiteAngles.max() - finiteAngles.min(),
            maxAbsAngularVelocityDegPerSecond = velocities.maxOfOrNull { abs(it) },
            commonWristX = commonWristX,
        )
    }

    private fun renderSummary(summaries: List<Summary>): String {
        val fps = summaries.filter { it.scenario.startsWith("fps-") }
        val meanScoreSpread = fps.maxOf { it.meanScore } - fps.minOf { it.meanScore }
        val angleRanges = fps.mapNotNull { it.angleRangeDeg }
        val angleRangeSpread = angleRanges.max() - angleRanges.min()
        val commonTimestamps = fps.flatMap { it.commonWristX.keys }.toSet().sorted()
        val maxCommonWristSpread = commonTimestamps.maxOf { timestamp ->
            val values = fps.mapNotNull { it.commonWristX[timestamp] }
            values.max() - values.min()
        }

        return buildString {
            append("{\n  \"schema_version\": 1,\n  \"time_unit\": \"milliseconds\",\n  \"scenarios\": [\n")
            summaries.forEachIndexed { index, summary ->
                append("    {\n")
                append("      \"scenario\": \"").append(summary.scenario).append("\",\n")
                append("      \"frames\": ").append(summary.frames).append(",\n")
                append("      \"duration_ms\": ").append(summary.durationMs).append(",\n")
                append("      \"mean_score\": ").append(decimal(summary.meanScore)).append(",\n")
                append("      \"min_score\": ").append(decimal(summary.minScore)).append(",\n")
                append("      \"max_score\": ").append(decimal(summary.maxScore)).append(",\n")
                append("      \"repaired_frames\": ").append(summary.repairedFrames).append(",\n")
                append("      \"continuity_break_frames\": ").append(summary.continuityBreakFrames).append(",\n")
                append("      \"null_angle_frames\": ").append(summary.nullAngleFrames).append(",\n")
                append("      \"angle_range_deg\": ").append(summary.angleRangeDeg?.let(::decimal) ?: "null").append(",\n")
                append("      \"max_abs_angular_velocity_deg_s\": ")
                    .append(summary.maxAbsAngularVelocityDegPerSecond?.let(::decimal) ?: "null").append(",\n")
                append("      \"validity\": {")
                FrameValidity.entries.forEachIndexed { validityIndex, validity ->
                    if (validityIndex > 0) append(", ")
                    append("\"").append(validity.name).append("\": ").append(summary.validityCounts.getValue(validity))
                }
                append("}\n    }")
                if (index < summaries.lastIndex) append(',')
                append('\n')
            }
            append("  ],\n  \"fps_invariance\": {\n")
            append("    \"fps_values\": [10, 15, 20],\n")
            append("    \"mean_score_spread\": ").append(decimal(meanScoreSpread)).append(",\n")
            append("    \"angle_range_spread_deg\": ").append(decimal(angleRangeSpread)).append(",\n")
            append("    \"max_common_timestamp_wrist_x_spread\": ").append(decimal(maxCommonWristSpread)).append("\n")
            append("  }\n}\n")
        }
    }

    private fun decimal(value: Double): String = "%.6f".format(Locale.US, value)
    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
