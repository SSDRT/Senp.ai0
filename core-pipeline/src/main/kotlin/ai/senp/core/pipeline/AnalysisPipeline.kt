package ai.senp.core.pipeline

import ai.senp.core.contracts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AnalysisPipeline(
    private val videoPoseExtractor: VideoPoseExtractor,
    private val motionProcessor: MotionProcessor,
    private val phaseDetector: PhaseDetector,
    private val alignmentEngine: AlignmentEngine,
    private val cache: AnalysisCache,
    private val monotonicClock: MonotonicClock,
    private val wallClock: WallClock,
    private val engineVersion: String,
) {
    init { require(engineVersion.isNotBlank()) }

    suspend fun analyze(request: AnalysisRequest): AnalysisOutcome {
        val timings=mutableListOf<StageTiming>(); var active=PipelineStageId.VALIDATION
        suspend fun <T> stage(id:PipelineStageId, block:suspend()->StageResult<T>):T { active=id; return executeStage(id,timings,block) }
        return try {
            stage(PipelineStageId.VALIDATION){ validate(request) }
            val key=CacheKey.from(request)
            when(val lookup=stage(PipelineStageId.CACHE_READ){cache.lookup(key)}) {
                is CacheLookup.Hit -> AnalysisOutcome.Success(buildResult(request,key,lookup.analysis,CacheStatus.HIT,wallClock.nowEpochMs(),timings))
                CacheLookup.Miss -> {
                    val source=stage(PipelineStageId.VIDEO_POSE_SOURCE){ extractValidated(VideoRole.SOURCE,request) }
                    val sourceMotion=stage(PipelineStageId.MOTION_SOURCE){ motionValidated(source.poses,request) }
                    val sourcePhases=stage(PipelineStageId.PHASE_SOURCE){ phasesValidated(sourceMotion,request) }
                    val reference=stage(PipelineStageId.VIDEO_POSE_REFERENCE){ extractValidated(VideoRole.REFERENCE,request) }
                    val referenceMotion=stage(PipelineStageId.MOTION_REFERENCE){ motionValidated(reference.poses,request) }
                    val referencePhases=stage(PipelineStageId.PHASE_REFERENCE){ phasesValidated(referenceMotion,request) }
                    val aligned=stage(PipelineStageId.ALIGNMENT){ alignmentValidated(sourceMotion,sourcePhases,referenceMotion,referencePhases,request) }
                    val payload=AnalysisPayload(source.duration,reference.duration,source.poses.frames.size,reference.poses.frames.size,source.diagnostics,reference.diagnostics,aligned.alignment.copy(points=aligned.alignment.points.toList()),aligned.problems.toList())
                    val now=wallClock.nowEpochMs(); val cached=CachedAnalysis(payload,now,engineVersion)
                    stage(PipelineStageId.CACHE_WRITE){cache.store(key,cached)}
                    AnalysisOutcome.Success(buildResult(request,key,cached,CacheStatus.MISS,now,timings))
                }
            }
        } catch(c:CancellationException){ throw c }
          catch(a:StageAbort){ AnalysisOutcome.Failure(a.failure,timings.toList()) }
          catch(e:Exception){ AnalysisOutcome.Failure(AnalysisFailure.Unexpected(active,e::class.qualifiedName?:"unknown",e.message?:"Unexpected pipeline failure"),timings.toList()) }
    }

    private suspend fun <T> executeStage(id:PipelineStageId,timings:MutableList<StageTiming>,block:suspend()->StageResult<T>):T {
        currentCoroutineContext().ensureActive(); val start=monotonicClock.elapsedRealtimeMs()
        try { return when(val r=block()) { is StageResult.Success->r.value; is StageResult.Failure->{ if(r.failure.stage!=id) throw StageAbort(AnalysisFailure.Unexpected(id,r.failure::class.qualifiedName?:"unknown","Stage " + id + " received failure declared for " + r.failure.stage + ": " + r.failure.message)); throw StageAbort(r.failure) } } }
        catch(c:CancellationException){throw c} catch(a:StageAbort){throw a} catch(e:Exception){throw StageAbort(AnalysisFailure.Unexpected(id,e::class.qualifiedName?:"unknown",e.message?:"Unexpected stage failure"))}
        finally { val end=monotonicClock.elapsedRealtimeMs(); timings+=StageTiming(id,start.coerceAtLeast(0),(end-start).coerceAtLeast(0)) }
    }

    private fun validate(r:AnalysisRequest):StageResult<Unit> = when { r.requestId.isBlank()->StageResult.Failure(AnalysisFailure.InvalidRequest("requestId must not be blank")); r.requestId.length>256->StageResult.Failure(AnalysisFailure.InvalidRequest("requestId must be at most 256 characters")); else->StageResult.Success(Unit) }

    private suspend fun extractValidated(role:VideoRole,r:AnalysisRequest):StageResult<VideoPoseExtraction> {
        val src=if(role==VideoRole.SOURCE) r.source else r.reference
        return when(val result=videoPoseExtractor.extract(role,src,r.configuration.sampling,r.configuration.model)) {
            is StageResult.Failure->result
            is StageResult.Success->{ val x=result.value; when { x.role!=role->StageResult.Failure(AnalysisFailure.VideoPose(role,VideoPoseFailureKind.INFERENCE,"extractor returned " + x.role + " for " + role)); x.poses.frames.isEmpty()->StageResult.Failure(AnalysisFailure.VideoPose(role,VideoPoseFailureKind.INFERENCE,"extractor returned no sampled pose frames")); else->StageResult.Success(x) } }
        }
    }

    private suspend fun motionValidated(p:PoseSequence,r:AnalysisRequest)=when(val x=motionProcessor.process(p,r.configuration.normalizationVersion,r.configuration.exerciseProfileVersion)){ is StageResult.Failure->x; is StageResult.Success->if(x.value.role==p.role)x else StageResult.Failure(AnalysisFailure.Motion(p.role,"motion processor returned wrong role")) }
    private suspend fun phasesValidated(m:MotionSeries,r:AnalysisRequest)=when(val x=phaseDetector.detect(m,r.configuration.exerciseProfileVersion)){ is StageResult.Failure->x; is StageResult.Success->if(x.value.role==m.role)x else StageResult.Failure(AnalysisFailure.Phase(m.role,"phase detector returned wrong role")) }
    private suspend fun alignmentValidated(sm:MotionSeries,sp:PhaseSeries,rm:MotionSeries,rp:PhaseSeries,r:AnalysisRequest)=when(val x=alignmentEngine.align(sm,sp,rm,rp,r.configuration)){ is StageResult.Failure->x; is StageResult.Success->if(x.value.alignment.points.isNotEmpty())x else StageResult.Failure(AnalysisFailure.Alignment("alignment engine returned an empty path")) }

    private fun buildResult(r:AnalysisRequest,key:CacheKey,c:CachedAnalysis,status:CacheStatus,served:TimestampMs,t:List<StageTiming>)=AnalysisResult(requestId=r.requestId,requestedAtEpochMs=r.requestedAtEpochMs,payload=c.payload,timings=t.toList(),provenance=AnalysisProvenance(key,key.stableId(),status,c.computedAtEpochMs,served,c.producerEngineVersion,engineVersion))
    private class StageAbort(val failure:AnalysisFailure):RuntimeException(null,null,false,false)
}
