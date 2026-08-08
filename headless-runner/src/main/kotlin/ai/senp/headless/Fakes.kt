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
        // Several joints, grouped per frame so angle timestamps stay non-decreasing.
        val angles=poses.frames.flatMap{f->JOINTS.mapIndexed{i,joint->JointAngle(f.timestamp,joint,90.0+f.diagnosticFrameIndex+i*7,0.97)}}
        return StageResult.Success(MotionSeries(poses.role,poses.frames.map{FeatureSample(it.timestamp,mapOf("tempo_seconds" to it.timestamp.value/1000.0,"profile_signal" to signal),it.validity)},angles))
    }
    private companion object{ val JOINTS=listOf("left_knee","right_hip","left_shoulder","left_elbow") }
}
class FakePhaseDetector(private val failingRole:VideoRole?=null):PhaseDetector{
    override suspend fun detect(motion:MotionSeries,exerciseProfileVersion:String):StageResult<PhaseSeries>{
        if(motion.role==failingRole)return StageResult.Failure(AnalysisFailure.Phase(motion.role,"Synthetic phase failure")); val first=motion.features.first().timestamp;val last=motion.features.last().timestamp
        return StageResult.Success(PhaseSeries(motion.role,listOf(PhaseSegment("concentric:" + exerciseProfileVersion,first,TimestampMs(last.value+1),0,0.95))))
    }
}
class FakeAlignmentEngine(private val fail:Boolean=false):AlignmentEngine{
    override suspend fun align(sourceMotion:MotionSeries,sourcePhases:PhaseSeries,referenceMotion:MotionSeries,referencePhases:PhaseSeries,configuration:AnalysisConfiguration):StageResult<AlignmentAnalysis>{
        if(fail)return StageResult.Failure(AnalysisFailure.Alignment("Synthetic alignment failure")); val pts=sourceMotion.features.zip(referenceMotion.features).map{(s,r)->AlignmentPoint(s.timestamp,r.timestamp,0.05,0.94)}
        return StageResult.Success(AlignmentAnalysis(AlignmentResult("phase-aware-masked-dtw:" + configuration.exerciseProfileVersion,pts,0.94),problemWindows(pts)))
    }

    /**
     * A spread wide enough for downstream selection to have something to decide: mixed severity,
     * both certainties, and one window the aligner could not map onto the reference. A single
     * window leaves ranking, certainty preference, and the unmapped path all untested.
     */
    private fun problemWindows(pts:List<AlignmentPoint>):List<ProblemWindow>{
        val first=pts.first();val mid=pts[pts.size/2];val last=pts.last()
        fun window(from:AlignmentPoint,to:AlignmentPoint,label:String,metric:String,mean:Double,peak:Double,severity:Double,certainty:ProblemCertainty,mapped:Boolean=true)=
            ProblemWindow(from.sourceTimestamp,TimestampMs(to.sourceTimestamp.value+1),
                if(mapped)from.referenceTimestamp else null,if(mapped)TimestampMs(to.referenceTimestamp.value+1) else null,
                label,metric,mean,peak,severity,0.58,certainty)
        return listOf(
            window(first,mid,"knee_collapse","left_knee_degrees",16.0,24.0,0.82,ProblemCertainty.GENUINE),
            window(mid,last,"hip_shift","right_hip_degrees",11.0,19.0,0.66,ProblemCertainty.GENUINE),
            window(first,last,"shoulder_rise","left_shoulder_degrees",8.0,13.0,0.55,ProblemCertainty.GENUINE,mapped=false),
            // Deliberately the most severe window of the four. A big deviation the aligner cannot
            // stand behind is exactly the case that separates certainty-first ranking from
            // severity-only ranking, so ranking by severity alone would surface this first.
            window(first,last,"elbow_too_open","left_elbow_degrees",21.0,33.0,0.91,ProblemCertainty.UNCERTAIN),
        )
    }
}
class IncrementingMonotonicClock:MonotonicClock{private val v=AtomicLong(0);override fun elapsedRealtimeMs()=v.getAndIncrement()}
