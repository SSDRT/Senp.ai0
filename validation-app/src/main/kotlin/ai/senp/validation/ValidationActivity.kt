package ai.senp.validation

import ai.senp.core.contracts.AlignmentPoint
import ai.senp.core.contracts.AnalysisConfiguration
import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.AnalysisOutcome
import ai.senp.core.contracts.AnalysisRequest
import ai.senp.core.contracts.AnalysisResult
import ai.senp.core.contracts.CacheStatus
import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.MotionSeries
import ai.senp.core.contracts.MotionUnitCorrespondence
import ai.senp.core.contracts.PhaseSeries
import ai.senp.core.contracts.PipelineStageId
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.ProblemWindow
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.SpatialReliabilityStatus
import ai.senp.core.contracts.SynchronizationResult
import ai.senp.core.contracts.TemporalStructure
import ai.senp.core.contracts.TimestampCorrespondence
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoPoseExtraction
import ai.senp.core.contracts.VideoPoseFailureKind
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.VideoSource
import ai.senp.motion.MotionCoreVersions
import ai.senp.pose.mediapipe.AndroidVideoPoseExtractor
import ai.senp.sync.v2.VideoSynchronizationOutcome
import ai.senp.sync.v2.VideoSynchronizationRequest
import ai.senp.sync.v2.VideoSynchronizationRun
import ai.senp.video.DecodeConfig
import ai.senp.video.DecodedFrame
import ai.senp.video.SequentialVideoDecoder
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ai.senp.validation.ui.SenpApp

