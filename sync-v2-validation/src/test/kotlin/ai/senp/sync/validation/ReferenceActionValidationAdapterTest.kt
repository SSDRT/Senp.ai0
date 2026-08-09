package ai.senp.sync.validation

import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.ImageLandmark
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmark
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoPoseDiagnostics
import ai.senp.core.contracts.VideoPoseExtraction
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.WorldLandmark
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ReferenceActionValidationAdapterTest {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "type"
    }

    @Test
    fun `normalized request adapter recognizes its own saved reference`() {
        val root = Files.createTempDirectory("reference-action-adapter-self")
        val result = runCase(root, "self", poseExtraction(VideoRole.REFERENCE), poseExtraction(VideoRole.SOURCE))

        assertEquals(REFERENCE_ACTION_RESULT_SCHEMA, result.getValue("schema").jsonPrimitive.content)
        assertEquals("self", result.getValue("case_id").jsonPrimitive.content)
        assertEquals("ACTION", result.getValue("classification").jsonPrimitive.content)
        assertEquals("USABLE", result.getValue("compile").jsonObject.getValue("status").jsonPrimitive.content)
        val profile = result.getValue("profile").jsonObject
        assertEquals("true", profile.getValue("usable").jsonPrimitive.content)
        assertTrue(profile.getValue("state_ids").jsonArray.size >= 3)
        assertTrue(profile.getValue("legal_transitions").jsonArray.isNotEmpty())
        val observations = result.getValue("observations").jsonArray
        assertEquals(60, observations.size)
        assertTrue(
            observations.any { observation ->
                observation.jsonObject["state_id"]?.jsonPrimitive?.contentOrNull != null
            },
            "recognized observations must preserve emitted state IDs",
        )
        assertTrue(
            result.getValue("compile").jsonObject["validation"] !is kotlinx.serialization.json.JsonNull,
            "usable compilation must preserve self-validation metadata",
        )
        assertEquals(0, result.getValue("deviations").jsonArray.size)
        assertTrue(Files.isRegularFile(root.resolve("result.json")))
    }

    @Test
    fun `absolute timestamp offset preserves action path and confidence`() {
        val baselineRoot = Files.createTempDirectory("reference-action-adapter-offset-base")
        val shiftedRoot = Files.createTempDirectory("reference-action-adapter-offset-shift")
        val reference = poseExtraction(VideoRole.REFERENCE)
        val baseline = runCase(baselineRoot, "baseline", reference, poseExtraction(VideoRole.SOURCE))
        val shifted = runCase(
            shiftedRoot,
            "shifted",
            reference,
            poseExtraction(VideoRole.SOURCE, startOffsetMs = 30_000L),
        )

        assertEquals("ACTION", shifted.getValue("classification").jsonPrimitive.content)
        val baselinePath = statePath(baseline)
        val shiftedPath = statePath(shifted)
        assertEquals(baselinePath, shiftedPath)
        val baselineConfidence = baseline.getValue("confidence").jsonPrimitive.content.toDouble()
        val shiftedConfidence = shifted.getValue("confidence").jsonPrimitive.content.toDouble()
        assertTrue(kotlin.math.abs(baselineConfidence - shiftedConfidence) <= 0.05)
    }

    @Test
    fun `weak reference produces schema valid suppression instead of a manufactured match`() {
        val root = Files.createTempDirectory("reference-action-adapter-weak")
        val full = poseExtraction(VideoRole.REFERENCE)
        val weakFrames = full.poses.frames.take(10)
        val weak = full.copy(
            duration = DurationMs(1_000L),
            poses = PoseSequence(VideoRole.REFERENCE, weakFrames),
            diagnostics = full.diagnostics.copy(
                decodedFrameCount = weakFrames.size,
                sampledFrameCount = weakFrames.size,
                detectedFrameCount = weakFrames.size,
            ),
        )
        val result = runCase(root, "weak", weak, poseExtraction(VideoRole.SOURCE))

        assertEquals("SUPPRESSED", result.getValue("classification").jsonPrimitive.content)
        val profile = result.getValue("profile").jsonObject
        assertEquals("false", profile.getValue("usable").jsonPrimitive.content)
        assertTrue(profile.getValue("state_ids").jsonArray.isEmpty())
        assertEquals("FAILED", result.getValue("compile").jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `reverse traversal is discriminated by classification or explicit deviation`() {
        val root = Files.createTempDirectory("reference-action-adapter-reverse")
        val result = runCase(
            root,
            "reverse",
            poseExtraction(VideoRole.REFERENCE),
            poseExtraction(VideoRole.SOURCE, reverseMotion = true),
        )

        val classification = result.getValue("classification").jsonPrimitive.content
        val deviations = result.getValue("deviations").jsonArray
        assertTrue(classification != "ACTION" || deviations.isNotEmpty())
    }

    @Test
    fun `malformed saved pose input is emitted as schema-valid suppression`() {
        val root = Files.createTempDirectory("reference-action-adapter-malformed")
        val referencePath = root.resolve("reference.json")
        val candidatePath = root.resolve("candidate.json")
        val resultPath = root.resolve("result.json")
        val requestPath = root.resolve("request.json")
        Files.writeString(referencePath, json.encodeToString(poseExtraction(VideoRole.REFERENCE)))
        Files.writeString(candidatePath, "{\"not_pose_data\":true}")
        val request = buildJsonObject {
            put("schema_version", 1)
            put("protocol", REFERENCE_ACTION_ADAPTER_PROTOCOL)
            put("mode", "reference_action_pose_compare")
            put("case_id", "malformed")
            put("reference_pose_extraction_json", referencePath.toString())
            put("candidate_pose_extraction_json", candidatePath.toString())
            put("result_output", resultPath.toString())
            put("required_result_schema", REFERENCE_ACTION_RESULT_SCHEMA)
        }
        Files.writeString(requestPath, json.encodeToString(JsonObject.serializer(), request))

        val result = runReferenceActionValidationAdapter(requestPath)

        assertEquals("SUPPRESSED", result.getValue("classification").jsonPrimitive.content)
        assertEquals("false", result.getValue("profile").jsonObject.getValue("usable").jsonPrimitive.content)
        assertEquals("INVALID_INPUT", result.getValue("compile").jsonObject.getValue("reason").jsonPrimitive.content)
        assertTrue(result.getValue("observations").jsonArray.isEmpty())
        assertTrue(Files.isRegularFile(resultPath))
    }

    @Test
    fun `missing saved pose file is emitted as schema-valid suppression`() {
        val root = Files.createTempDirectory("reference-action-adapter-missing")
        val referencePath = root.resolve("reference.json")
        val missingCandidatePath = root.resolve("missing-candidate.json")
        val resultPath = root.resolve("result.json")
        val requestPath = root.resolve("request.json")
        Files.writeString(referencePath, json.encodeToString(poseExtraction(VideoRole.REFERENCE)))
        val request = buildJsonObject {
            put("schema_version", 1)
            put("protocol", REFERENCE_ACTION_ADAPTER_PROTOCOL)
            put("mode", "reference_action_pose_compare")
            put("case_id", "missing")
            put("reference_pose_extraction_json", referencePath.toString())
            put("candidate_pose_extraction_json", missingCandidatePath.toString())
            put("result_output", resultPath.toString())
            put("required_result_schema", REFERENCE_ACTION_RESULT_SCHEMA)
        }
        Files.writeString(requestPath, json.encodeToString(JsonObject.serializer(), request))

        val result = runReferenceActionValidationAdapter(requestPath)

        assertEquals("SUPPRESSED", result.getValue("classification").jsonPrimitive.content)
        assertEquals("INVALID_INPUT", result.getValue("compile").jsonObject.getValue("reason").jsonPrimitive.content)
        assertTrue(result.getValue("observations").jsonArray.isEmpty())
        assertTrue(Files.isRegularFile(resultPath))
    }

    @Test
    fun `blind frames are suppressed instead of treated as no action evidence`() {
        val root = Files.createTempDirectory("reference-action-adapter-blind")
        val reference = poseExtraction(VideoRole.REFERENCE)
        val candidate = poseExtraction(VideoRole.SOURCE)
        val blindLandmarks = setOf(
            PoseLandmarkId.LEFT_SHOULDER,
            PoseLandmarkId.RIGHT_SHOULDER,
            PoseLandmarkId.LEFT_ELBOW,
            PoseLandmarkId.RIGHT_ELBOW,
            PoseLandmarkId.LEFT_WRIST,
            PoseLandmarkId.RIGHT_WRIST,
            PoseLandmarkId.LEFT_HIP,
            PoseLandmarkId.RIGHT_HIP,
            PoseLandmarkId.LEFT_KNEE,
            PoseLandmarkId.RIGHT_KNEE,
            PoseLandmarkId.LEFT_ANKLE,
            PoseLandmarkId.RIGHT_ANKLE,
        )
        val blindStartMs = 2_400L
        val blindEndMs = 3_600L
        val blindCandidate = candidate.copy(
            poses = candidate.poses.copy(
                frames = candidate.poses.frames.map { frame ->
                    if (frame.timestamp.value !in blindStartMs..blindEndMs) {
                        frame
                    } else {
                        frame.copy(
                            landmarks = frame.landmarks.map { landmark ->
                                if (landmark.id in blindLandmarks) {
                                    landmark.copy(visibility = 0.02, presence = 0.02)
                                } else {
                                    landmark
                                }
                            },
                        )
                    }
                },
            ),
        )

        val result = runCase(root, "blind", reference, blindCandidate)
        val blindObservations = result.getValue("observations").jsonArray
            .map { it.jsonObject }
            .filter { observation ->
                observation.getValue("timestamp_ms").jsonPrimitive.content.toLong() in blindStartMs..blindEndMs
            }
        val suppressed = blindObservations.count { observation ->
            observation.getValue("classification").jsonPrimitive.content in setOf("SUPPRESSED", "UNCERTAIN")
        }

        assertTrue(blindObservations.isNotEmpty())
        assertTrue(suppressed.toDouble() / blindObservations.size.toDouble() >= 0.70)
        assertTrue(blindObservations.none { observation ->
            observation.getValue("classification").jsonPrimitive.content == "NO_ACTION"
        })
    }

    private fun runCase(
        root: Path,
        caseId: String,
        reference: VideoPoseExtraction,
        candidate: VideoPoseExtraction,
    ): JsonObject {
        val referencePath = root.resolve("reference.json")
        val candidatePath = root.resolve("candidate.json")
        val resultPath = root.resolve("result.json")
        val requestPath = root.resolve("request.json")
        Files.writeString(referencePath, json.encodeToString(reference))
        Files.writeString(candidatePath, json.encodeToString(candidate))
        val request = buildJsonObject {
            put("schema_version", 1)
            put("protocol", REFERENCE_ACTION_ADAPTER_PROTOCOL)
            put("mode", "reference_action_pose_compare")
            put("case_id", caseId)
            put("reference_pose_extraction_json", referencePath.toString())
            put("candidate_pose_extraction_json", candidatePath.toString())
            put("result_output", resultPath.toString())
            put("required_result_schema", REFERENCE_ACTION_RESULT_SCHEMA)
        }
        Files.writeString(requestPath, json.encodeToString(JsonObject.serializer(), request))
        return runReferenceActionValidationAdapter(requestPath)
    }

    private fun statePath(result: JsonObject): List<String> {
        val path = mutableListOf<String>()
        result.getValue("observations").jsonArray.forEach { element ->
            val state = element.jsonObject["state_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            if (path.lastOrNull() != state) path += state
        }
        return path
    }

    private fun poseExtraction(
        role: VideoRole,
        reverseMotion: Boolean = false,
        startOffsetMs: Long = 0L,
    ): VideoPoseExtraction {
        val frameCount = 60
        val cycleFrames = 12.0
        val frames = (0 until frameCount).map { index ->
            val phaseIndex = if (reverseMotion) frameCount - 1 - index else index
            val phase = phaseIndex / cycleFrames
            val primary = -cos(2.0 * PI * phase)
            val secondary = sin(2.0 * PI * phase)
            val landmarks = PoseLandmarkId.entries.map { id ->
                val base = when (id) {
                    PoseLandmarkId.LEFT_HIP -> Triple(-0.20, -0.05, 0.0)
                    PoseLandmarkId.RIGHT_HIP -> Triple(0.20, -0.05, 0.0)
                    PoseLandmarkId.LEFT_SHOULDER -> Triple(-0.42, 0.95, 0.0)
                    PoseLandmarkId.RIGHT_SHOULDER -> Triple(0.42, 0.95, 0.0)
                    PoseLandmarkId.LEFT_ELBOW -> Triple(-0.56, 0.69 + 0.18 * secondary, 0.10 * primary)
                    PoseLandmarkId.RIGHT_ELBOW -> Triple(0.56, 0.69 + 0.16 * secondary, 0.11 * primary)
                    PoseLandmarkId.LEFT_WRIST -> Triple(-0.66, 0.42 + 0.34 * primary, 0.16 * primary)
                    PoseLandmarkId.RIGHT_WRIST -> Triple(0.66, 0.45 + 0.30 * primary, 0.18 * primary)
                    PoseLandmarkId.LEFT_KNEE -> Triple(-0.20, -0.55 + 0.10 * secondary, 0.05 * primary)
                    PoseLandmarkId.RIGHT_KNEE -> Triple(0.20, -0.55 + 0.08 * secondary, 0.06 * primary)
                    PoseLandmarkId.LEFT_ANKLE -> Triple(-0.20, -1.0, 0.0)
                    PoseLandmarkId.RIGHT_ANKLE -> Triple(0.20, -1.0, 0.0)
                    PoseLandmarkId.LEFT_FOOT_INDEX -> Triple(-0.20, -1.16, 0.18)
                    PoseLandmarkId.RIGHT_FOOT_INDEX -> Triple(0.20, -1.16, 0.18)
                    else -> Triple(0.0, 0.2, 0.0)
                }
                PoseLandmark(
                    id = id,
                    image = ImageLandmark(0.5 + base.first * 0.2, 0.5 - base.second * 0.2, base.third),
                    world = WorldLandmark(base.first, base.second, base.third),
                    visibility = 0.98,
                    presence = 0.98,
                )
            }
            PoseFrame(
                timestamp = TimestampMs(startOffsetMs + index * 100L),
                diagnosticFrameIndex = index.toLong(),
                landmarks = landmarks,
                validity = FrameValidity.Valid,
            )
        }
        return VideoPoseExtraction(
            role = role,
            duration = DurationMs(startOffsetMs + frameCount * 100L),
            poses = PoseSequence(role, frames),
            diagnostics = VideoPoseDiagnostics(
                decodedFrameCount = frameCount,
                sampledFrameCount = frameCount,
                detectedFrameCount = frameCount,
                noPersonFrameCount = 0,
                unusableTrackingFrameCount = 0,
                decodeNanos = 1_000_000,
                inferenceNanos = 2_000_000,
                maxInFlightFrames = 2,
                peakInFlightFrames = 1,
            ),
        )
    }
}
