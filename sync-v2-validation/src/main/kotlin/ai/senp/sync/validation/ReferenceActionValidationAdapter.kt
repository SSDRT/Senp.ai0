package ai.senp.sync.validation

import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.FrameValidityReason
import ai.senp.core.contracts.FrameValidityStatus
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
import ai.senp.motion.ActionProfile
import ai.senp.motion.ActionRecognitionResult
import ai.senp.motion.ActionStateEstimate
import ai.senp.motion.ActionStateRecognizer
import ai.senp.motion.ActionStateRecognizerConfig
import ai.senp.motion.ActionTrackingStatus
import ai.senp.motion.ReferenceActionCompilation
import ai.senp.motion.ReferenceActionCompiler
import ai.senp.motion.ReferenceDeviationEvaluator
import ai.senp.motion.ReferenceDeviationMeasurement
import ai.senp.motion.SpatialObservationFrame
import ai.senp.motion.SpatialSequenceAnalysis
import ai.senp.motion.SpatialSynchronizationEngine
import ai.senp.sync.v2.PoseObservationAdapter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val REFERENCE_ACTION_ADAPTER_PROTOCOL = "senp-reference-action-validation-adapter/1"
internal const val REFERENCE_ACTION_RESULT_SCHEMA = "reference-action-normalized-result/1"

private const val MINIMUM_PROFILE_RECONSTRUCTION = 0.72
private const val MINIMUM_PROFILE_TRANSITION_COVERAGE = 0.80
private const val MINIMUM_PROFILE_RECOGNITION_CONFIDENCE = 0.55
private const val MINIMUM_CANDIDATE_ANALYZABLE_FRACTION = 0.35
private const val MINIMUM_ACTION_STATE_CONFIDENCE = 0.42
private const val MINIMUM_ACTION_FEATURE_COVERAGE = 0.50
private const val MINIMUM_ACTION_FRAME_CONFIDENCE = 0.40
private const val MINIMUM_ACTION_STATE_COVERAGE = 0.80
private const val MINIMUM_LEGAL_TRANSITION_FRACTION = 0.95
private const val MINIMUM_ACTION_TRACKED_FRACTION = 0.60
private const val MINIMUM_REPORTED_DEVIATION_CONFIDENCE = 0.45
private const val DEVIATION_SPAN_MAX_GAP_MS = 500L

private val referenceActionAdapterJson = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}
private val referenceActionRecognizerConfig = ActionStateRecognizerConfig()

private data class AdapterRequest(
    val caseId: String,
    val referencePosePath: Path,
    val candidatePosePath: Path,
    val resultOutput: Path,
)

private data class RecognitionMetrics(
    val stateCoverage: Double,
    val legalTransitionFraction: Double,
    val distinctStatePath: List<String>,
    val meanActionConfidence: Double,
    val maxEstimateConfidence: Double,
    val actionWindowTrackedFraction: Double,
)

private data class DeviationSpan(
    val stateId: String,
    val feature: String,
    var startMs: Long,
    var endMs: Long,
    var maxConfidence: Double,
    var maxNormalizedDeviation: Double,
    var peakSignedDelta: Double,
    val referenceLower: Double,
    val referenceUpper: Double,
    val referenceMedian: Double,
)

internal fun runReferenceActionValidationAdapter(requestPath: Path): JsonObject {
    val totalStart = System.nanoTime()
    val request = parseAdapterRequest(requestPath)
    val result = try {
        evaluateReferenceActionRequest(request, totalStart)
    } catch (failure: Exception) {
        buildInvalidInputSuppressedResult(request, failure, totalStart)
    }
    request.resultOutput.parent?.let(Files::createDirectories)
    Files.writeString(request.resultOutput, referenceActionAdapterJson.encodeToString(JsonObject.serializer(), result) + "\n")
    return result
}

