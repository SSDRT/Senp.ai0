package ai.senp.headless

import ai.senp.core.cache.InMemoryAnalysisCache
import ai.senp.core.contracts.*
import ai.senp.core.pipeline.VideoPoseExtractor
import kotlinx.coroutines.*
import kotlin.test.*

class AnalysisPipelineTest {
 @Test fun `end to end success is streaming and deterministic`()=runBlocking{
  val success=assertIs<AnalysisOutcome.Success>(samplePipeline(InMemoryAnalysisCache()).analyze(sampleRequest()))
  assertEquals(4,success.result.payload.sourceFrameCount);assertEquals(267,success.result.payload.sourceDuration.value)
  assertEquals(1,success.result.payload.sourceVideoPoseDiagnostics.peakInFlightFrames)
  assertEquals(listOf(PipelineStageId.VALIDATION,PipelineStageId.CACHE_READ,PipelineStageId.VIDEO_POSE_SOURCE,PipelineStageId.MOTION_SOURCE,PipelineStageId.PHASE_SOURCE,PipelineStageId.VIDEO_POSE_REFERENCE,PipelineStageId.MOTION_REFERENCE,PipelineStageId.PHASE_REFERENCE,PipelineStageId.ALIGNMENT,PipelineStageId.CACHE_WRITE),success.result.timings.map{it.stage})
 }
 @Test fun `typed adapter failure preserved`()=runBlocking{
  val out=samplePipeline(InMemoryAnalysisCache(),videoPoseExtractor=FakeVideoPoseExtractor(failingRole=VideoRole.SOURCE)).analyze(sampleRequest());val f=assertIs<AnalysisOutcome.Failure>(out);assertIs<AnalysisFailure.VideoPose>(f.failure);assertEquals(PipelineStageId.VIDEO_POSE_SOURCE,f.failure.stage)
 }
 @Test fun `typed cancellation preserved`()=runBlocking{
  val x=object:VideoPoseExtractor{override suspend fun extract(role:VideoRole,source:VideoSource,sampling:SamplingConfiguration,model:PoseModelConfiguration)=StageResult.Failure(AnalysisFailure.Cancelled(PipelineStageId.VIDEO_POSE_SOURCE))}
  assertIs<AnalysisFailure.Cancelled>(assertIs<AnalysisOutcome.Failure>(samplePipeline(InMemoryAnalysisCache(),videoPoseExtractor=x).analyze(sampleRequest())).failure)
 }
 @Test fun `coroutine cancellation propagates`()=runBlocking{
  val started=CompletableDeferred<Unit>();val x=object:VideoPoseExtractor{override suspend fun extract(role:VideoRole,source:VideoSource,sampling:SamplingConfiguration,model:PoseModelConfiguration):StageResult<VideoPoseExtraction>{started.complete(Unit);awaitCancellation()}}
  val cache=InMemoryAnalysisCache();val job=async{samplePipeline(cache,videoPoseExtractor=x).analyze(sampleRequest())};started.await();job.cancel();assertFailsWith<CancellationException>{job.await()};assertEquals(0,cache.size())
 }
 @Test fun `cache hit bypasses extractor`()=runBlocking{
  val cache=InMemoryAnalysisCache();val x=FakeVideoPoseExtractor();val p=samplePipeline(cache,videoPoseExtractor=x);assertIs<AnalysisOutcome.Success>(p.analyze(sampleRequest()));val second=assertIs<AnalysisOutcome.Success>(p.analyze(sampleRequest(requestId="two")));assertEquals(CacheStatus.HIT,second.result.provenance.cacheStatus);assertEquals(1,x.callsFor(VideoRole.SOURCE));assertEquals(1,x.callsFor(VideoRole.REFERENCE))
 }
 @Test fun `mismatched stage failure becomes contract failure`()=runBlocking{
  val x=object:VideoPoseExtractor{override suspend fun extract(role:VideoRole,source:VideoSource,sampling:SamplingConfiguration,model:PoseModelConfiguration)=StageResult.Failure(AnalysisFailure.Alignment("wrong"))};val u=assertIs<AnalysisFailure.Unexpected>(assertIs<AnalysisOutcome.Failure>(samplePipeline(InMemoryAnalysisCache(),videoPoseExtractor=x).analyze(sampleRequest())).failure);assertEquals(PipelineStageId.VIDEO_POSE_SOURCE,u.stage)
 }
}
