package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.core.pipeline.VideoPoseExtractor
import ai.senp.motion.*
import ai.senp.sync.v2.InMemorySynchronizationPoseCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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

    @Test fun highConfidenceWorldWristOutsideImageCannotCertifyTheCriticalTurnaround() {
        val ref=reference()
        val processor=session().newLiveProcessor(ref.profile,30.0,qualifiedRepReference=ref,repEvidenceFingerprint=fingerprint)
        val generic=session().newLiveProcessor(ref.profile,30.0)
        var output: LiveReferenceActionOutput?=null
        for(time in 0L..2000L step 33) {
            val p=pose(time,progress(time))
            val raw=if(progress(time)>=.85) p.copy(landmarks=p.landmarks.map { if(it.id==PoseLandmarkId.LEFT_WRIST) it.copy(image=it.image.copy(x=1.01)) else it }) else p
            val before=raw.copy()
            output=processor.update(p,640,480,fingerprint.poseModelSha256,raw)
            val baseline=generic.update(p,640,480,fingerprint.poseModelSha256,raw)
            assertEquals(baseline.estimate,output.estimate);assertEquals(baseline.precision,output.precision);assertEquals(before,raw)
        }
        assertEquals(0,output?.qualifiedReps?.counts?.qualified)
        assertTrue(requireNotNull(output?.qualifiedReps).counts.unassessable>0)
    }

    @Test fun clippedCriticalReferenceCannotCompileAndAllOutsideSourceCannotUseImageFallback() {
        val input=extraction()
        val clipped=input.copy(poses=input.poses.copy(frames=input.poses.frames.map { p -> p.copy(landmarks=p.landmarks.map { if(it.id==PoseLandmarkId.LEFT_WRIST) it.copy(image=it.image.copy(y=-.01)) else it }) }))
        assertIs<QualifiedRepCompilation.Unavailable>(compileQualifiedRepFromExtraction(clipped,"biceps_curl",fingerprint,QualifiedRepConfig()))
        val ref=reference();val processor=session().newLiveProcessor(ref.profile,30.0,qualifiedRepReference=ref,repEvidenceFingerprint=fingerprint)
        for(time in 0L..2000L step 33) {
            val p=pose(time,progress(time));val outside=p.copy(landmarks=p.landmarks.map { it.copy(image=it.image.copy(x=it.image.x+2)) })
            val result=requireNotNull(processor.update(p,640,480,fingerprint.poseModelSha256,outside).qualifiedReps)
            assertEquals(RepReason.MISSING_CRITICAL_EVIDENCE,result.unavailableReason);assertEquals(0,result.counts.qualified)
        }
    }

    @Test fun rawEndpointJumpsCannotBypassTheObservedPathRequirement() {
        val samples = listOf(0.0,0.0,0.0,1.0,1.0,0.0,0.0,0.0,0.0,0.0)
        for (requirePath in listOf(false,true)) {
            val config = QualifiedRepConfig(requireObservedPath = requirePath)
            val ref = assertIs<QualifiedRepCompilation.Ready>(compileQualifiedRepFromExtraction(extraction(),"biceps_curl",fingerprint,config)).reference
            val processor = session().newLiveProcessor(ref.profile,15.0,qualifiedRepReference = ref,repEvidenceFingerprint = fingerprint)
            val outputs = samples.mapIndexed { i,p -> pose(i*67L,p).let { processor.update(it,640,480,fingerprint.poseModelSha256,it) } }
            val last = requireNotNull(outputs.last().qualifiedReps)
            assertEquals(if (requirePath) 0 else 1,last.counts.qualified)
            if (requirePath) assertTrue(RepReason.INSUFFICIENT_PATH_EVIDENCE in outputs.flatMap { it.qualifiedReps?.finalizedAttempts.orEmpty() }.single().reasons)
        }
    }

    private val primaryModel = PoseModelConfiguration(Sha256(fingerprint.poseModelSha256))
    private val fallbackModel = PoseModelConfiguration(Sha256("c".repeat(64)),"pose-landmarker-heavy")
    private val request = ReferenceExtractionRequest(VideoSource("content://verified-reference",Sha256(fingerprint.referenceVideoSha256)),
        SamplingConfiguration(30,640),primaryModel)
    private fun weakExtraction() = extraction().let { it.copy(poses = it.poses.copy(frames = it.poses.frames.map { frame -> pose(frame.timestamp.value,0.0) })) }

    @Test fun usablePrimaryNeverExtractsOrSelectsFallback() = runBlocking {
        val result = session().prepareQualifiedReference(extraction(),fingerprint,request,"biceps_curl",QualifiedRepConfig(),fallbackModel)
        val ready = assertIs<QualifiedReferencePreparation.Ready>(result)
        assertEquals(fingerprint,ready.reference.fingerprint)
        assertEquals(listOf(null),ready.attempts.map { it.failure })
    }

    @Test fun fallbackReextractsOnlyTheSameReferenceAndRemainsBoundToItsOwnModel() = runBlocking {
        var calls = 0
        val engine = ReferenceActionSessionEngine(object : VideoPoseExtractor {
            override suspend fun extract(role: VideoRole,source: VideoSource,sampling: SamplingConfiguration,model: PoseModelConfiguration): StageResult<VideoPoseExtraction> {
                calls++
                assertEquals(VideoRole.REFERENCE,role);assertEquals(request.source,source)
                assertEquals(request.sampling,sampling);assertEquals(fallbackModel,model)
                return StageResult.Success(extraction())
            }
        },InMemorySynchronizationPoseCache())
        repeat(2) {
            val ready = assertIs<QualifiedReferencePreparation.Ready>(engine.prepareQualifiedReference(
                weakExtraction(),fingerprint,request,"biceps_curl",QualifiedRepConfig(),fallbackModel))
            assertEquals(2,ready.attempts.size);assertNotNull(ready.attempts.first().failure)
            assertNull(ready.attempts.last().failure)
            assertEquals(fingerprint.copy(poseModelSha256 = fallbackModel.modelSha256.value),ready.reference.fingerprint)
            assertEquals(fallbackModel,ready.request?.model)
            val processor = engine.newLiveProcessor(ready.reference.profile,30.0,qualifiedRepReference = ready.reference,
                repEvidenceFingerprint = ready.reference.fingerprint)
            var output: LiveReferenceActionOutput? = null
            for (time in 0L..2000L step 33) {
                val p = pose(time,progress(time))
                output = processor.update(p,640,480,fallbackModel.modelSha256.value,p)
            }
            assertEquals(1,output?.qualifiedReps?.counts?.qualified)
            val p = pose(2100,0.0)
            assertEquals(RepReason.MODEL_MISMATCH,processor.update(p,640,480,primaryModel.modelSha256.value,p).qualifiedReps?.unavailableReason)
        }
        assertEquals(1,calls,"Identical fallback reference settings must share only their own model cache")
    }

    @Test fun unsupportedExerciseOrMismatchedUploadCannotStartFallbackExtraction(): Unit = runBlocking {
        assertIs<QualifiedReferencePreparation.Unavailable>(session().prepareQualifiedReference(
            extraction(),fingerprint,request,"unknown-exercise",QualifiedRepConfig(),fallbackModel))
        val result = assertIs<QualifiedReferencePreparation.Unavailable>(session().prepareQualifiedReference(
            weakExtraction(),fingerprint,request.copy(source = VideoSource("content://other",Sha256("d".repeat(64)))),
            "biceps_curl",QualifiedRepConfig(),fallbackModel))
        assertEquals(RepReferenceFailure.MISSING_PROVENANCE,result.attempts.single().failure)
        assertIs<QualifiedReferencePreparation.Unavailable>(session().prepareQualifiedReference(
            weakExtraction(),fingerprint,null,"biceps_curl",QualifiedRepConfig(),fallbackModel))
    }

    @Test fun missingPackagedFallbackRetainsBothFailuresWithoutRelaxingReferenceRequirements() = runBlocking {
        val engine = ReferenceActionSessionEngine(object : VideoPoseExtractor {
            override suspend fun extract(role: VideoRole,source: VideoSource,sampling: SamplingConfiguration,model: PoseModelConfiguration): StageResult<VideoPoseExtraction> =
                StageResult.Failure(AnalysisFailure.VideoPose(role,VideoPoseFailureKind.MODEL_LOAD,"Heavy asset absent"))
        },InMemorySynchronizationPoseCache())
        val result = assertIs<QualifiedReferencePreparation.Unavailable>(engine.prepareQualifiedReference(
            weakExtraction(),fingerprint,request,"biceps_curl",QualifiedRepConfig(),fallbackModel))
        assertEquals(2,result.attempts.size)
        assertTrue(result.detail.contains("Heavy asset absent"))
        assertTrue(result.attempts.all { it.failure != null })
    }

    @Test fun lifecycleCancellationCannotProduceAnUnavailableOrUsableFallback(): Unit = runBlocking {
        val engine = ReferenceActionSessionEngine(object : VideoPoseExtractor {
            override suspend fun extract(role: VideoRole,source: VideoSource,sampling: SamplingConfiguration,model: PoseModelConfiguration): StageResult<VideoPoseExtraction> =
                throw CancellationException("Session destroyed")
        },InMemorySynchronizationPoseCache())
        assertFailsWith<CancellationException> { engine.prepareQualifiedReference(
            weakExtraction(),fingerprint,request,"biceps_curl",QualifiedRepConfig(),fallbackModel) }
    }
}
