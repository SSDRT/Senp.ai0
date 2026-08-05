package ai.senp.motion

import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object MotionFixtureGenerator {
    const val NATIVE_FIXTURE_FILE = "mp33_squat_motion_core_v1.json"

    val fixtureConfig = MotionConfig(
        maxRepairGapMs = 220L,
        continuityBreakGapMs = 400L,
        emaHalfLifeMs = 120L,
        blindEnterDurationMs = 250L,
        recoverDurationMs = 200L,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        Locale.setDefault(Locale.US)
        val outputDirectory = File(args.firstOrNull() ?: "build/fixtures").apply { mkdirs() }
        val output = File(outputDirectory, NATIVE_FIXTURE_FILE)
        output.writeText(generateNativeFixture())
        println("fixture=${output.absolutePath} bytes=${output.length()}")
    }

    fun generateNativeFixture(): String {
        val input = nativeSquatInput()
        val processed = MotionEngine(fixtureConfig).analyze(input, ExerciseProfiles.squat)
        val normalizer = PoseNormalizer(fixtureConfig.minScale)
        val root = buildJsonObject {
            put("fixture_schema_version", MotionCoreVersions.FIXTURE_SCHEMA)
            put("fixture_id", "mp33-squat-motion-core-v1")
            objectValue("provenance") {
                put("implementation_repository", "https://github.com/SSDRT/Senp.ai0")
                put("repository_baseline_commit", "21f12f8e6c3c62b5a0af6558db1ca337129a25c3")
                put("motion_core_base_commit", "e8a280b853ceed4b087826aa09c1d370b8ea79e8")
                put("behavior_reference_repository", "https://github.com/SSDRT/senp.ai")
                put("behavior_reference_commit", "a54a8453907a6cd1ece61ad7565020a98118c032")
                put("generated_by", "ai.senp.motion.MotionFixtureGenerator")
            }
            objectValue("versions") {
                put("pipeline", MotionCoreVersions.PIPELINE)
                put("landmark_contract", MotionCoreVersions.MP33_CONTRACT)
                put("normalization", MotionCoreVersions.NORMALIZATION)
                put("exercise_profiles", MotionCoreVersions.EXERCISE_PROFILES)
                put("angles", MotionCoreVersions.ANGLES)
            }
            put("skeleton", "mediapipe_pose_33")
            put("time_unit", "milliseconds")
            put("tolerance", 1e-9)
            objectValue("config") {
                put("min_visibility", fixtureConfig.minVisibility)
                put("min_presence", fixtureConfig.minPresence)
                put("min_angle_confidence", fixtureConfig.minAngleConfidence)
                put("max_repair_gap_ms", fixtureConfig.maxRepairGapMs)
                put("continuity_break_gap_ms", fixtureConfig.continuityBreakGapMs)
                put("ema_half_life_ms", fixtureConfig.emaHalfLifeMs)
                put("blind_enter_threshold", fixtureConfig.blindEnterThreshold)
                put("usable_threshold", fixtureConfig.usableThreshold)
                put("blind_enter_duration_ms", fixtureConfig.blindEnterDurationMs)
                put("recover_duration_ms", fixtureConfig.recoverDurationMs)
                put("repaired_confidence_cap", fixtureConfig.repairedConfidenceCap)
            }
            objectValue("profile") {
                put("id", ExerciseProfiles.squat.id)
                put("required", landmarkNames(ExerciseProfiles.squat.required))
                put("preferred", landmarkNames(ExerciseProfiles.squat.preferred))
                put("side_policy", ExerciseProfiles.squat.sidePolicy.name)
            }
            objectValue("events") {
                event("short_gap", 1600L, 1667L, setOf(LandmarkId.LEFT_KNEE))
                event("long_blind", 2800L, 3600L, ExerciseProfiles.squat.required)
                event(
                    "preferred_shoulder_gap",
                    4600L,
                    4733L,
                    setOf(LandmarkId.LEFT_SHOULDER, LandmarkId.RIGHT_SHOULDER),
                )
            }
            put("input_frames", buildJsonArray { input.forEach { add(frameJson(it)) } })
            put("expected_frames", buildJsonArray {
                processed.forEach { item ->
                    val normalized = normalizer.normalizeImage(item.frame)
                    val normalizedWorld = normalizer.normalizeWorld(item.frame, orientToBodyAxes = true)
                    add(buildJsonObject {
                        put("timestamp_ms", item.frame.timestampMs)
                        put("tracked_landmarks", landmarksJson(item.frame.landmarks))
                        put("quality", qualityJson(item.quality))
                        put("repaired_landmarks", landmarkNames(item.repairedLandmarks))
                        put("continuity_break_landmarks", landmarkNames(item.continuityBreakLandmarks))
                        objectValue("guardrails") {
                            put("left_right_swap_applied", item.guardrails.leftRightSwapApplied)
                            put("impossible_proportions", item.guardrails.impossibleProportions)
                        }
                        objectValue("normalized_image") {
                            put("status", normalized.status.name)
                            put("root", vecJson(normalized.root))
                            put("scale", nullableNumber(normalized.scale))
                            put("landmarks", landmarksJson(normalized.frame.landmarks))
                        }
                        objectValue("normalized_world") {
                            put("status", normalizedWorld.status.name)
                            put("root", vecJson(normalizedWorld.root))
                            put("scale", nullableNumber(normalizedWorld.scale))
                            put("landmarks", landmarksJson(normalizedWorld.frame.landmarks))
                        }
                        put(
                            "angles_image_deg",
                            nullableNumberMap(
                                MotionFeatures.angles(
                                    frame = item.frame,
                                    validity = item.quality.validity,
                                    minVisibility = fixtureConfig.minAngleConfidence,
                                    minPresence = fixtureConfig.minAngleConfidence,
                                    coordinateSpace = CoordinateSpace.IMAGE,
                                ),
                            ),
                        )
                        put(
                            "angles_world_deg",
                            nullableNumberMap(
                                MotionFeatures.angles(
                                    frame = item.frame,
                                    validity = item.quality.validity,
                                    minVisibility = fixtureConfig.minAngleConfidence,
                                    minPresence = fixtureConfig.minAngleConfidence,
                                    coordinateSpace = CoordinateSpace.WORLD,
                                ),
                            ),
                        )
                        put("torso_image", torsoJson(MotionFeatures.torso(item.frame, CoordinateSpace.IMAGE)))
                    })
                }
            })
        }
        return Json.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    fun nativeSquatInput(): List<PoseFrame> {
        val base = SyntheticPose.squatSequence(fps = 15, seconds = 6, noise = 0.002)
        val shortGap = SyntheticPose.withMissing(base, 1600L, 1667L, setOf(LandmarkId.LEFT_KNEE))
        val blind = SyntheticPose.withMissing(shortGap, 2800L, 3600L, ExerciseProfiles.squat.required)
        return SyntheticPose.withMissing(
            blind,
            4600L,
            4733L,
            setOf(LandmarkId.LEFT_SHOULDER, LandmarkId.RIGHT_SHOULDER),
        )
    }

    private fun frameJson(frame: PoseFrame): JsonObject = buildJsonObject {
        put("timestamp_ms", frame.timestampMs)
        put("landmarks", landmarksJson(frame.landmarks))
    }

    private fun landmarksJson(landmarks: List<Landmark>): JsonArray = buildJsonArray {
        landmarks.forEach { landmark ->
            add(buildJsonObject {
                put("image", vecJson(landmark.image))
                put("world", vecJson(landmark.world))
                put("visibility", landmark.visibility)
                put("presence", landmark.presence)
                put("repaired", landmark.repaired)
            })
        }
    }

    private fun qualityJson(quality: QualityResult): JsonObject = buildJsonObject {
        put("score", quality.score)
        put("validity", quality.validity.name)
        put("selected_side", quality.selectedSide?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("required_coverage", quality.requiredCoverage)
        put("required_visibility", quality.requiredVisibility)
        put("required_presence", quality.requiredPresence)
        put("preferred_quality", quality.preferredQuality)
        put("repaired_fraction", quality.repairedFraction)
        put("clipping", quality.clipping)
        put("instability", quality.instability)
    }

    private fun torsoJson(torso: TorsoFeatures?): JsonElement = torso?.let { value ->
        buildJsonObject {
            put("pelvis_center", vecJson(value.pelvisCenter))
            put("shoulder_center", vecJson(value.shoulderCenter))
            put("torso_vector", vecJson(value.torsoVector))
            put("torso_length", value.torsoLength)
            put("lean_from_vertical_deg", value.leanFromVerticalDeg)
            put("shoulder_tilt_deg", value.shoulderTiltDeg)
            put("hip_tilt_deg", value.hipTiltDeg)
        }
    } ?: JsonNull

    private fun vecJson(value: Vec3?): JsonElement = value?.let {
        buildJsonArray {
            add(JsonPrimitive(it.x))
            add(JsonPrimitive(it.y))
            add(JsonPrimitive(it.z))
        }
    } ?: JsonNull

    private fun nullableNumber(value: Double?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

    private fun nullableNumberMap(values: Map<String, Double?>): JsonObject = buildJsonObject {
        values.forEach { (name, value) -> put(name, nullableNumber(value)) }
    }

    private fun landmarkNames(values: Set<LandmarkId>): JsonArray = buildJsonArray {
        values.sortedBy { it.index }.forEach { add(JsonPrimitive(it.name)) }
    }

    private fun JsonObjectBuilder.objectValue(key: String, block: JsonObjectBuilder.() -> Unit) {
        put(key, buildJsonObject(block))
    }

    private fun JsonObjectBuilder.event(name: String, fromMs: Long, toMs: Long, landmarks: Set<LandmarkId>) {
        objectValue(name) {
            put("from_ms", fromMs)
            put("to_ms", toMs)
            put("landmarks", landmarkNames(landmarks))
        }
    }
}
