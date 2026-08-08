package ai.senp.headless

import ai.senp.core.contracts.*
import ai.senp.core.pipeline.*
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FakeVideoPoseExtractor(private val frameCount:Int=4, private val failingRole:VideoRole?=null, private val delayMs:Long=0):VideoPoseExtractor {
    private val counts=VideoRole.entries.associateWith{AtomicInteger(0)}
    fun callsFor(role:VideoRole)=counts.getValue(role).get()
    override suspend fun extract(role:VideoRole,source:VideoSource,sampling:SamplingConfiguration,model:PoseModelConfiguration):StageResult<VideoPoseExtraction>{
        counts.getValue(role).incrementAndGet(); if(delayMs>0)delay(delayMs)
        if(role==failingRole)return StageResult.Failure(AnalysisFailure.VideoPose(role,VideoPoseFailureKind.CODEC,"Synthetic video/pose failure for " + source.uri))
        val frames=List(frameCount){i-> val ts=TimestampMs(i*1000L/sampling.targetFramesPerSecond); PoseFrame(ts,i.toLong(),PoseLandmarkId.entries.map{ id->PoseLandmark(id,ImageLandmark(id.index/32.0,ts.value/10000.0,-id.index/100.0),if(i%2==0)WorldLandmark(id.index/100.0,0.0,0.0)else null,0.99,0.98)},FrameValidity.Valid)}
        val duration=DurationMs(if(frameCount==0)0 else (frameCount*1000L+sampling.targetFramesPerSecond-1)/sampling.targetFramesPerSecond)
        return StageResult.Success(VideoPoseExtraction(role,duration,PoseSequence(role,frames),VideoPoseDiagnostics(frameCount*2,frameCount,frameCount,0,0,1_000_000,2_000_000,2,1)))
    }
}

class FakeMotionProcessor(private val failingRole:VideoRole?=null):MotionProcessor{
    override suspend fun process(poses:PoseSequence,normalizationVersion:String,exerciseProfileVersion:String):StageResult<MotionSeries>{
        if(poses.role==failingRole)return StageResult.Failure(AnalysisFailure.Motion(poses.role,"Synthetic motion failure"))
        val signal=(normalizationVersion.length+exerciseProfileVersion.length)/100.0
        return StageResult.Success(MotionSeries(poses.role,poses.frames.map{FeatureSample(it.timestamp,mapOf("tempo_seconds" to it.timestamp.value/1000.0,"profile_signal" to signal),it.validity)},poses.frames.map{JointAngle(it.timestamp,"left_elbow",90.0+it.diagnosticFrameIndex,0.97)}))
    }
}
class FakePhaseDetector(private val failingRole:VideoRole?=null):PhaseDetector{
    override suspend fun detect(motion:MotionSeries,exerciseProfileVersion:String):StageResult<PhaseSeries>{
        if(motion.role==failingRole)return StageResult.Failure(AnalysisFailure.Phase(motion.role,"Synthetic phase failure")); val first=motion.features.first().timestamp;val last=motion.features.last().timestamp
        return StageResult.Success(PhaseSeries(motion.role,listOf(PhaseSegment("concentric:" + exerciseProfileVersion,first,TimestampMs(last.value+1),0,0.95))))
    }
}
class FakeAlignmentEngine(private val fail:Boolean=false):AlignmentEngine{
    override suspend fun align(sourceMotion:MotionSeries,sourcePhases:PhaseSeries,referenceMotion:MotionSeries,referencePhases:PhaseSeries,configuration:AnalysisConfiguration):StageResult<AlignmentAnalysis>{
        if(fail)return StageResult.Failure(AnalysisFailure.Alignment("Synthetic alignment failure")); val pts=sourceMotion.features.zip(referenceMotion.features).map{(s,r)->AlignmentPoint(s.timestamp,r.timestamp,0.05,0.94)};val a=pts.first();val b=pts.last()
        return StageResult.Success(AlignmentAnalysis(AlignmentResult("phase-aware-masked-dtw:" + configuration.exerciseProfileVersion,pts,0.94),listOf(ProblemWindow(a.sourceTimestamp,TimestampMs(b.sourceTimestamp.value+1),a.referenceTimestamp,TimestampMs(b.referenceTimestamp.value+1),"elbow_too_open","left_elbow_degrees",12.0,18.0,0.42,0.58,ProblemCertainty.UNCERTAIN))))
    }
}
class IncrementingMonotonicClock:MonotonicClock{private val v=AtomicLong(0);override fun elapsedRealtimeMs()=v.getAndIncrement()}