private fun evaluateReferenceActionRequest(
    request: AdapterRequest,
    totalStart: Long,
): JsonObject {
    val decodeStart = System.nanoTime()
    val referenceExtraction = decodePoseExtraction(request.referencePosePath, VideoRole.REFERENCE)
    val candidateExtraction = decodePoseExtraction(request.candidatePosePath, VideoRole.SOURCE)
    val decodeNanos = System.nanoTime() - decodeStart

    val adapter = PoseObservationAdapter()
    val adaptStart = System.nanoTime()
    val referenceSequence = adapter.adapt(referenceExtraction, analysisFramesPerSecond(referenceExtraction))
    val candidateSequence = adapter.adapt(candidateExtraction, analysisFramesPerSecond(candidateExtraction))
    val adaptNanos = System.nanoTime() - adaptStart

    val spatialEngine = SpatialSynchronizationEngine()
    val spatialStart = System.nanoTime()
    val referenceSpatial = spatialEngine.analyzeSequence(referenceSequence)
    val candidateSpatial = spatialEngine.analyzeSequence(candidateSequence)
    val spatialNanos = System.nanoTime() - spatialStart

    val compileStart = System.nanoTime()
    val compilation = ReferenceActionCompiler().compile(referenceSpatial)
    val compileNanos = System.nanoTime() - compileStart

    val result = when (compilation) {
        is ReferenceActionCompilation.Failure -> buildSuppressedResult(
            request = request,
            candidateSpatial = candidateSpatial,
            compileStatus = "FAILED",
            compileReason = compilation.reason.name,
            compileMessage = compilation.message,
            profile = null,
            decodeNanos = decodeNanos,
            adaptNanos = adaptNanos,
            spatialNanos = spatialNanos,
            compileNanos = compileNanos,
            recognitionNanos = 0L,
            totalStartNanos = totalStart,
        )

        is ReferenceActionCompilation.Success -> {
            val profile = compilation.profile
            val usabilityFailure = profileUsabilityFailure(profile)
            if (usabilityFailure != null) {
                buildSuppressedResult(
                    request = request,
                    candidateSpatial = candidateSpatial,
                    compileStatus = "REJECTED_SELF_VALIDATION",
                    compileReason = "SELF_VALIDATION_BELOW_GATE",
                    compileMessage = usabilityFailure,
                    profile = profile,
                    decodeNanos = decodeNanos,
                    adaptNanos = adaptNanos,
                    spatialNanos = spatialNanos,
                    compileNanos = compileNanos,
                    recognitionNanos = 0L,
                    totalStartNanos = totalStart,
                )
            } else if (candidateSpatial.analyzableFraction < MINIMUM_CANDIDATE_ANALYZABLE_FRACTION) {
                buildSuppressedResult(
                    request = request,
                    candidateSpatial = candidateSpatial,
                    compileStatus = "USABLE",
                    compileReason = "CANDIDATE_EVIDENCE_INSUFFICIENT",
                    compileMessage = "candidate analyzable fraction ${candidateSpatial.analyzableFraction} is below $MINIMUM_CANDIDATE_ANALYZABLE_FRACTION",
                    profile = profile,
                    decodeNanos = decodeNanos,
                    adaptNanos = adaptNanos,
                    spatialNanos = spatialNanos,
                    compileNanos = compileNanos,
                    recognitionNanos = 0L,
                    totalStartNanos = totalStart,
                )
            } else {
                val recognitionStart = System.nanoTime()
                val recognition = ActionStateRecognizer(profile, referenceActionRecognizerConfig).recognize(candidateSpatial)
                val measurements = evaluateDeviations(profile, candidateSpatial, recognition)
                val recognitionNanos = System.nanoTime() - recognitionStart
                buildRecognizedResult(
                    request = request,
                    profile = profile,
                    candidateSpatial = candidateSpatial,
                    recognition = recognition,
                    measurements = measurements,
                    decodeNanos = decodeNanos,
                    adaptNanos = adaptNanos,
                    spatialNanos = spatialNanos,
                    compileNanos = compileNanos,
                    recognitionNanos = recognitionNanos,
                    totalStartNanos = totalStart,
                )
            }
        }
    }

    return result
}

private fun buildInvalidInputSuppressedResult(
    request: AdapterRequest,
    failure: Exception,
    totalStartNanos: Long,
): JsonObject = buildJsonObject {
    put("schema", REFERENCE_ACTION_RESULT_SCHEMA)
    put("case_id", request.caseId)
    put("classification", "SUPPRESSED")
    put("confidence", 0.0)
    put(
        "compile",
        compileJson(
            status = "FAILED",
            reason = "INVALID_INPUT",
            message = failure.message?.take(500) ?: failure::class.simpleName.orEmpty().ifBlank { "reference-action input failed" },
            profile = null,
        ),
    )
    put("profile", unusableProfileJson())
    put("observations", buildJsonArray { })
    put("repetition_count", JsonNull)
    put("repetition_delta_from_reference", JsonNull)
    put("deviations", buildJsonArray { })
    put("cues", buildJsonArray { })
    put("capabilities", capabilitiesJson())
    put("recognition", buildJsonObject {
        put("final_status", "SUPPRESSED")
        put("tracked_fraction", 0.0)
        put("state_coverage", 0.0)
        put("legal_transition_fraction", 0.0)
        put("mean_action_confidence", 0.0)
        put("max_estimate_confidence", 0.0)
        put("distinct_state_path", buildJsonArray { })
        put("completed_repetitions", JsonNull)
        put("repetition_delta_from_reference", JsonNull)
        put("reason", "INVALID_INPUT")
    })
    put("candidate", buildJsonObject {
        put("spatial_analyzable_fraction", 0.0)
        put("frame_count", 0)
    })
    put("runtime", buildJsonObject {
        put("decode_ms", JsonNull)
        put("adapt_ms", JsonNull)
        put("spatial_ms", JsonNull)
        put("compile_ms", JsonNull)
        put("recognition_and_deviation_ms", JsonNull)
        put("total_ms", nanosToMs(System.nanoTime() - totalStartNanos))
    })
}

