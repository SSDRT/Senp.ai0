package ai.senp.core.pipeline

import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.AnalysisOutcome
import ai.senp.core.contracts.AnalysisPayload
import ai.senp.core.contracts.AnalysisProvenance
import ai.senp.core.contracts.AnalysisRequest
import ai.senp.core.contracts.AnalysisResult
import ai.senp.core.contracts.CacheKey
import ai.senp.core.contracts.CacheLookup
import ai.senp.core.contracts.CacheStatus
import ai.senp.core.contracts.CachedAnalysis
import ai.senp.core.contracts.DecodedVideo
import ai.senp.core.contracts.MotionSeries
import ai.senp.core.contracts.PhaseSeries
import ai.senp.core.contracts.PipelineStageId
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.StageTiming
import ai.senp.core.contracts.VideoRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AnalysisPipeline(
    private val decoder: VideoDecoder,
    private val poseEstimator: PoseEstimator,
    private val motionProcessor: MotionProcessor,
    private val phaseDetector: PhaseDetector,
    private val alignmentEngine: AlignmentEngine,
    private val cache: AnalysisCache,
    private val monotonicClock: MonotonicClock,
    private val wallClock: WallClock,
    private val engineVersion: String,
) {
    init {
        require(engineVersion.isNotBlank()) { "engine version must not be blank" }
    }

    suspend fun analyze(request: AnalysisRequest): AnalysisOutcome {
        val timings = mutableListOf<StageTiming>()
        var activeStage = PipelineStageId.VALIDATION

        suspend fun <T> stage(
            id: PipelineStageId,
            block: suspend () -> StageResult<T>,
        ): T {
            activeStage = id
            return executeStage(id, timings, block)
        }

        return try {
            stage(PipelineStageId.VALIDATION) { validate(request) }
            val key = CacheKey.from(request)

            when (val lookup = stage(PipelineStageId.CACHE_READ) { cache.lookup(key) }) {
                is CacheLookup.Hit -> {
                    val servedAt = wallClock.nowEpochMs()
                    AnalysisOutcome.Success(
                        buildResult(
                            request = request,
                            key = key,
                            cached = lookup.analysis,
                            cacheStatus = CacheStatus.HIT,
                            servedAt = servedAt,
                            timings = timings,
                        ),
                    )
                }

                CacheLookup.Miss -> {
                    val sourceDecoded = stage(PipelineStageId.DECODE_SOURCE) {
                        decodeValidated(VideoRole.SOURCE, request)
                    }
                    val sourcePoses = stage(PipelineStageId.POSE_SOURCE) {
                        estimateValidated(VideoRole.SOURCE, sourceDecoded, request)
                    }
                    val sourceMotion = stage(PipelineStageId.MOTION_SOURCE) {
                        motionValidated(sourcePoses, request)
                    }
                    val sourcePhases = stage(PipelineStageId.PHASE_SOURCE) {
                        phasesValidated(sourceMotion, request)
                    }

                    val referenceDecoded = stage(PipelineStageId.DECODE_REFERENCE) {
                        decodeValidated(VideoRole.REFERENCE, request)
                    }
                    val referencePoses = stage(PipelineStageId.POSE_REFERENCE) {
                        estimateValidated(VideoRole.REFERENCE, referenceDecoded, request)
                    }
                    val referenceMotion = stage(PipelineStageId.MOTION_REFERENCE) {
                        motionValidated(referencePoses, request)
                    }
                    val referencePhases = stage(PipelineStageId.PHASE_REFERENCE) {
                        phasesValidated(referenceMotion, request)
                    }

                    val alignment = stage(PipelineStageId.ALIGNMENT) {
                        alignmentValidated(
                            sourceMotion = sourceMotion,
                            sourcePhases = sourcePhases,
                            referenceMotion = referenceMotion,
                            referencePhases = referencePhases,
                            request = request,
                        )
                    }

                    val payload = AnalysisPayload(
                        sourceDuration = sourceDecoded.duration,
                        referenceDuration = referenceDecoded.duration,
                        sourceFrameCount = sourcePoses.frames.size,
                        referenceFrameCount = referencePoses.frames.size,
                        alignment = alignment.alignment.copy(points = alignment.alignment.points.toList()),
                        problems = alignment.problems.toList(),
                    )
                    val computedAt = wallClock.nowEpochMs()
                    val cached = CachedAnalysis(
                        payload = payload,
                        computedAtEpochMs = computedAt,
                        producerEngineVersion = engineVersion,
                    )

                    stage(PipelineStageId.CACHE_WRITE) { cache.store(key, cached) }
                    AnalysisOutcome.Success(
                        buildResult(
                            request = request,
                            key = key,
                            cached = cached,
                            cacheStatus = CacheStatus.MISS,
                            servedAt = computedAt,
                            timings = timings,
                        ),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (abort: StageAbort) {
            AnalysisOutcome.Failure(abort.failure, timings.toList())
        } catch (error: Exception) {
            AnalysisOutcome.Failure(
                failure = AnalysisFailure.Unexpected(
                    stage = activeStage,
                    exceptionType = error::class.qualifiedName ?: error::class.simpleName ?: "unknown",
                    message = error.message ?: "Unexpected pipeline failure",
                ),
                timings = timings.toList(),
            )
        }
    }

    private suspend fun <T> executeStage(
        id: PipelineStageId,
        timings: MutableList<StageTiming>,
        block: suspend () -> StageResult<T>,
    ): T {
        currentCoroutineContext().ensureActive()
        val startedAt = monotonicClock.elapsedRealtimeMs()
        return try {
            when (val result = block()) {
                is StageResult.Success -> result.value
                is StageResult.Failure -> {
                    val failure = result.failure
                    if (failure.stage != id) {
                        throw StageAbort(
                            AnalysisFailure.Unexpected(
                                stage = id,
                                exceptionType = failure::class.qualifiedName
                                    ?: failure::class.simpleName
                                    ?: "unknown",
                                message = "Stage $id received a failure declared for ${failure.stage}: ${failure.message}",
                            ),
                        )
                    }
                    throw StageAbort(failure)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (abort: StageAbort) {
            throw abort
        } catch (error: Exception) {
            throw StageAbort(
                AnalysisFailure.Unexpected(
                    stage = id,
                    exceptionType = error::class.qualifiedName ?: error::class.simpleName ?: "unknown",
                    message = error.message ?: "Unexpected stage failure",
                ),
            )
        } finally {
            val finishedAt = monotonicClock.elapsedRealtimeMs()
            timings += StageTiming(
                stage = id,
                startedAtElapsedRealtimeMs = startedAt.coerceAtLeast(0),
                durationMs = (finishedAt - startedAt).coerceAtLeast(0),
            )
        }
    }

    private fun validate(request: AnalysisRequest): StageResult<Unit> {
        if (request.requestId.isBlank()) {
            return StageResult.Failure(AnalysisFailure.InvalidRequest("requestId must not be blank"))
        }
        if (request.requestId.length > 256) {
            return StageResult.Failure(AnalysisFailure.InvalidRequest("requestId must be at most 256 characters"))
        }
        return StageResult.Success(Unit)
    }

    private suspend fun decodeValidated(
        role: VideoRole,
        request: AnalysisRequest,
    ): StageResult<DecodedVideo> {
        val source = when (role) {
            VideoRole.SOURCE -> request.source
            VideoRole.REFERENCE -> request.reference
        }
        return when (val result = decoder.decode(role, source, request.configuration.sampling)) {
            is StageResult.Failure -> result
            is StageResult.Success -> {
                val video = result.value
                when {
                    video.role != role -> StageResult.Failure(
                        AnalysisFailure.Decode(role, "decoder returned ${video.role} for $role input"),
                    )

                    video.frames.isEmpty() -> StageResult.Failure(
                        AnalysisFailure.Decode(role, "decoder returned no sampled frames"),
                    )

                    else -> StageResult.Success(video)
                }
            }
        }
    }

    private suspend fun estimateValidated(
        role: VideoRole,
        video: DecodedVideo,
        request: AnalysisRequest,
    ): StageResult<PoseSequence> = when (
        val result = poseEstimator.estimate(role, video, request.configuration.model)
    ) {
        is StageResult.Failure -> result
        is StageResult.Success -> {
            val poses = result.value
            when {
                poses.role != role -> StageResult.Failure(
                    AnalysisFailure.Pose(role, "pose estimator returned ${poses.role} for $role input"),
                )

                poses.frames.size != video.frames.size -> StageResult.Failure(
                    AnalysisFailure.Pose(
                        role,
                        "pose estimator returned ${poses.frames.size} frames for ${video.frames.size} decoded frames",
                    ),
                )

                poses.frames.map { it.timestamp } != video.frames.map { it.timestamp } -> StageResult.Failure(
                    AnalysisFailure.Pose(role, "pose timestamps do not match decoded timestamps"),
                )

                else -> StageResult.Success(poses)
            }
        }
    }

    private suspend fun motionValidated(
        poses: PoseSequence,
        request: AnalysisRequest,
    ): StageResult<MotionSeries> = when (
        val result = motionProcessor.process(
            poses = poses,
            normalizationVersion = request.configuration.normalizationVersion,
            exerciseProfileVersion = request.configuration.exerciseProfileVersion,
        )
    ) {
        is StageResult.Failure -> result
        is StageResult.Success -> {
            val motion = result.value
            if (motion.role != poses.role) {
                StageResult.Failure(
                    AnalysisFailure.Motion(poses.role, "motion processor returned ${motion.role} for ${poses.role} input"),
                )
            } else {
                StageResult.Success(motion)
            }
        }
    }

    private suspend fun phasesValidated(
        motion: MotionSeries,
        request: AnalysisRequest,
    ): StageResult<PhaseSeries> = when (
        val result = phaseDetector.detect(motion, request.configuration.exerciseProfileVersion)
    ) {
        is StageResult.Failure -> result
        is StageResult.Success -> {
            val phases = result.value
            if (phases.role != motion.role) {
                StageResult.Failure(
                    AnalysisFailure.Phase(motion.role, "phase detector returned ${phases.role} for ${motion.role} input"),
                )
            } else {
                StageResult.Success(phases)
            }
        }
    }

    private suspend fun alignmentValidated(
        sourceMotion: MotionSeries,
        sourcePhases: PhaseSeries,
        referenceMotion: MotionSeries,
        referencePhases: PhaseSeries,
        request: AnalysisRequest,
    ): StageResult<AlignmentAnalysis> = when (
        val result = alignmentEngine.align(
            sourceMotion = sourceMotion,
            sourcePhases = sourcePhases,
            referenceMotion = referenceMotion,
            referencePhases = referencePhases,
            configuration = request.configuration,
        )
    ) {
        is StageResult.Failure -> result
        is StageResult.Success -> {
            if (result.value.alignment.points.isEmpty()) {
                StageResult.Failure(AnalysisFailure.Alignment("alignment engine returned an empty path"))
            } else {
                StageResult.Success(result.value)
            }
        }
    }

    private fun buildResult(
        request: AnalysisRequest,
        key: CacheKey,
        cached: CachedAnalysis,
        cacheStatus: CacheStatus,
        servedAt: ai.senp.core.contracts.TimestampMs,
        timings: List<StageTiming>,
    ): AnalysisResult = AnalysisResult(
        requestId = request.requestId,
        requestedAtEpochMs = request.requestedAtEpochMs,
        payload = cached.payload,
        timings = timings.toList(),
        provenance = AnalysisProvenance(
            cacheKey = key,
            cacheKeyStableId = key.stableId(),
            cacheStatus = cacheStatus,
            computedAtEpochMs = cached.computedAtEpochMs,
            servedAtEpochMs = servedAt,
            producerEngineVersion = cached.producerEngineVersion,
            servingEngineVersion = engineVersion,
        ),
    )

    private class StageAbort(
        val failure: AnalysisFailure,
    ) : RuntimeException(null, null, false, false)
}