/** Interactive mobile UI & emulator validation entry point. */
class ValidationActivity : ComponentActivity() {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (intent.getBooleanExtra(EXTRA_RUN_HEADLESS, false)) {
            thread(name = "senp-e2e-validation") {
                runCatching {
                    if (intent.getStringExtra(EXTRA_MODE) == MODE_SYNC_V2) validateSyncV2Pair() else validatePair()
                }
                    .onSuccess { summary ->
                        Log.i(TAG, "COMPLETE $summary")
                        runOnUiThread { finish() }
                    }
                    .onFailure { error ->
                        Log.e(TAG, "FAILED ${error.message}", error)
                        runOnUiThread { finish() }
                    }
            }
        } else {
            setContent {
                SenpApp(
                    onOpenLivePushUp = {
                        startActivity(Intent(this, LivePushUpActivity::class.java))
                    },
                )
            }
        }
    }

    private fun validatePair(): String {
        val sourceFile = requiredVideo(EXTRA_SOURCE_VIDEO)
        val referenceFile = requiredVideo(EXTRA_REFERENCE_VIDEO)
        val profileVersion = intent.getStringExtra(EXTRA_EXERCISE_PROFILE_VERSION)
            ?.takeIf(String::isNotBlank) ?: MotionCoreVersions.EXERCISE_PROFILES
        val label = sanitize(intent.getStringExtra(EXTRA_LABEL) ?: "${sourceFile.nameWithoutExtension}-vs-${referenceFile.nameWithoutExtension}")
        val outputDirectory = File(requireNotNull(getExternalFilesDir(null)), "e2e/$label").apply {
            deleteRecursively()
            check(mkdirs()) { "Unable to create output directory: $absolutePath" }
        }

        val sampling = SamplingConfiguration(
            targetFramesPerSecond = intent.getIntExtra(EXTRA_FPS, 15).coerceIn(1, 30),
            longEdgeCapPx = intent.getIntExtra(EXTRA_LONG_EDGE, 640).coerceIn(128, 1280),
        )
        val model = PoseModelConfiguration(Sha256(MODEL_SHA256))
        val configuration = AnalysisConfiguration(
            model = model,
            pipelineVersion = EngineComposition.PIPELINE_VERSION,
            sampling = sampling,
            normalizationVersion = MotionCoreVersions.NORMALIZATION,
            exerciseProfileVersion = profileVersion,
        )
        val request = AnalysisRequest(
            requestId = label,
            requestedAtEpochMs = TimestampMs(System.currentTimeMillis()),
            source = VideoSource(sourceFile.absolutePath, Sha256(sha256(sourceFile))),
            reference = VideoSource(referenceFile.absolutePath, Sha256(sha256(referenceFile))),
            configuration = configuration,
        )
        File(outputDirectory, "request.json").writeText(json.encodeToString(request))

        val composition = EngineComposition(this)
        if (intent.getBooleanExtra(EXTRA_SYNC_V2_ONLY, false)) {
            return validateSynchronizationV2(composition, request, outputDirectory)
        }
        val first = timedAnalyze(composition, request)
        val firstResult = requireAnalysis(first.outcome, outputDirectory, "analysis_failure_miss.json")
        check(firstResult.provenance.cacheStatus == CacheStatus.MISS) { "first analysis must be a cache miss" }
        File(outputDirectory, "analysis_result_miss.json").writeText(json.encodeToString(firstResult))

        val second = timedAnalyze(composition, request.copy(requestId = "$label-cache-hit"))
        val secondResult = requireAnalysis(second.outcome, outputDirectory, "analysis_failure_hit.json")
        check(secondResult.provenance.cacheStatus == CacheStatus.HIT) { "second analysis must be a cache hit" }
        check(firstResult.provenance.cacheKeyStableId == secondResult.provenance.cacheKeyStableId) {
            "cache key stable ID changed for an identical analysis configuration"
        }
        check(firstResult.payload == secondResult.payload) { "cache hit payload differs from computed payload" }
        val recomputeStages = secondResult.timings.map { it.stage }.filter {
            it in setOf(
                PipelineStageId.VIDEO_POSE_SOURCE,
                PipelineStageId.MOTION_SOURCE,
                PipelineStageId.PHASE_SOURCE,
                PipelineStageId.VIDEO_POSE_REFERENCE,
                PipelineStageId.MOTION_REFERENCE,
                PipelineStageId.PHASE_REFERENCE,
                PipelineStageId.ALIGNMENT,
            )
        }
        check(recomputeStages.isEmpty()) { "cache hit unexpectedly recomputed stages: $recomputeStages" }
        File(outputDirectory, "analysis_result_hit.json").writeText(json.encodeToString(secondResult))

        val diagnostic = extractDiagnostics(composition, request, outputDirectory)
        val chosen = representativeAlignmentPoints(firstResult)
        val sourceCaptures = captureFrames(
            sourceFile,
            VideoRole.SOURCE,
            diagnostic.source.poses,
            chosen.map { it.sourceTimestamp.value },
            outputDirectory,
            "matched-source",
        )
        val referenceCaptures = captureFrames(
            referenceFile,
            VideoRole.REFERENCE,
            diagnostic.reference.poses,
            chosen.map { it.referenceTimestamp.value },
            outputDirectory,
            "matched-reference",
        )
        saveMatchedContactSheet(File(outputDirectory, "matched_raw_pose_contact_sheet.jpg"), sourceCaptures, referenceCaptures)
        recycle(sourceCaptures)
        recycle(referenceCaptures)

        val problemPairs = representativeProblemPoints(firstResult.payload.problems, firstResult.payload.alignment.points)
        if (problemPairs.isNotEmpty()) {
            val problemSource = captureFrames(
                sourceFile,
                VideoRole.SOURCE,
                diagnostic.source.poses,
                problemPairs.map { it.first },
                outputDirectory,
                "problem-source",
            )
            val problemReference = captureFrames(
                referenceFile,
                VideoRole.REFERENCE,
                diagnostic.reference.poses,
                problemPairs.map { it.second },
                outputDirectory,
                "problem-reference",
            )
            saveMatchedContactSheet(File(outputDirectory, "problem_window_contact_sheet.jpg"), problemSource, problemReference)
            recycle(problemSource)
            recycle(problemReference)
        } else {
            File(outputDirectory, "problem_window_contact_sheet.NONE").writeText("No problem windows reported.\n")
        }

        val cancellation = verifyCancellation(sourceFile, request.source.sha256, sampling, model)
        check(cancellation["ok"]?.toString() == "true") { "cancellation verification failed: $cancellation" }
        val corrupt = verifyCorruptVideo(sampling, model)
        check(corrupt["ok"]?.toString() == "true") { "corrupt-video verification failed: $corrupt" }
        val edgeCases = buildJsonObject {
            put("cancellation", cancellation)
            put("corrupt_video", corrupt)
            put("no_person_verified_by", JsonPrimitive("android-pose-mediapipe blankFrameProducesNoPersonAndRejectsDuplicateTimestamp"))
            put("long_blind_verified_by", JsonPrimitive("core-alignment canonical long-blind suppression suite and QA golden fixture"))
        }
        File(outputDirectory, "edge_cases.json").writeText(json.encodeToString(JsonObject.serializer(), edgeCases))

        val hitDurations = mutableListOf(second.elapsedMs)
        repeat(2) { index ->
            val timed = timedAnalyze(composition, request.copy(requestId = "$label-cache-benchmark-${index + 1}"))
            val hit = requireAnalysis(timed.outcome, outputDirectory, "analysis_failure_benchmark_${index + 1}.json")
            check(hit.provenance.cacheStatus == CacheStatus.HIT)
            hitDurations += timed.elapsedMs
        }
        val peakRss = readVmHwmBytes()
        writeStageReport(outputDirectory, label, firstResult, peakRss)
        writeBenchmarkReport(outputDirectory, label, first.elapsedMs, hitDurations, peakRss)
        writeCacheReport(outputDirectory, firstResult, secondResult, first.elapsedMs, second.elapsedMs)

        val summary = buildString {
            appendLine("label=$label")
            appendLine("source=${sourceFile.absolutePath}")
            appendLine("reference=${referenceFile.absolutePath}")
            appendLine("exerciseProfileVersion=$profileVersion")
            appendLine("sourceSha256=${request.source.sha256.value}")
            appendLine("referenceSha256=${request.reference.sha256.value}")
            appendLine("cacheKeyStableId=${firstResult.provenance.cacheKeyStableId.value}")
            appendLine("cacheMissElapsedMs=${format(first.elapsedMs)}")
            appendLine("cacheHitElapsedMs=${format(second.elapsedMs)}")
            appendLine("referenceReuse=true")
            appendLine("alignmentMode=${firstResult.payload.alignment.mode}")
            appendLine("alignmentPoints=${firstResult.payload.alignment.points.size}")
            appendLine("alignmentConfidence=${firstResult.payload.alignment.aggregateConfidence}")
            appendLine("problemWindows=${firstResult.payload.problems.size}")
            appendLine("sourceFrames=${firstResult.payload.sourceFrameCount}")
            appendLine("referenceFrames=${firstResult.payload.referenceFrameCount}")
            appendLine("sourceDetected=${firstResult.payload.sourceVideoPoseDiagnostics.detectedFrameCount}")
            appendLine("referenceDetected=${firstResult.payload.referenceVideoPoseDiagnostics.detectedFrameCount}")
            appendLine("sourcePeakInFlight=${firstResult.payload.sourceVideoPoseDiagnostics.peakInFlightFrames}")
            appendLine("referencePeakInFlight=${firstResult.payload.referenceVideoPoseDiagnostics.peakInFlightFrames}")
            appendLine("processVmHwmBytes=$peakRss")
            appendLine("matchedAlignmentTimestampsMs=${chosen.joinToString(";") { "${it.sourceTimestamp.value}:${it.referenceTimestamp.value}" }}")
            appendLine("problemTimestampPairsMs=${problemPairs.joinToString(";") { "${it.first}:${it.second}" }}")
        }
        File(outputDirectory, "summary.txt").writeText(summary)
        File(outputDirectory, "COMPLETE").writeText("ok\n")
        return "OK $label points=${firstResult.payload.alignment.points.size} problems=${firstResult.payload.problems.size}"
    }

    private fun validateSynchronizationV2(
        composition: EngineComposition,
        request: AnalysisRequest,
        outputDirectory: File,
    ): String = runBlocking {
        val outcome = composition.synchronizationPipeline.synchronize(
            VideoSynchronizationRequest(
                source = request.source,
                reference = request.reference,
                sampling = request.configuration.sampling,
                model = request.configuration.model,
            ),
        )
        val run = when (outcome) {
            is VideoSynchronizationOutcome.Success -> outcome.run
            is VideoSynchronizationOutcome.Failure -> {
                File(outputDirectory, "sync_v2_failure.json").writeText(
                    json.encodeToString(AnalysisFailure.serializer(), outcome.failure),
                )
                error("Sync-v2 failed: ${outcome.failure}")
            }
        }
        val result = run.synchronization.result
        File(outputDirectory, "sync_v2_result.json").writeText(
            json.encodeToString(ai.senp.core.contracts.SynchronizationResult.serializer(), result),
        )
        File(outputDirectory, "source_pose_extraction.json").writeText(
            json.encodeToString(VideoPoseExtraction.serializer(), run.sourcePoseExtraction),
        )
        File(outputDirectory, "reference_pose_extraction.json").writeText(
            json.encodeToString(VideoPoseExtraction.serializer(), run.referencePoseExtraction),
        )
        val stats = run.synchronization.stats
        val timing = run.timings
        val diagnostics = buildJsonObject {
            put("engine_version", ai.senp.sync.v2.SynchronizationKernelV2Versions.ENGINE)
            put("status", result.status.name)
            put("overall_confidence", result.diagnostics.overallConfidence)
            put("source_analyzable_fraction", result.diagnostics.sourceAnalyzableFraction)
            put("reference_analyzable_fraction", result.diagnostics.referenceAnalyzableFraction)
            put("source_pose_extraction_ms", timing.sourcePoseExtractionNanos / 1_000_000.0)
            put("reference_pose_extraction_ms", timing.referencePoseExtractionNanos / 1_000_000.0)
            put("post_pose_sync_ms", timing.postPoseSynchronizationNanos / 1_000_000.0)
            put("total_pipeline_ms", timing.totalNanos / 1_000_000.0)
            put("post_pose_fraction", timing.postPoseFraction)
            put("source_decode_ms", timing.sourceDecodeNanos / 1_000_000.0)
            put("source_inference_ms", timing.sourceInferenceNanos / 1_000_000.0)
            put("reference_decode_ms", timing.referenceDecodeNanos / 1_000_000.0)
            put("reference_inference_ms", timing.referenceInferenceNanos / 1_000_000.0)
            put("iteration_count", stats.iterationCount)
            put("total_coarse_unit_comparisons", stats.totalCoarseUnitComparisons)
            put("total_fine_cells_evaluated", stats.totalFineCellsEvaluated)
            put("maximum_fine_band_width", stats.maximumFineBandWidth)
            put("total_fine_alignment_count", stats.totalFineAlignmentCount)
            put("peak_rss_bytes", readVmHwmBytes())
            put("iterations", buildJsonArray {
                stats.iterations.forEach { iteration ->
                    add(buildJsonObject {
                        put("iteration", iteration.iteration)
                        put("phase", iteration.phase)
                        put("status", iteration.status.name)
                        put("quality", iteration.quality)
                        put("overall_confidence", iteration.overallConfidence)
                        put("spatial_confidence", iteration.spatialConfidence)
                        put("correspondence_confidence", iteration.correspondenceConfidence)
                        put("correspondence_ambiguity", iteration.correspondenceAmbiguity)
                        put("matched_unit_count", iteration.matchedUnitCount)
                        put("matched_timestamp_count", iteration.matchedTimestampCount)
                        put("paired_spatial_evidence_count", iteration.pairedSpatialEvidenceCount)
                        put("refined_hypothesis_count", iteration.refinedHypothesisCount)
                        put("coarse_unit_comparisons", iteration.temporalStats.coarseUnitComparisons)
                        put("fine_cells_evaluated", iteration.temporalStats.fineCellsEvaluated)
                        put("maximum_fine_band_width", iteration.temporalStats.maximumFineBandWidth)
                        put("fine_alignment_count", iteration.temporalStats.fineAlignmentCount)
                    })
                }
            })
        }
        File(outputDirectory, "sync_v2_diagnostics.json").writeText(json.encodeToString(JsonObject.serializer(), diagnostics))
        val summary = buildString {
            appendLine("status=${result.status}")
            appendLine("overallConfidence=${result.diagnostics.overallConfidence}")
            appendLine("sourceAnalyzableFraction=${result.diagnostics.sourceAnalyzableFraction}")
            appendLine("referenceAnalyzableFraction=${result.diagnostics.referenceAnalyzableFraction}")
            appendLine("sourcePoseExtractionMs=${format(timing.sourcePoseExtractionNanos / 1_000_000.0)}")
            appendLine("referencePoseExtractionMs=${format(timing.referencePoseExtractionNanos / 1_000_000.0)}")
            appendLine("postPoseSyncMs=${format(timing.postPoseSynchronizationNanos / 1_000_000.0)}")
            appendLine("totalPipelineMs=${format(timing.totalNanos / 1_000_000.0)}")
            appendLine("postPoseFraction=${format(timing.postPoseFraction)}")
            appendLine("iterations=${stats.iterationCount}")
            appendLine("fineCells=${stats.totalFineCellsEvaluated}")
            appendLine("maxFineBand=${stats.maximumFineBandWidth}")
        }
        File(outputDirectory, "sync_v2_summary.txt").writeText(summary)
        File(outputDirectory, "COMPLETE").writeText("sync-v2-ok\n")
        "SYNC_V2_OK ${request.requestId} status=${result.status}"
    }

    private fun validateSyncV2Pair(): String {
        val sourceFile = requiredVideo(EXTRA_SOURCE_VIDEO)
        val referenceFile = requiredVideo(EXTRA_REFERENCE_VIDEO)
        val label = sanitize(intent.getStringExtra(EXTRA_LABEL) ?: "-vs-")
        val outputDirectory = File(requireNotNull(getExternalFilesDir(null)), "sync-v2/$label").apply {
            deleteRecursively()
            check(mkdirs()) { "Unable to create Sync-v2 output directory: $absolutePath" }
        }
        val sampling = SamplingConfiguration(
            targetFramesPerSecond = intent.getIntExtra(EXTRA_FPS, 15).coerceIn(1, 30),
            longEdgeCapPx = intent.getIntExtra(EXTRA_LONG_EDGE, 640).coerceIn(128, 1280),
        )
        val model = PoseModelConfiguration(Sha256(MODEL_SHA256))
        val request = VideoSynchronizationRequest(
            source = VideoSource(sourceFile.absolutePath, Sha256(sha256(sourceFile))),
            reference = VideoSource(referenceFile.absolutePath, Sha256(sha256(referenceFile))),
            sampling = sampling,
            model = model,
        )
        val composition = EngineComposition(this)
        val outcome = runBlocking { composition.synchronizationPipeline.synchronize(request) }
        val run = when (outcome) {
            is VideoSynchronizationOutcome.Success -> outcome.run
            is VideoSynchronizationOutcome.Failure -> {
                File(outputDirectory, "sync_v2_failure.json").writeText(
                    json.encodeToString(AnalysisFailure.serializer(), outcome.failure),
                )
                error("Sync-v2 video pipeline failed: ${outcome.failure}")
            }
        }
        val result = run.synchronization.result
        File(outputDirectory, "synchronization-result.json").writeText(
            json.encodeToString(SynchronizationResult.serializer(), result),
        )
        File(outputDirectory, "source_pose_extraction.json").writeText(json.encodeToString(run.sourcePoseExtraction))
        File(outputDirectory, "reference_pose_extraction.json").writeText(json.encodeToString(run.referencePoseExtraction))
        File(outputDirectory, "normalized-result.json").writeText(
            json.encodeToString(JsonObject.serializer(), normalizedSyncResult(run)),
        )
        File(outputDirectory, "sync-v2-diagnostics.json").writeText(
            json.encodeToString(JsonObject.serializer(), syncV2Diagnostics(run)),
        )

        val matched = result.correspondences.filterIsInstance<MotionUnitCorrespondence.MatchedUnit>()
            .flatMap { unit ->
                unit.timeline.filterIsInstance<TimestampCorrespondence.Matched>().map { match ->
                    Triple(unit, match.sourceTimestamp.value, match.referenceTimestamp.value)
                }
            }
        if (matched.isNotEmpty()) {
            val indexes = listOf(0, matched.size / 3, (matched.size * 2) / 3, matched.lastIndex).distinct()
            val chosen = indexes.map(matched::get)
            val sourceCaptures = captureFrames(
                sourceFile,
                VideoRole.SOURCE,
                run.sourcePoseExtraction.poses,
                chosen.map { it.second },
                outputDirectory,
                "sync-v2-source",
            )
            val referenceCaptures = captureFrames(
                referenceFile,
                VideoRole.REFERENCE,
                run.referencePoseExtraction.poses,
                chosen.map { it.third },
                outputDirectory,
                "sync-v2-reference",
            )
            saveMatchedContactSheet(File(outputDirectory, "sync_v2_mapped_pose_contact_sheet.jpg"), sourceCaptures, referenceCaptures)
            recycle(sourceCaptures)
            recycle(referenceCaptures)
        } else {
            File(outputDirectory, "sync_v2_mapped_pose_contact_sheet.NONE").writeText("No defensible matched timestamp pairs.\n")
        }

        val summary = buildString {
            appendLine("mode=sync_v2")
            appendLine("label=$label")
            appendLine("status=${result.status}")
            appendLine("overallConfidence=${result.diagnostics.overallConfidence}")
            appendLine("sourceAnalyzableFraction=${result.diagnostics.sourceAnalyzableFraction}")
            appendLine("referenceAnalyzableFraction=${result.diagnostics.referenceAnalyzableFraction}")
            appendLine("matchedUnits=${result.correspondences.count { it is MotionUnitCorrespondence.MatchedUnit }}")
            appendLine("matchedTimestamps=${matched.size}")
            appendLine("postPoseSynchronizationMs=${run.timings.postPoseSynchronizationNanos / 1_000_000.0}")
            appendLine("poseAndPreprocessingMs=${run.timings.poseAndPreprocessingNanos / 1_000_000.0}")
            appendLine("totalMs=${run.timings.totalNanos / 1_000_000.0}")
            appendLine("postPoseFraction=${run.timings.postPoseFraction}")
            appendLine("peakRssBytes=${readVmHwmBytes()}")
        }
        File(outputDirectory, "summary.txt").writeText(summary)
        File(outputDirectory, "COMPLETE").writeText("ok\n")
        return "OK sync-v2 $label status=${result.status} mappings=${matched.size}"
    }

    private fun normalizedSyncResult(run: VideoSynchronizationRun): JsonObject {
        val result = run.synchronization.result
        val mappings = buildJsonArray {
            run.synchronization.mappingDiagnostics.forEach { diagnostic ->
                val referenceTimestamp = diagnostic.referenceTimestamp
                add(buildJsonObject {
                    put("source_ms", diagnostic.sourceTimestamp.value)
                    if (referenceTimestamp == null) {
                        put("reference_ms", JsonNull)
                    } else {
                        put("reference_ms", referenceTimestamp.value)
                    }
                    put("confidence", diagnostic.decisionConfidence)
                    put("source_unit_id", diagnostic.sourceUnitId)
                    diagnostic.referenceUnitId?.let { put("reference_unit_id", it) }
                    diagnostic.sourceDirection?.let { put("source_direction", it) } ?: put("source_direction", "UNKNOWN")
                    diagnostic.referenceDirection?.let { put("reference_direction", it) } ?: put("reference_direction", "UNKNOWN")
                    put("source_state", diagnostic.sourceState?.name ?: "UNKNOWN")
                    put("reference_state", diagnostic.referenceState?.name ?: if (diagnostic.referenceTimestamp == null) "UNMATCHED" else "UNKNOWN")
                    put(
                        "reliability",
                        if (diagnostic.sourceReliability in setOf(null, SpatialReliabilityStatus.COMPATIBLE) &&
                            diagnostic.referenceReliability in setOf(null, SpatialReliabilityStatus.COMPATIBLE)
                        ) {
                            "RELIABLE"
                        } else {
                            listOfNotNull(diagnostic.sourceReliability, diagnostic.referenceReliability).joinToString("+") { it.name }
                        },
                    )
                })
            }
        }
        val sourceUnmatched = buildJsonArray {
            result.correspondences.filterIsInstance<MotionUnitCorrespondence.SourceUnmatchedUnit>().forEach { unit ->
                add(buildJsonObject {
                    put("unit_id", unit.sourceUnitId)
                    put("reason", unit.reason.name)
                    put("confidence", unit.decisionConfidence)
                })
            }
        }
        val referenceUnmatched = buildJsonArray {
            result.correspondences.filterIsInstance<MotionUnitCorrespondence.ReferenceUnmatchedUnit>().forEach { unit ->
                add(buildJsonObject {
                    put("unit_id", unit.referenceUnitId)
                    put("reason", unit.reason.name)
                    put("confidence", unit.decisionConfidence)
                })
            }
        }
        val hypothesis = result.spatialDiagnostics.relativeViewHypotheses.maxByOrNull { it.confidence }
        val sourceScale = result.spatialDiagnostics.sourceTransforms.map { it.transform.uniformScale }.averageOrNull()
        val referenceScale = result.spatialDiagnostics.referenceTransforms.map { it.transform.uniformScale }.averageOrNull()
        val spatial = buildJsonObject {
            put("aggregate_confidence", result.spatialDiagnostics.aggregateConfidence)
            put("mirror", hypothesis?.mirror?.name ?: "UNKNOWN")
            put("selected_side", hypothesis?.selectedBodySide?.name ?: "UNKNOWN")
            hypothesis?.relativeYawDegrees?.let { put("relative_yaw_degrees", it) }
            hypothesis?.relativeElevationDegrees?.let { put("relative_elevation_degrees", it) }
            hypothesis?.sideSelectionStability?.let { put("side_selection_stability", it) }
            sourceScale?.let { put("source_uniform_scale", it) }
            referenceScale?.let { put("reference_uniform_scale", it) }
            put(
                "frozen_diagnostics",
                json.encodeToJsonElement(ai.senp.core.contracts.SpatialSynchronizationDiagnostics.serializer(), result.spatialDiagnostics),
            )
        }
        return buildJsonObject {
            put("schema_version", 1)
            put("origin", "production_android_mediapipe_sync_v2")
            put("status", result.status.name)
            put("confidence", result.diagnostics.overallConfidence)
            put("source_analyzable_fraction", result.diagnostics.sourceAnalyzableFraction)
            put("reference_analyzable_fraction", result.diagnostics.referenceAnalyzableFraction)
            put("mappings", mappings)
            put("unmatched_source_units", sourceUnmatched)
            put("unmatched_reference_units", referenceUnmatched)
            put("spatial_diagnostics", spatial)
            result.refusal?.let { put("refusal_reason", it.reason.name) }
        }
    }

    private fun syncV2Diagnostics(run: VideoSynchronizationRun): JsonObject = buildJsonObject {
        val stats = run.synchronization.stats
        put("engine", ai.senp.sync.v2.SynchronizationKernelV2Versions.ENGINE)
        put("iteration_count", stats.iterationCount)
        put("total_coarse_unit_comparisons", stats.totalCoarseUnitComparisons)
        put("total_fine_cells_evaluated", stats.totalFineCellsEvaluated)
        put("maximum_fine_band_width", stats.maximumFineBandWidth)
        put("total_fine_alignment_count", stats.totalFineAlignmentCount)
        put("iterations", buildJsonArray {
            stats.iterations.forEach { iteration ->
                add(buildJsonObject {
                    put("iteration", iteration.iteration)
                    put("phase", iteration.phase)
                    put("status", iteration.status.name)
                    put("quality", iteration.quality)
                    put("overall_confidence", iteration.overallConfidence)
                    put("spatial_confidence", iteration.spatialConfidence)
                    put("correspondence_confidence", iteration.correspondenceConfidence)
                    put("correspondence_ambiguity", iteration.correspondenceAmbiguity)
                    put("matched_unit_count", iteration.matchedUnitCount)
                    put("matched_timestamp_count", iteration.matchedTimestampCount)
                    put("paired_spatial_evidence_count", iteration.pairedSpatialEvidenceCount)
                    put("refined_hypothesis_count", iteration.refinedHypothesisCount)
                    put("fine_cells_evaluated", iteration.temporalStats.fineCellsEvaluated)
                    put("maximum_fine_band_width", iteration.temporalStats.maximumFineBandWidth)
                })
            }
        })
        put("timings", buildJsonObject {
            put("source_pose_extraction_ms", run.timings.sourcePoseExtractionNanos / 1_000_000.0)
            put("reference_pose_extraction_ms", run.timings.referencePoseExtractionNanos / 1_000_000.0)
            put("pose_and_preprocessing_ms", run.timings.poseAndPreprocessingNanos / 1_000_000.0)
            put("post_pose_sync_ms", run.timings.postPoseSynchronizationNanos / 1_000_000.0)
            put("total_pipeline_ms", run.timings.totalNanos / 1_000_000.0)
            put("post_pose_fraction_of_total", run.timings.postPoseFraction)
            put("source_decode_ms", run.timings.sourceDecodeNanos / 1_000_000.0)
            put("source_inference_ms", run.timings.sourceInferenceNanos / 1_000_000.0)
            put("reference_decode_ms", run.timings.referenceDecodeNanos / 1_000_000.0)
            put("reference_inference_ms", run.timings.referenceInferenceNanos / 1_000_000.0)
            put("source_pose_cache_hit", run.timings.sourcePoseCacheHit)
            put("reference_pose_cache_hit", run.timings.referencePoseCacheHit)
        })
        put("peak_rss_bytes", readVmHwmBytes())
    }

    private fun activityState(structure: TemporalStructure, timestamp: TimestampMs): String =
        structure.activitySegments.firstOrNull { it.range.contains(timestamp) }?.kind?.name ?: "UNKNOWN"

    private fun mappingReliability(result: SynchronizationResult, source: TimestampMs, reference: TimestampMs?): String {
        fun status(role: VideoRole, timestamp: TimestampMs): SpatialReliabilityStatus? = result.spatialDiagnostics.reliabilitySegments
            .firstOrNull { it.role == role && it.range.contains(timestamp) }?.status
        val sourceStatus = status(VideoRole.SOURCE, source)
        val referenceStatus = reference?.let { status(VideoRole.REFERENCE, it) }
        return if (sourceStatus in setOf(null, SpatialReliabilityStatus.COMPATIBLE) &&
            referenceStatus in setOf(null, SpatialReliabilityStatus.COMPATIBLE)
        ) {
            "RELIABLE"
        } else {
            listOfNotNull(sourceStatus, referenceStatus).joinToString("+") { it.name }
        }
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun extractDiagnostics(
        composition: EngineComposition,
        request: AnalysisRequest,
        outputDirectory: File,
    ): DiagnosticStages = runBlocking {
        val source = requireStage(
            composition.videoPoseExtractor.extract(VideoRole.SOURCE, request.source, request.configuration.sampling, request.configuration.model),
            "diagnostic source pose",
        )
        val sourceMotion = requireStage(
            composition.motionProcessor.process(source.poses, request.configuration.normalizationVersion, request.configuration.exerciseProfileVersion),
            "diagnostic source motion",
        )
        val sourcePhases = requireStage(
            composition.phaseDetector.detect(sourceMotion, request.configuration.exerciseProfileVersion),
            "diagnostic source phase",
        )
        val reference = requireStage(
            composition.videoPoseExtractor.extract(VideoRole.REFERENCE, request.reference, request.configuration.sampling, request.configuration.model),
            "diagnostic reference pose",
        )
        val referenceMotion = requireStage(
            composition.motionProcessor.process(reference.poses, request.configuration.normalizationVersion, request.configuration.exerciseProfileVersion),
            "diagnostic reference motion",
        )
        val referencePhases = requireStage(
            composition.phaseDetector.detect(referenceMotion, request.configuration.exerciseProfileVersion),
            "diagnostic reference phase",
        )
        val alignment = requireStage(
            composition.alignmentEngine.align(sourceMotion, sourcePhases, referenceMotion, referencePhases, request.configuration),
            "diagnostic alignment",
        )

        File(outputDirectory, "source_pose_extraction.json").writeText(json.encodeToString(source))
        File(outputDirectory, "reference_pose_extraction.json").writeText(json.encodeToString(reference))
        File(outputDirectory, "source_motion.json").writeText(json.encodeToString(sourceMotion))
        File(outputDirectory, "reference_motion.json").writeText(json.encodeToString(referenceMotion))
        File(outputDirectory, "source_phases.json").writeText(json.encodeToString(sourcePhases))
        File(outputDirectory, "reference_phases.json").writeText(json.encodeToString(referencePhases))
        File(outputDirectory, "diagnostic_alignment.json").writeText(
            json.encodeToString(
                buildJsonObject {
                    put("alignment", json.encodeToJsonElement(ai.senp.core.contracts.AlignmentResult.serializer(), alignment.alignment))
                    put("problems", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ProblemWindow.serializer()), alignment.problems))
                },
            ),
        )
        writeMotionCsv(File(outputDirectory, "source_motion_trace.csv"), sourceMotion)
        writeMotionCsv(File(outputDirectory, "reference_motion_trace.csv"), referenceMotion)
        writePhaseCsv(File(outputDirectory, "source_phase_trace.csv"), sourcePhases)
        writePhaseCsv(File(outputDirectory, "reference_phase_trace.csv"), referencePhases)
        writeAlignmentCsv(File(outputDirectory, "alignment_trace.csv"), alignment.alignment.points)
        DiagnosticStages(source, reference)
    }

    private fun timedAnalyze(composition: EngineComposition, request: AnalysisRequest): TimedOutcome {
        val started = SystemClock.elapsedRealtimeNanos()
        val outcome = runBlocking { composition.pipeline.analyze(request) }
        val elapsed = (SystemClock.elapsedRealtimeNanos() - started).toDouble() / 1_000_000.0
        return TimedOutcome(outcome, elapsed)
    }

    private fun requireAnalysis(outcome: AnalysisOutcome, outputDirectory: File, failureName: String): AnalysisResult = when (outcome) {
        is AnalysisOutcome.Success -> outcome.result
        is AnalysisOutcome.Failure -> {
            val failureJson = buildJsonObject {
                put("failure", json.encodeToJsonElement(AnalysisFailure.serializer(), outcome.failure))
                put("timings", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ai.senp.core.contracts.StageTiming.serializer()), outcome.timings))
            }
            File(outputDirectory, failureName).writeText(json.encodeToString(JsonObject.serializer(), failureJson))
            error("analysis failed at ${outcome.failure.stage}: ${outcome.failure.message}")
        }
    }

    private fun <T> requireStage(result: StageResult<T>, label: String): T = when (result) {
        is StageResult.Success -> result.value
        is StageResult.Failure -> error("$label failed: ${result.failure}")
    }

    private fun representativeAlignmentPoints(result: AnalysisResult): List<AlignmentPoint> {
        val points = result.payload.alignment.points
        check(points.isNotEmpty()) { "alignment path is empty" }
        val indexes = listOf(0, points.size / 3, (points.size * 2) / 3, points.lastIndex).distinct()
        return indexes.map(points::get)
    }

    private fun representativeProblemPoints(
        windows: List<ProblemWindow>,
        alignment: List<AlignmentPoint>,
    ): List<Pair<Long, Long>> = windows.take(4).map { window ->
        val source = window.sourceStart.value + (window.sourceEndExclusive.value - window.sourceStart.value) / 2L
        val referenceStart = window.referenceStart
        val referenceEndExclusive = window.referenceEndExclusive
        val reference = if (referenceStart != null && referenceEndExclusive != null) {
            referenceStart.value + (referenceEndExclusive.value - referenceStart.value) / 2L
        } else {
            alignment.minByOrNull { abs(it.sourceTimestamp.value - source) }?.referenceTimestamp?.value ?: 0L
        }
        source to reference
    }

    private fun captureFrames(
        file: File,
        role: VideoRole,
        poses: PoseSequence,
        requestedTimes: List<Long>,
        outputDirectory: File,
        prefix: String,
    ): List<Capture> {
        if (requestedTimes.isEmpty()) return emptyList()
        val pending = requestedTimes.mapIndexed { index, timestamp -> RequestedCapture(index, timestamp) }.sortedBy { it.timestampMs }
        val capturesByIndex = mutableMapOf<Int, Capture>()
        var pendingIndex = 0
        val decoded = SequentialVideoDecoder(DecodeConfig(targetFps = 30.0, longEdgeCapPx = 640)).decode(role, file) { frame ->
            while (pendingIndex < pending.size && frame.timestampMs >= pending[pendingIndex].timestampMs) {
                val request = pending[pendingIndex++]
                val pose = poses.frames.minByOrNull { abs(it.timestamp.value - frame.timestampMs) }
                capturesByIndex[request.index] = saveCapture(outputDirectory, prefix, request.timestampMs, frame, pose)
            }
            StageResult.Success(Unit)
        }
        requireStage(decoded, "$prefix frame decode")
        check(capturesByIndex.size == requestedTimes.size) {
            "$prefix captured ${capturesByIndex.size}/${requestedTimes.size} requested timestamps"
        }
        return requestedTimes.indices.map { capturesByIndex.getValue(it) }
    }

    private fun saveCapture(
        directory: File,
        prefix: String,
        requestedMs: Long,
        frame: DecodedFrame,
        pose: PoseFrame?,
    ): Capture {
        val raw = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(frame.argb8888, 0, frame.width, 0, 0, frame.width, frame.height)
        }
        val overlay = raw.copy(Bitmap.Config.ARGB_8888, true)
        drawPoseOverlay(overlay, frame.timestampMs, pose)
        val base = "${prefix}_${requestedMs}ms_actual_${frame.timestampMs}ms"
        saveJpeg(raw, File(directory, "${base}_raw.jpg"))
        saveJpeg(overlay, File(directory, "${base}_pose.jpg"))
        return Capture(requestedMs, frame.timestampMs, raw, overlay)
    }

    private fun drawPoseOverlay(bitmap: Bitmap, timestampMs: Long, pose: PoseFrame?) {
        val canvas = Canvas(bitmap)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 255, 120); strokeWidth = 4f }
        val point = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 80, 80); style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            textSize = 22f
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        canvas.drawText("$timestampMs ms ${pose?.validity?.status ?: "missing"}", 12f, 28f, text)
        if (pose == null || pose.validity.status == FrameValidityStatus.BLIND) return
        val points = pose.landmarks.associateBy { it.id }
        for ((from, to) in CONNECTIONS) {
            val a = points.getValue(from).image
            val b = points.getValue(to).image
            if (drawable(a.x, a.y) && drawable(b.x, b.y)) {
                canvas.drawLine(
                    (a.x * bitmap.width).toFloat(),
                    (a.y * bitmap.height).toFloat(),
                    (b.x * bitmap.width).toFloat(),
                    (b.y * bitmap.height).toFloat(),
                    line,
                )
            }
        }
        pose.landmarks.forEach { landmark ->
            val p = landmark.image
            if (drawable(p.x, p.y)) {
                canvas.drawCircle((p.x * bitmap.width).toFloat(), (p.y * bitmap.height).toFloat(), 4.5f, point)
            }
        }
    }

    private fun saveMatchedContactSheet(file: File, source: List<Capture>, reference: List<Capture>) {
        val columns = minOf(source.size, reference.size)
        check(columns > 0) { "cannot create a matched contact sheet with no captures" }
        val tileWidth = 320
        val tileHeight = 320
        val header = 30
        val rowHeight = tileHeight + header
        val sheet = Bitmap.createBitmap(tileWidth * columns, rowHeight * 4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.DKGRAY)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f }
        for (column in 0 until columns) {
            val s = source[column]
            val r = reference[column]
            drawSheetTile(canvas, s.raw, column, 0, tileWidth, tileHeight, header, "src raw ${s.actualMs}ms", text)
            drawSheetTile(canvas, s.overlay, column, 1, tileWidth, tileHeight, header, "src pose ${s.actualMs}ms", text)
            drawSheetTile(canvas, r.raw, column, 2, tileWidth, tileHeight, header, "ref raw ${r.actualMs}ms", text)
            drawSheetTile(canvas, r.overlay, column, 3, tileWidth, tileHeight, header, "ref pose ${r.actualMs}ms", text)
        }
        saveJpeg(sheet, file, 88)
        sheet.recycle()
    }

    private fun drawSheetTile(
        canvas: Canvas,
        bitmap: Bitmap,
        column: Int,
        row: Int,
        tileWidth: Int,
        tileHeight: Int,
        header: Int,
        label: String,
        text: Paint,
    ) {
        val left = column * tileWidth.toFloat()
        val top = row * (tileHeight + header).toFloat()
        canvas.drawText(label, left + 8f, top + 20f, text)
        val scale = minOf(tileWidth.toFloat() / bitmap.width, tileHeight.toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val dstLeft = left + (tileWidth - width) / 2f
        val dstTop = top + header + (tileHeight - height) / 2f
        canvas.drawBitmap(bitmap, null, RectF(dstLeft, dstTop, dstLeft + width, dstTop + height), null)
    }

    private fun recycle(captures: List<Capture>) = captures.forEach { capture ->
        capture.raw.recycle()
        capture.overlay.recycle()
    }

    private fun writeMotionCsv(file: File, series: MotionSeries) {
        val featureNames = series.features.flatMap { it.values.keys }.distinct().sorted()
        file.printWriter().use { out ->
            out.println((listOf("timestamp_ms", "validity", "confidence") + featureNames).joinToString(","))
            series.features.forEach { sample ->
                val row = mutableListOf(sample.timestamp.value.toString(), sample.validity.status.name, format(sample.validity.confidence))
                featureNames.forEach { name -> row += sample.values[name]?.let(::format) ?: "" }
                out.println(row.joinToString(","))
            }
        }
    }

    private fun writePhaseCsv(file: File, phases: PhaseSeries) {
        file.printWriter().use { out ->
            out.println("name,start_ms,end_exclusive_ms,repetition_index,confidence")
            phases.phases.forEach { phase ->
                out.println("${phase.name},${phase.start.value},${phase.endExclusive.value},${phase.repetitionIndex},${format(phase.confidence)}")
            }
        }
    }

    private fun writeAlignmentCsv(file: File, points: List<AlignmentPoint>) {
        file.printWriter().use { out ->
            out.println("source_ms,reference_ms,local_cost,confidence")
            points.forEach { point ->
                out.println("${point.sourceTimestamp.value},${point.referenceTimestamp.value},${format(point.localCost)},${format(point.confidence)}")
            }
        }
    }

    private fun writeCacheReport(
        directory: File,
        miss: AnalysisResult,
        hit: AnalysisResult,
        missElapsedMs: Double,
        hitElapsedMs: Double,
    ) {
        val report = buildJsonObject {
            put("schema_version", 1)
            put("stable_cache_key", miss.provenance.cacheKeyStableId.value)
            put("miss_status", miss.provenance.cacheStatus.name)
            put("hit_status", hit.provenance.cacheStatus.name)
            put("same_payload", miss.payload == hit.payload)
            put("reference_reused", hit.timings.none { it.stage == PipelineStageId.VIDEO_POSE_REFERENCE })
            put("miss_elapsed_ms", missElapsedMs)
            put("hit_elapsed_ms", hitElapsedMs)
            put("hit_stages", JsonArray(hit.timings.map { JsonPrimitive(it.stage.name) }))
        }
        File(directory, "cache_validation.json").writeText(json.encodeToString(JsonObject.serializer(), report))
    }

    private fun writeStageReport(directory: File, runId: String, result: AnalysisResult, peakRssBytes: Long) {
        val stages = buildJsonArray {
            result.timings.forEach { timing ->
                add(buildJsonObject {
                    put("name", timing.stage.name.lowercase())
                    put("duration_ms", timing.durationMs)
                    put("peak_rss_bytes", peakRssBytes)
                    put("status", "passed")
                })
            }
        }
        val report = buildJsonObject {
            put("schema_version", 1)
            put("run_id", runId)
            put("scenario_id", runId)
            put("total_duration_ms", result.timings.sumOf { it.durationMs })
            put("process_peak_rss_bytes", peakRssBytes)
            put("stages", stages)
        }
        File(directory, "stage_report.json").writeText(json.encodeToString(JsonObject.serializer(), report))
    }

    private fun writeBenchmarkReport(
        directory: File,
        runId: String,
        missElapsedMs: Double,
        hitDurations: List<Double>,
        peakRssBytes: Long,
    ) {
        val sortedHits = hitDurations.sorted()
        val median = sortedHits[sortedHits.size / 2]
        val p95 = sortedHits[((sortedHits.size - 1) * 95 + 99) / 100]
        val cases = buildJsonArray {
            add(buildJsonObject {
                put("id", "real_pair_cache_miss")
                put("repetitions", 1)
                put("median_ms", missElapsedMs)
                put("p95_ms", missElapsedMs)
                put("peak_rss_bytes", peakRssBytes)
                put("raw", buildJsonObject { put("elapsed_ms", JsonArray(listOf(JsonPrimitive(missElapsedMs)))) })
            })
            add(buildJsonObject {
                put("id", "real_pair_cache_hit")
                put("repetitions", hitDurations.size)
                put("median_ms", median)
                put("p95_ms", p95)
                put("peak_rss_bytes", peakRssBytes)
                put("raw", buildJsonObject { put("elapsed_ms", JsonArray(hitDurations.map(::JsonPrimitive))) })
            })
        }
        val report = buildJsonObject {
            put("schema_version", 1)
            put("suite", "senp_android_e2e")
            put("metadata", buildJsonObject {
                put("run_id", runId)
                put("device", "emulator")
                put("engine_version", EngineComposition.ENGINE_VERSION)
            })
            put("cases", cases)
        }
        File(directory, "benchmark_report.json").writeText(json.encodeToString(JsonObject.serializer(), report))
    }

    private fun verifyCancellation(
        sourceFile: File,
        sourceSha: Sha256,
        sampling: SamplingConfiguration,
        model: PoseModelConfiguration,
    ): JsonObject = runBlocking {
        val extractor = AndroidVideoPoseExtractor(this@ValidationActivity)
        val pending = async {
            extractor.extract(VideoRole.SOURCE, VideoSource(sourceFile.absolutePath, sourceSha), sampling, model)
        }
        delay(10)
        extractor.cancel()
        when (val result = pending.await()) {
            is StageResult.Failure -> {
                val failure = result.failure as? AnalysisFailure.VideoPose
                buildJsonObject {
                    put("ok", failure?.kind == VideoPoseFailureKind.CANCELLED)
                    put("failure", json.encodeToJsonElement(AnalysisFailure.serializer(), result.failure))
                }
            }
            is StageResult.Success -> buildJsonObject {
                put("ok", false)
                put("message", "analysis completed before cancellation was observed")
            }
        }
    }

    private fun verifyCorruptVideo(sampling: SamplingConfiguration, model: PoseModelConfiguration): JsonObject = runBlocking {
        val corrupt = File.createTempFile("senp-e2e-corrupt-", ".mp4", cacheDir).apply {
            writeBytes(ByteArray(4096) { index -> (index * 31).toByte() })
        }
        try {
            val extractor = AndroidVideoPoseExtractor(this@ValidationActivity)
            val result = extractor.extract(
                VideoRole.REFERENCE,
                VideoSource(corrupt.absolutePath, Sha256(sha256(corrupt))),
                sampling,
                model,
            )
            when (result) {
                is StageResult.Failure -> {
                    val failure = result.failure as? AnalysisFailure.VideoPose
                    buildJsonObject {
                        put("ok", failure?.kind == VideoPoseFailureKind.CORRUPT_VIDEO)
                        put("failure", json.encodeToJsonElement(AnalysisFailure.serializer(), result.failure))
                    }
                }
                is StageResult.Success -> buildJsonObject {
                    put("ok", false)
                    put("message", "corrupt video unexpectedly decoded")
                }
            }
        } finally {
            corrupt.delete()
        }
    }

    private fun readVmHwmBytes(): Long {
        val line = File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmHWM:") } }
            ?: return 0L
        val kib = Regex("VmHWM:\\s+(\\d+)\\s+kB").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
        return kib * 1024L
    }

    private fun requiredVideo(extra: String): File {
        val value = requireNotNull(intent.getStringExtra(extra)) { "Missing intent extra: $extra" }
        return File(value).also { file -> check(file.isFile) { "Missing video: ${file.absolutePath}" } }
    }

    private fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 92) {
        FileOutputStream(file).use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) }
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(80).ifEmpty { "pair" }
    private fun drawable(x: Double, y: Double): Boolean = x.isFinite() && y.isFinite() && x in -0.1..1.1 && y in -0.1..1.1
    private fun format(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

    private data class TimedOutcome(val outcome: AnalysisOutcome, val elapsedMs: Double)
    private data class DiagnosticStages(val source: VideoPoseExtraction, val reference: VideoPoseExtraction)
    private data class RequestedCapture(val index: Int, val timestampMs: Long)
    private data class Capture(val requestedMs: Long, val actualMs: Long, val raw: Bitmap, val overlay: Bitmap)

    companion object {
        private const val TAG = "SENP_E2E"
        private const val EXTRA_RUN_HEADLESS = "RUN_HEADLESS"
        private const val EXTRA_MODE = "mode"
        private const val MODE_SYNC_V2 = "sync_v2"
        private const val EXTRA_SOURCE_VIDEO = "source_video"
        private const val EXTRA_REFERENCE_VIDEO = "reference_video"
        private const val EXTRA_EXERCISE_PROFILE_VERSION = "exercise_profile_version"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_LONG_EDGE = "long_edge"
        private const val EXTRA_SYNC_V2_ONLY = "sync_v2_only"
        private const val MODEL_SHA256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
        private val CONNECTIONS = listOf(
            PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.RIGHT_SHOULDER,
            PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.LEFT_ELBOW,
            PoseLandmarkId.LEFT_ELBOW to PoseLandmarkId.LEFT_WRIST,
            PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkId.RIGHT_ELBOW,
            PoseLandmarkId.RIGHT_ELBOW to PoseLandmarkId.RIGHT_WRIST,
            PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.LEFT_HIP,
            PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkId.RIGHT_HIP,
            PoseLandmarkId.LEFT_HIP to PoseLandmarkId.RIGHT_HIP,
            PoseLandmarkId.LEFT_HIP to PoseLandmarkId.LEFT_KNEE,
            PoseLandmarkId.LEFT_KNEE to PoseLandmarkId.LEFT_ANKLE,
            PoseLandmarkId.RIGHT_HIP to PoseLandmarkId.RIGHT_KNEE,
            PoseLandmarkId.RIGHT_KNEE to PoseLandmarkId.RIGHT_ANKLE,
            PoseLandmarkId.LEFT_ANKLE to PoseLandmarkId.LEFT_HEEL,
            PoseLandmarkId.LEFT_HEEL to PoseLandmarkId.LEFT_FOOT_INDEX,
            PoseLandmarkId.RIGHT_ANKLE to PoseLandmarkId.RIGHT_HEEL,
            PoseLandmarkId.RIGHT_HEEL to PoseLandmarkId.RIGHT_FOOT_INDEX,
        )
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