private fun parseAdapterRequest(path: Path): AdapterRequest {
    require(Files.isRegularFile(path)) { "adapter request does not exist: $path" }
    val root = referenceActionAdapterJson.parseToJsonElement(Files.readString(path)).jsonObject
    val schemaVersion = root["schema_version"]?.jsonPrimitive?.intOrNull
    require(schemaVersion == 1) { "adapter request schema_version must be 1" }
    require(root.requiredString("protocol") == REFERENCE_ACTION_ADAPTER_PROTOCOL) {
        "adapter request protocol must be $REFERENCE_ACTION_ADAPTER_PROTOCOL"
    }
    require(root.requiredString("mode") == "reference_action_pose_compare") {
        "adapter request mode must be reference_action_pose_compare"
    }
    require(root.requiredString("required_result_schema") == REFERENCE_ACTION_RESULT_SCHEMA) {
        "adapter request required_result_schema must be $REFERENCE_ACTION_RESULT_SCHEMA"
    }
    val caseId = root.requiredString("case_id")
    require(caseId.isNotBlank()) { "adapter case_id must be non-blank" }
    val reference = Path.of(root.requiredString("reference_pose_extraction_json")).toAbsolutePath().normalize()
    val candidate = Path.of(root.requiredString("candidate_pose_extraction_json")).toAbsolutePath().normalize()
    val output = Path.of(root.requiredString("result_output")).toAbsolutePath().normalize()
    return AdapterRequest(caseId, reference, candidate, output)
}

private fun decodePoseExtraction(path: Path, role: VideoRole): VideoPoseExtraction {
    val text = Files.readString(path)
    val decoded = try {
        referenceActionAdapterJson.decodeFromString<VideoPoseExtraction>(text)
    } catch (_: SerializationException) {
        decodeHarnessPoseExtraction(referenceActionAdapterJson.parseToJsonElement(text).jsonObject)
    } catch (_: IllegalArgumentException) {
        decodeHarnessPoseExtraction(referenceActionAdapterJson.parseToJsonElement(text).jsonObject)
    }
    val normalizedDuration = max(
        decoded.duration.value,
        decoded.poses.frames.lastOrNull()?.timestamp?.value?.plus(1L) ?: 1L,
    )
    return decoded.copy(
        role = role,
        duration = DurationMs(normalizedDuration),
        poses = decoded.poses.copy(role = role),
    )
}

