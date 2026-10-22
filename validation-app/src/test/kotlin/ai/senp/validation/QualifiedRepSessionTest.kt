package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.core.pipeline.VideoPoseExtractor
import ai.senp.motion.*
import ai.senp.sync.v2.InMemorySynchronizationPoseCache
import kotlin.math.*
import kotlin.test.*

class QualifiedRepSessionTest {
    private val fingerprint = RepEvidenceFingerprint("a".repeat(64),"b".repeat(64))
    private fun pose(time: Long, progress: Double, armDrift: Double = 0.0): PoseFrame {
        val coordinates = mutableMapOf<PoseLandmarkId,List<Double>>()
        fun set(id: PoseLandmarkId,x: Double,y: Double,z: Double = 0.0) { coordinates[id] = listOf(x,y,z) }
        for ((left,sign) in listOf(true to -1.0,false to 1.0)) {
            val shoulder = if (left) PoseLandmarkId.LEFT_SHOULDER else PoseLandmarkId.RIGHT_SHOULDER
            val elbow = if (left) PoseLandmarkId.LEFT_ELBOW else PoseLandmarkId.RIGHT_ELBOW
            val wrist = if (left) PoseLandmarkId.LEFT_WRIST else PoseLandmarkId.RIGHT_WRIST
            set(shoulder,sign*0.2,-0.5)
            val phi = Math.toRadians(armDrift);val ux = sign*sin(phi)*0.35;val uy = cos(phi)*0.35
            val ex = sign*0.2+ux;val ey = -0.5+uy;set(elbow,ex,ey)
            val theta = Math.toRadians(sign*(170-100*progress));val vx = -ux/0.35;val vy = -uy/0.35
            set(wrist,ex+(vx*cos(theta)-vy*sin(theta))*0.3,ey+(vx*sin(theta)+vy*cos(theta))*0.3)
            set(if (left) PoseLandmarkId.LEFT_HIP else PoseLandmarkId.RIGHT_HIP,sign*0.15,0.0)
            set(if (left) PoseLandmarkId.LEFT_KNEE else PoseLandmarkId.RIGHT_KNEE,sign*0.15,0.45)
            set(if (left) PoseLandmarkId.LEFT_ANKLE else PoseLandmarkId.RIGHT_ANKLE,sign*0.15,0.9)
            set(if (left) PoseLandmarkId.LEFT_FOOT_INDEX else PoseLandmarkId.RIGHT_FOOT_INDEX,sign*0.15,0.95,0.1)
        }
        return PoseFrame(TimestampMs(time),time/33,PoseLandmarkId.entries.map { id ->
            val p = coordinates[id] ?: listOf(0.0,-0.7,0.0)
            PoseLandmark(id,ImageLandmark(p[0]*0.4+0.5,p[1]*0.4+0.4,p[2]*0.4),WorldLandmark(p[0],p[1],p[2]),0.99,0.99)
        },FrameValidity.Valid)
    }
    private fun progress(t: Long): Double = when {
        t < 300 -> 0.0
        t < 900 -> (t-300)/600.0
        t < 1100 -> 1.0
        t < 1700 -> (1700-t)/600.0
        else -> 0.0
    }
    private fun extraction(): VideoPoseExtraction {
        val frames = (0..3).flatMap { rep -> (0L..2000L step 33).map { pose(rep*2100L+it,progress(it)) } }
        return VideoPoseExtraction(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),PoseSequence(VideoRole.REFERENCE,frames),
            VideoPoseDiagnostics(frames.size,frames.size,frames.size,0,0,0,0,1,1))
    }
    private fun session(): ReferenceActionSessionEngine = ReferenceActionSessionEngine(object : VideoPoseExtractor {
        override suspend fun extract(role: VideoRole,source: VideoSource,sampling: SamplingConfiguration,model: PoseModelConfiguration): StageResult<VideoPoseExtraction> =
            error("Test supplies original synthetic kinematic observations")
    },InMemorySynchronizationPoseCache())
    private fun reference(): QualifiedRepReference = assertIs<QualifiedRepCompilation.Ready>(
        compileQualifiedRepFromExtraction(extraction(),"biceps_curl",fingerprint,QualifiedRepConfig())).reference

    @Test fun rawPoseAdapterReferenceCalibrationAndLiveAcceptanceAreConnected() {
        val ref = reference()
        val processor = session().newLiveProcessor(ref.profile,30.0,exerciseId = "biceps_curl",qualifiedRepReference = ref,repEvidenceFingerprint = fingerprint)
        var output: LiveReferenceActionOutput? = null
        for (time in 0L..2000L step 33) {
            val p = pose(time,progress(time))
            output = processor.update(p,640,480,fingerprint.poseModelSha256,p)
        }
        assertEquals(1,output?.qualifiedReps?.counts?.qualified)
        assertEquals(0,output?.qualifiedReps?.counts?.rejected)
        assertFalse(requireNotNull(output?.qualifiedReps).releaseQualified)
        processor.reset()
        val p = pose(0,0.0)
        assertEquals(0,processor.update(p,640,480,fingerprint.poseModelSha256,p).qualifiedReps?.counts?.finalized)
    }
    @Test fun clearlyWrongArmPathCannotCountEvenAfterTheArmReturnsToReferenceAlignment() {
        val ref = reference()
        val processor = session().newLiveProcessor(ref.profile,30.0,qualifiedRepReference = ref,repEvidenceFingerprint = fingerprint)
        var result: LiveReferenceActionOutput? = null
        var sawEarlyDisqualification = false
        for (time in 0L..2000L step 33) {
            val p = pose(time,progress(time),if (time in 500..1000) 60.0 else 0.0)
            result = processor.update(p,640,480,fingerprint.poseModelSha256,p)
            sawEarlyDisqualification = sawEarlyDisqualification || result.qualifiedReps?.activeDisqualified == true
        }
        assertTrue(sawEarlyDisqualification)
        assertEquals(0,result?.qualifiedReps?.counts?.qualified)
        assertEquals(1,result?.qualifiedReps?.counts?.rejected)
    }
    @Test fun qualificationIsOptInAndRawEvidenceAndMatchingProvenanceAreRequired() {
        val ref = reference();val p = pose(0,0.0)
        assertNull(session().newLiveProcessor(ref.profile,30.0).update(p).qualifiedReps)
        assertFailsWith<IllegalArgumentException> { session().newLiveProcessor(ref.profile,30.0,qualifiedRepReference = ref) }
        val processor = session().newLiveProcessor(ref.profile,30.0,qualifiedRepReference = ref,repEvidenceFingerprint = fingerprint)
        assertEquals(RepReason.MODEL_MISMATCH,processor.update(p).qualifiedReps?.unavailableReason)
        assertEquals(RepReason.MISSING_CRITICAL_EVIDENCE,processor.update(p.copy(timestamp = TimestampMs(33)),poseModelSha256 = fingerprint.poseModelSha256).qualifiedReps?.unavailableReason)
        assertFailsWith<IllegalArgumentException> { PreparedReferenceAction(ref.profile,"c".repeat(64),30.0,repEvidenceFingerprint = fingerprint) }
    }
    @Test fun repairedCoordinatesCannotCertifyTheCriticalTurnaround() {
        val ref = reference()
        val processor = session().newLiveProcessor(ref.profile,30.0,qualifiedRepReference = ref,repEvidenceFingerprint = fingerprint)
        var output: LiveReferenceActionOutput? = null
        for (time in 0L..2000L step 33) {
            val p = pose(time,progress(time))
            val raw = if (progress(time) >= 0.85) p.copy(validity = FrameValidity(FrameValidityStatus.REPAIRED,0.99,setOf(FrameValidityReason.SHORT_GAP_INTERPOLATION))) else p
            output = processor.update(p,640,480,fingerprint.poseModelSha256,raw)
        }
        assertEquals(0,output?.qualifiedReps?.counts?.qualified)
        assertEquals(1,output?.qualifiedReps?.counts?.unassessable)
    }
}