private fun decodeHarnessPoseExtraction(root: JsonObject): VideoPoseExtraction {
    val role = root["role"]?.jsonPrimitive?.content?.let(VideoRole::valueOf) ?: VideoRole.REFERENCE
    val posesObject = root["poses"]?.jsonObject ?: error("pose extraction requires poses object")
    val frameObjects = posesObject["frames"]?.jsonArray ?: error("pose extraction requires poses.frames")
    require(frameObjects.isNotEmpty()) { "pose extraction requires at least one frame" }
    val frames = frameObjects.mapIndexed { index, element -> decodeHarnessFrame(element.jsonObject, index) }
    require(frames.zipWithNext().all { (left, right) -> left.timestamp < right.timestamp }) {
        "pose extraction timestamps must be strictly increasing"
    }
    val requestedDuration = root["duration"]?.jsonPrimitive?.longOrNull ?: 0L
    val duration = max(requestedDuration, frames.last().timestamp.value + 1L)
    val diagnosticsObject = root["diagnostics"] as? JsonObject
    val sampledCount = frames.size
    val noPerson = (diagnosticsObject?.get("noPersonFrameCount")?.jsonPrimitive?.intOrNull ?: 0)
        .coerceIn(0, sampledCount)
    val unusable = (diagnosticsObject?.get("unusableTrackingFrameCount")?.jsonPrimitive?.intOrNull ?: 0)
        .coerceIn(0, sampledCount - noPerson)
    val detected = sampledCount - noPerson - unusable
    val maxInFlight = (diagnosticsObject?.get("maxInFlightFrames")?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
    val peakInFlight = (diagnosticsObject?.get("peakInFlightFrames")?.jsonPrimitive?.intOrNull ?: 1)
        .coerceIn(0, maxInFlight)
    return VideoPoseExtraction(
        role = role,
        duration = DurationMs(duration),
        poses = PoseSequence(role, frames),
        diagnostics = VideoPoseDiagnostics(
            decodedFrameCount = max(
                sampledCount,
                diagnosticsObject?.get("decodedFrameCount")?.jsonPrimitive?.intOrNull ?: sampledCount,
            ),
            sampledFrameCount = sampledCount,
            detectedFrameCount = detected,
            noPersonFrameCount = noPerson,
            unusableTrackingFrameCount = unusable,
            decodeNanos = (diagnosticsObject?.get("decodeNanos")?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(0L),
            inferenceNanos = (diagnosticsObject?.get("inferenceNanos")?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(0L),
            maxInFlightFrames = maxInFlight,
            peakInFlightFrames = peakInFlight,
        ),
    )
}

private fun decodeHarnessFrame(root: JsonObject, fallbackIndex: Int): PoseFrame {
    val timestamp = root["timestamp"]?.jsonPrimitive?.longOrNull
        ?: error("pose frame $fallbackIndex requires integer timestamp")
    val diagnosticIndex = root["diagnosticFrameIndex"]?.jsonPrimitive?.longOrNull ?: fallbackIndex.toLong()
    val landmarks = root["landmarks"]?.jsonArray ?: error("pose frame $fallbackIndex requires landmarks")
    val byId = landmarks.associate { element ->
        val objectValue = element.jsonObject
        val id = PoseLandmarkId.valueOf(objectValue.requiredString("id"))
        id to objectValue
    }
    require(byId.size == PoseLandmarkId.COUNT) { "pose frame $fallbackIndex must contain all 33 landmark IDs" }
    val decodedLandmarks = PoseLandmarkId.entries.map { id -> decodeHarnessLandmark(id, byId.getValue(id)) }
    return PoseFrame(
        timestamp = TimestampMs(timestamp),
        diagnosticFrameIndex = diagnosticIndex,
        landmarks = decodedLandmarks,
        validity = decodeHarnessValidity(root["validity"]),
    )
}

private fun decodeHarnessLandmark(id: PoseLandmarkId, root: JsonObject): PoseLandmark {
    val image = root["image"]?.jsonObject ?: error("landmark $id requires image coordinates")
    val worldElement = root["world"]
    val world = if (worldElement == null || worldElement is JsonNull) {
        null
    } else {
        val objectValue = worldElement.jsonObject
        WorldLandmark(
            xMeters = objectValue.requiredDouble("xMeters"),
            yMeters = objectValue.requiredDouble("yMeters"),
            zMeters = objectValue.requiredDouble("zMeters"),
        )
    }
    return PoseLandmark(
        id = id,
        image = ImageLandmark(
            x = image.requiredDouble("x"),
            y = image.requiredDouble("y"),
            z = image.requiredDouble("z"),
        ),
        world = world,
        visibility = root["visibility"]?.jsonPrimitive?.doubleOrNull,
        presence = root["presence"]?.jsonPrimitive?.doubleOrNull,
    )
}

private fun decodeHarnessValidity(element: JsonElement?): FrameValidity {
    if (element == null || element is JsonNull) return FrameValidity.Valid
    val root = element.jsonObject
    val status = FrameValidityStatus.valueOf(root.requiredString("status"))
    val confidence = root["confidence"]?.jsonPrimitive?.doubleOrNull
        ?: if (status == FrameValidityStatus.VALID) 1.0 else 0.0
    val reasons = root["reasons"]?.jsonArray
        ?.map { FrameValidityReason.valueOf(it.jsonPrimitive.content) }
        ?.toSet()
        .orEmpty()
    return FrameValidity(status, confidence, reasons)
}

private fun analysisFramesPerSecond(extraction: VideoPoseExtraction): Double {
    val steps = extraction.poses.frames.zipWithNext { left, right -> right.timestamp.value - left.timestamp.value }
        .filter { it > 0L }
        .sorted()
    if (steps.isEmpty()) return 15.0
    val medianStep = if (steps.size % 2 == 1) {
        steps[steps.size / 2].toDouble()
    } else {
        (steps[steps.size / 2 - 1] + steps[steps.size / 2]).toDouble() / 2.0
    }
    return (1_000.0 / medianStep).coerceIn(1.0, 120.0)
}

private fun profileUsabilityFailure(profile: ActionProfile): String? {
    val validation = profile.validation
    return when {
        validation.reconstructionAccuracy < MINIMUM_PROFILE_RECONSTRUCTION ->
            "reference reconstruction ${validation.reconstructionAccuracy} is below $MINIMUM_PROFILE_RECONSTRUCTION"
        validation.transitionCoverage < MINIMUM_PROFILE_TRANSITION_COVERAGE ->
            "reference transition coverage ${validation.transitionCoverage} is below $MINIMUM_PROFILE_TRANSITION_COVERAGE"
        validation.meanRecognitionConfidence < MINIMUM_PROFILE_RECOGNITION_CONFIDENCE ->
            "reference recognition confidence ${validation.meanRecognitionConfidence} is below $MINIMUM_PROFILE_RECOGNITION_CONFIDENCE"
        else -> null
    }
}

private fun evaluateDeviations(
    profile: ActionProfile,
    candidate: SpatialSequenceAnalysis,
    recognition: ActionRecognitionResult,
): List<ReferenceDeviationMeasurement> {
    require(candidate.frames.size == recognition.estimates.size) {
        "recognizer must emit exactly one estimate per candidate frame"
    }
    val evaluator = ReferenceDeviationEvaluator(profile)
    return candidate.frames.zip(recognition.estimates).flatMap { (frame, estimate) -> evaluator.evaluate(frame, estimate) }
}

private fun buildRecognizedResult(
    request: AdapterRequest,
    profile: ActionProfile,
    candidateSpatial: SpatialSequenceAnalysis,
    recognition: ActionRecognitionResult,
    measurements: List<ReferenceDeviationMeasurement>,
    decodeNanos: Long,
    adaptNanos: Long,
    spatialNanos: Long,
    compileNanos: Long,
    recognitionNanos: Long,
    totalStartNanos: Long,
): JsonObject {
    val metrics = recognitionMetrics(profile, recognition)
    val observationClasses = candidateSpatial.frames.zip(recognition.estimates).map { (frame, estimate) ->
        observationClassification(frame, estimate)
    }
    val classification = when {
        recognition.finalStatus == ActionTrackingStatus.COMPLETED &&
            metrics.stateCoverage >= MINIMUM_ACTION_STATE_COVERAGE &&
            metrics.legalTransitionFraction >= MINIMUM_LEGAL_TRANSITION_FRACTION &&
            metrics.actionWindowTrackedFraction >= MINIMUM_ACTION_TRACKED_FRACTION -> "ACTION"
        recognition.finalStatus == ActionTrackingStatus.NO_ACTION -> "NO_ACTION"
        recognition.trackedFraction < 0.10 && metrics.maxEstimateConfidence < 0.58 -> "NO_ACTION"
        else -> "UNCERTAIN"
    }
    val confidence = classificationConfidence(classification, profile, recognition, metrics)
    val spans = aggregateDeviationSpans(measurements)
        .filter { it.maxConfidence >= MINIMUM_REPORTED_DEVIATION_CONFIDENCE }
    return buildJsonObject {
        put("schema", REFERENCE_ACTION_RESULT_SCHEMA)
        put("case_id", request.caseId)
        put("classification", classification)
        put("confidence", confidence)
        put("compile", compileJson("USABLE", null, null, profile))
        put("profile", profileJson(profile, usable = true))
        put("observations", buildJsonArray {
            candidateSpatial.frames.zip(recognition.estimates).zip(observationClasses).forEach { pair ->
                val frame = pair.first.first
                val estimate = pair.first.second
                add(observationJson(frame, estimate, pair.second))
            }
        })
        put("repetition_count", recognition.completedRepetitions)
        put("repetition_delta_from_reference", recognition.repetitionDeltaFromReference)
        put("deviations", deviationSpansJson(spans))
        put("cues", buildJsonArray { })
        put("capabilities", capabilitiesJson())
        put("recognition", buildJsonObject {
            put("final_status", recognition.finalStatus.name)
            put("tracked_fraction", recognition.trackedFraction)
            put("action_window_tracked_fraction", metrics.actionWindowTrackedFraction)
            put("state_coverage", metrics.stateCoverage)
            put("legal_transition_fraction", metrics.legalTransitionFraction)
            put("mean_action_confidence", metrics.meanActionConfidence)
            put("max_estimate_confidence", metrics.maxEstimateConfidence)
            put("distinct_state_path", stringArray(metrics.distinctStatePath))
            put("completed_repetitions", recognition.completedRepetitions)
            put("repetition_delta_from_reference", recognition.repetitionDeltaFromReference)
        })
        put("candidate", buildJsonObject {
            put("spatial_analyzable_fraction", candidateSpatial.analyzableFraction)
            put("frame_count", candidateSpatial.frames.size)
        })
        put("runtime", runtimeJson(decodeNanos, adaptNanos, spatialNanos, compileNanos, recognitionNanos, totalStartNanos))
    }
}

private fun buildSuppressedResult(
    request: AdapterRequest,
    candidateSpatial: SpatialSequenceAnalysis,
    compileStatus: String,
    compileReason: String,
    compileMessage: String,
    profile: ActionProfile?,
    decodeNanos: Long,
    adaptNanos: Long,
    spatialNanos: Long,
    compileNanos: Long,
    recognitionNanos: Long,
    totalStartNanos: Long,
): JsonObject = buildJsonObject {
    put("schema", REFERENCE_ACTION_RESULT_SCHEMA)
    put("case_id", request.caseId)
    put("classification", "SUPPRESSED")
    put("confidence", (1.0 - candidateSpatial.analyzableFraction).coerceIn(0.50, 1.0))
    put("compile", compileJson(compileStatus, compileReason, compileMessage, profile))
    put("profile", if (profile == null) unusableProfileJson() else profileJson(profile, usable = false))
    put("observations", buildJsonArray {
        candidateSpatial.frames.forEach { frame ->
            add(buildJsonObject {
                put("timestamp_ms", frame.timestamp.value)
                put("state_id", JsonNull)
                put("tracking_status", "LOST")
                put("classification", "SUPPRESSED")
                put("confidence", 0.0)
                put("feature_coverage", 0.0)
                put("frame_confidence", frame.intrinsicDescriptor.confidence)
                put("mirror_mode", "UNKNOWN")
                put("completed_repetitions", 0)
            })
        }
    })
    put("repetition_count", JsonNull)
    put("repetition_delta_from_reference", JsonNull)
    put("deviations", buildJsonArray { })
    put("cues", buildJsonArray { })
    put("capabilities", capabilitiesJson())
    put("recognition", buildJsonObject {
        put("final_status", "SUPPRESSED")
        put("tracked_fraction", 0.0)
        put("state_coverage", 0.0)
        put("legal_transition_fraction", 0.0)
        put("mean_action_confidence", 0.0)
        put("max_estimate_confidence", 0.0)
        put("distinct_state_path", buildJsonArray { })
        put("completed_repetitions", JsonNull)
        put("repetition_delta_from_reference", JsonNull)
        put("reason", compileReason)
    })
    put("candidate", buildJsonObject {
        put("spatial_analyzable_fraction", candidateSpatial.analyzableFraction)
        put("frame_count", candidateSpatial.frames.size)
    })
    put("runtime", runtimeJson(decodeNanos, adaptNanos, spatialNanos, compileNanos, recognitionNanos, totalStartNanos))
}

private fun recognitionMetrics(profile: ActionProfile, recognition: ActionRecognitionResult): RecognitionMetrics {
    val entryAndTrackedEstimates = recognition.estimates.filter {
        it.status in setOf(
            ActionTrackingStatus.POSSIBLE_ENTRY,
            ActionTrackingStatus.TRACKING,
            ActionTrackingStatus.COMPLETED,
        ) && it.stateIndex != null
    }
    val trackedEstimates = recognition.estimates.filter {
        (it.status == ActionTrackingStatus.TRACKING || it.status == ActionTrackingStatus.COMPLETED) &&
            it.stateIndex != null
    }
    val coverageStateIndices = mutableListOf<Int>()
    entryAndTrackedEstimates.mapNotNull(ActionStateEstimate::stateIndex).forEach { stateIndex ->
        if (coverageStateIndices.lastOrNull() != stateIndex) coverageStateIndices += stateIndex
    }
    val expandedCoverageStateIndices = expandObservedStatePath(profile, coverageStateIndices)
    val stateCoverage = if (profile.states.isEmpty()) {
        0.0
    } else {
        expandedCoverageStateIndices.toSet().size.toDouble() / profile.states.size.toDouble()
    }
    val trackedStateIndices = mutableListOf<Int>()
    trackedEstimates.mapNotNull(ActionStateEstimate::stateIndex).forEach { stateIndex ->
        if (trackedStateIndices.lastOrNull() != stateIndex) trackedStateIndices += stateIndex
    }
    val expandedTrackedStateIndices = expandObservedStatePath(profile, trackedStateIndices)
    val distinctStatePath = expandedTrackedStateIndices.map { profile.states[it].id }
    val legalEdges = profile.transitions.map { transition ->
        profile.states[transition.fromStateIndex].id to profile.states[transition.toStateIndex].id
    }.toSet()
    val observedEdges = distinctStatePath.zipWithNext()
    val legalFraction = if (observedEdges.isEmpty()) {
        if (distinctStatePath.isEmpty()) 0.0 else 1.0
    } else {
        observedEdges.count { it in legalEdges }.toDouble() / observedEdges.size.toDouble()
    }
    return RecognitionMetrics(
        stateCoverage = stateCoverage.coerceIn(0.0, 1.0),
        legalTransitionFraction = legalFraction.coerceIn(0.0, 1.0),
        distinctStatePath = distinctStatePath,
        meanActionConfidence = trackedEstimates.map(ActionStateEstimate::confidence).averageOrZero(),
        maxEstimateConfidence = recognition.estimates.maxOfOrNull(ActionStateEstimate::confidence) ?: 0.0,
        actionWindowTrackedFraction = actionWindowTrackedFraction(recognition),
    )
}

private fun actionWindowTrackedFraction(recognition: ActionRecognitionResult): Double {
    val firstTracked = recognition.estimates.indexOfFirst { estimate ->
        (estimate.status == ActionTrackingStatus.TRACKING || estimate.status == ActionTrackingStatus.COMPLETED) &&
            estimate.stateIndex != null
    }
    if (firstTracked < 0) return 0.0
    var start = firstTracked
    while (
        start > 0 &&
        recognition.estimates[start - 1].status == ActionTrackingStatus.POSSIBLE_ENTRY &&
        recognition.estimates[start - 1].stateIndex != null
    ) {
        start -= 1
    }
    val lastTracked = recognition.estimates.indexOfLast { estimate ->
        (estimate.status == ActionTrackingStatus.TRACKING || estimate.status == ActionTrackingStatus.COMPLETED) &&
            estimate.stateIndex != null
    }
    if (lastTracked < start) return 0.0
    val window = recognition.estimates.subList(start, lastTracked + 1)
    val tracked = window.count { estimate ->
        (estimate.status == ActionTrackingStatus.TRACKING || estimate.status == ActionTrackingStatus.COMPLETED) &&
            estimate.stateIndex != null
    }
    return tracked.toDouble() / window.size.toDouble()
}

private fun expandObservedStatePath(profile: ActionProfile, observed: List<Int>): List<Int> {
    if (observed.isEmpty()) return emptyList()
    val result = mutableListOf(observed.first())
    val maximumSkip = maxOf(
        referenceActionRecognizerConfig.maximumEntryStateSkip,
        referenceActionRecognizerConfig.maximumTrackingStateSkip,
    )
    observed.zipWithNext().forEach { (from, to) ->
        val distance = if (profile.cyclic) {
            (to - from + profile.states.size) % profile.states.size
        } else {
            to - from
        }
        if (distance in 1..maximumSkip) {
            for (step in 1..distance) {
                val raw = from + step
                result += if (profile.cyclic) raw % profile.states.size else raw
            }
        } else if (result.lastOrNull() != to) {
            result += to
        }
    }
    return result
}

private fun observationClassification(frame: SpatialObservationFrame, estimate: ActionStateEstimate): String {
    if (frame.intrinsicDescriptor.confidence < MINIMUM_ACTION_FRAME_CONFIDENCE) return "SUPPRESSED"
    return when (estimate.status) {
        ActionTrackingStatus.NO_ACTION -> "NO_ACTION"
        ActionTrackingStatus.POSSIBLE_ENTRY, ActionTrackingStatus.LOST -> "UNCERTAIN"
        ActionTrackingStatus.TRACKING, ActionTrackingStatus.COMPLETED -> if (
            estimate.confidence >= MINIMUM_ACTION_STATE_CONFIDENCE &&
            estimate.featureCoverage >= MINIMUM_ACTION_FEATURE_COVERAGE
        ) {
            "ACTION"
        } else {
            "SUPPRESSED"
        }
    }
}

private fun observationJson(
    frame: SpatialObservationFrame,
    estimate: ActionStateEstimate,
    classification: String,
): JsonObject = buildJsonObject {
    put("timestamp_ms", frame.timestamp.value)
    if (estimate.stateId != null) {
        put("state_id", estimate.stateId)
    } else {
        put("state_id", JsonNull)
    }
    put("tracking_status", estimate.status.name)
    put("classification", classification)
    put("confidence", estimate.confidence)
    put("feature_coverage", estimate.featureCoverage)
    put("frame_confidence", frame.intrinsicDescriptor.confidence)
    put("mirror_mode", estimate.mirrorMode.name)
    put("completed_repetitions", estimate.completedRepetitions)
    estimate.timing?.let { timing ->
        put("timing", buildJsonObject {
            put("state_id", timing.stateId)
            put("observed_duration_ms", timing.observedDurationMs)
            put("reference_median_ms", timing.referenceDurationMs.median)
            put("relative_to_reference_median", timing.relativeToReferenceMedian)
            put("classification", timing.classification.name)
            put("confidence", timing.confidence)
        })
    }
}

private fun classificationConfidence(
    classification: String,
    profile: ActionProfile,
    recognition: ActionRecognitionResult,
    metrics: RecognitionMetrics,
): Double = when (classification) {
    "ACTION" -> (
        0.32 * metrics.meanActionConfidence +
            0.18 * recognition.trackedFraction +
            0.20 * metrics.stateCoverage +
            0.15 * metrics.legalTransitionFraction +
            0.15 * profile.confidence
        ).coerceIn(0.0, 1.0)
    "NO_ACTION" -> (1.0 - 0.65 * metrics.maxEstimateConfidence - 0.20 * metrics.stateCoverage)
        .coerceIn(0.0, 1.0)
    else -> (0.25 + 0.35 * metrics.maxEstimateConfidence + 0.20 * metrics.stateCoverage)
        .coerceIn(0.0, 0.75)
}

private fun aggregateDeviationSpans(measurements: List<ReferenceDeviationMeasurement>): List<DeviationSpan> {
    val spans = mutableListOf<DeviationSpan>()
    val open = mutableMapOf<String, DeviationSpan>()
    measurements.asSequence()
        .filter(ReferenceDeviationMeasurement::persistenceCandidate)
        .sortedBy { it.timestamp.value }
        .forEach { measurement ->
            val key = "${measurement.stateId}|${measurement.feature}"
            val existing = open[key]
            val span = if (existing == null || measurement.timestamp.value - existing.endMs > DEVIATION_SPAN_MAX_GAP_MS) {
                existing?.let(spans::add)
                DeviationSpan(
                    stateId = measurement.stateId,
                    feature = measurement.feature,
                    startMs = measurement.timestamp.value,
                    endMs = measurement.timestamp.value,
                    maxConfidence = measurement.confidence,
                    maxNormalizedDeviation = measurement.normalizedDeviation,
                    peakSignedDelta = measurement.signedDeltaOutsideRange,
                    referenceLower = measurement.referenceRange.start,
                    referenceUpper = measurement.referenceRange.endInclusive,
                    referenceMedian = measurement.referenceMedian,
                ).also { open[key] = it }
            } else {
                existing
            }
            span.endMs = measurement.timestamp.value
            span.maxConfidence = max(span.maxConfidence, measurement.confidence)
            if (measurement.normalizedDeviation > span.maxNormalizedDeviation) {
                span.maxNormalizedDeviation = measurement.normalizedDeviation
            }
            if (abs(measurement.signedDeltaOutsideRange) > abs(span.peakSignedDelta)) {
                span.peakSignedDelta = measurement.signedDeltaOutsideRange
            }
        }
    spans += open.values
    return spans.sortedWith(compareBy(DeviationSpan::startMs, DeviationSpan::feature))
}

private fun deviationSpansJson(spans: List<DeviationSpan>): JsonArray = buildJsonArray {
    spans.forEach { span ->
        add(buildJsonObject {
            put("kind", "reference_geometry")
            put("state_id", span.stateId)
            put("feature", span.feature)
            put("start_ms", span.startMs)
            put("end_ms", span.endMs)
            put("confidence", span.maxConfidence)
            put("normalized_deviation_max", span.maxNormalizedDeviation)
            put("signed_delta_outside_range_peak", span.peakSignedDelta)
            put("reference_lower", span.referenceLower)
            put("reference_upper", span.referenceUpper)
            put("reference_median", span.referenceMedian)
        })
    }
}

private fun compileJson(
    status: String,
    reason: String?,
    message: String?,
    profile: ActionProfile?,
): JsonObject = buildJsonObject {
    put("status", status)
    if (reason != null) {
        put("reason", reason)
    } else {
        put("reason", JsonNull)
    }
    if (message != null) {
        put("message", message)
    } else {
        put("message", JsonNull)
    }
    if (profile != null) {
        put("validation", validationJson(profile))
        put("profile_confidence", profile.confidence)
    } else {
        put("validation", JsonNull)
        put("profile_confidence", 0.0)
    }
}

private fun legalOutputEdges(profile: ActionProfile): List<Pair<String, String>> =
    profile.transitions.map { transition ->
        profile.states[transition.fromStateIndex].id to profile.states[transition.toStateIndex].id
    }

private fun profileJson(profile: ActionProfile, usable: Boolean): JsonObject = buildJsonObject {
    put("usable", usable)
    put("version", profile.version)
    put("cyclic", profile.cyclic)
    put("cyclicity_confidence", profile.cyclicityConfidence)
    put("reference_repetitions", profile.referenceRepetitions)
    put("confidence", profile.confidence)
    put("state_ids", stringArray(profile.states.map { it.id }))
    put("legal_transitions", buildJsonArray {
        legalOutputEdges(profile).forEach { (from, to) ->
            add(buildJsonArray {
                add(JsonPrimitive(from))
                add(JsonPrimitive(to))
            })
        }
    })
    put("validation", validationJson(profile))
}

private fun unusableProfileJson(): JsonObject = buildJsonObject {
    put("usable", false)
    put("version", JsonNull)
    put("cyclic", false)
    put("cyclicity_confidence", 0.0)
    put("reference_repetitions", JsonNull)
    put("confidence", 0.0)
    put("state_ids", buildJsonArray { })
    put("legal_transitions", buildJsonArray { })
    put("validation", JsonNull)
}

private fun validationJson(profile: ActionProfile): JsonObject = buildJsonObject {
    val validation = profile.validation
    put("reconstruction_accuracy", validation.reconstructionAccuracy)
    put("transition_coverage", validation.transitionCoverage)
    put("mean_recognition_confidence", validation.meanRecognitionConfidence)
    put("analyzable_fraction", validation.analyzableFraction)
    put("reference_outlier_fraction", validation.referenceOutlierFraction)
}

private fun capabilitiesJson(): JsonObject = buildJsonObject {
    // The recognizer has mirror handling and body-centric normalization, but this adapter has not
    // earned a universal invariance claim on real footage. Keep those validation gates opt-in.
    put("mirror_invariant", false)
    put("viewpoint_invariant", false)
    put("live_cues", false)
}

private fun runtimeJson(
    decodeNanos: Long,
    adaptNanos: Long,
    spatialNanos: Long,
    compileNanos: Long,
    recognitionNanos: Long,
    totalStartNanos: Long,
): JsonObject = buildJsonObject {
    put("decode_ms", nanosToMs(decodeNanos))
    put("adapt_ms", nanosToMs(adaptNanos))
    put("spatial_ms", nanosToMs(spatialNanos))
    put("compile_ms", nanosToMs(compileNanos))
    put("recognition_and_deviation_ms", nanosToMs(recognitionNanos))
    put("total_ms", nanosToMs(System.nanoTime() - totalStartNanos))
}

private fun nanosToMs(value: Long): Double = value.toDouble() / 1_000_000.0

private fun stringArray(values: List<String>): JsonArray = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

private fun JsonObject.requiredString(key: String): String = this[key]?.jsonPrimitive?.content
    ?: error("missing required string field $key")

private fun JsonObject.requiredDouble(key: String): Double = this[key]?.jsonPrimitive?.double
    ?: error("missing required numeric field $key")

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
