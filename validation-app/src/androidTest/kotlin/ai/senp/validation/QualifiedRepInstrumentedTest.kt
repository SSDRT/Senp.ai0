package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.motion.*
import ai.senp.core.pipeline.VideoPoseExtractor
import ai.senp.sync.v2.InMemorySynchronizationPoseCache
import android.util.Log
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Original real-video landmarks exercise packaged Kotlin, adapters and live composition. Not fault ground truth. */
@RunWith(AndroidJUnit4::class)
class QualifiedRepInstrumentedTest {
    @Test fun originalSourceVideoLandmarksReproduceTheJvmAcceptedRepResultsOnAndroid() {
        replay("qualified-rep-native.json.gz","native-parity.json")
    }
    @Test fun realHeavyOnlyReferenceSelectsFallbackAndReproducesThreeAcceptedOutputs() {
        replay("qualified-rep-heavy-native.json.gz","heavy-native-parity.json")
    }
    @Test fun repeatedObservedRangeRecoversTheLeftPressReferenceWithTheOriginalSelfReplayGate() {
        replay("qualified-rep-heavy-native.json.gz","range-recovery-native-parity.json","qualified-rep-envelope-expectations.json")
    }
    @Test fun robustFitRecoversTheCompleteReferenceAfterBothEstablishedModelFitsFail() {
        replay("qualified-rep-robust-native.json.gz","robust-fit-native-parity.json")
    }
    @Test fun liveReferenceCameraUsesTheSelectedHeavyModelAcrossPauseAndResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("This camera test requires -PincludeHeavyPoseModel=true",context.assets.list("")?.contains("pose_landmarker_heavy.task") == true)
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("qualified-rep-heavy-native.json.gz"))
        val fixture = GZIPInputStream(stream).bufferedReader().use { JSONObject(it.readText()) }
        val reference = fixture.getJSONObject("videos").getJSONObject("reference")
        val sourceFrames = frames(reference.getJSONArray("frames"))
        val extraction = VideoPoseExtraction(VideoRole.REFERENCE,DurationMs(sourceFrames.last().timestamp.value+1),PoseSequence(VideoRole.REFERENCE,sourceFrames),
            VideoPoseDiagnostics(sourceFrames.size,sourceFrames.size,sourceFrames.size,0,0,0,0,1,1))
        val identity = RepEvidenceFingerprint(reference.getString("source_video_sha256"),reference.getString("model_sha256"))
        val rep = (compileQualifiedRepFromExtraction(extraction,"overhead_press",identity,QualifiedRepConfig(side = RepObservedSide.RIGHT)) as QualifiedRepCompilation.Ready).reference
        ReferenceActionProfileStore.set(PreparedReferenceAction(rep.profile,identity.referenceVideoSha256,15.0,
            repEvidenceFingerprint = identity,qualifiedRepReference = rep,repSource = extraction))
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").close()
        try {
            val intent = Intent(context,LiveReferenceActionActivity::class.java).putExtra(LiveReferenceActionActivity.EXTRA_REFERENCE_SHA256,identity.referenceVideoSha256)
            ActivityScenario.launch<LiveReferenceActionActivity>(intent).use { scenario ->
                fun waitForFrames(after: Long): Long {
                    val deadline = System.nanoTime()+20_000_000_000L
                    var count = 0L
                    while (System.nanoTime() < deadline) {
                        scenario.onActivity {
                            count = it.analyzedFrameCount
                            if (count > after) assertEquals(identity.poseModelSha256,it.activePoseModelSha256)
                        }
                        if (count >= after+3) return count
                        Thread.sleep(100)
                    }
                    fail("Selected Heavy live camera produced $count frames, expected >=${after+3}")
                    return count
                }
                val first = waitForFrames(0)
                scenario.moveToState(Lifecycle.State.CREATED)
                Thread.sleep(750)
                scenario.moveToState(Lifecycle.State.RESUMED)
                val resumed = waitForFrames(first)
                val directory = java.io.File(context.getExternalFilesDir(null),"qualified-rep-evidence").apply { mkdirs() }
                java.io.File(directory,"heavy-camera-binding.json").writeText(JSONObject().put("kind","virtual-camera-model-binding-not-form-accuracy")
                    .put("model_sha256",identity.poseModelSha256).put("frames_before_pause",first).put("frames_after_resume",resumed).toString(2))
            }
        } finally { ReferenceActionProfileStore.clear() }
    }
    private fun replay(resource: String, reportName: String, expectations: String? = null) {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(resource))
        val fixture = GZIPInputStream(stream).bufferedReader().use { JSONObject(it.readText()) }
        val videos = fixture.getJSONObject("videos")
        val reference = videos.getJSONObject("reference");val source = videos.getJSONObject("source")
        if (expectations != null) {
            val overrides = requireNotNull(javaClass.classLoader?.getResourceAsStream(expectations)).bufferedReader().use { JSONObject(it.readText()) }
            assertEquals(reference.getString("source_video_sha256"),overrides.getString("reference_video_sha256"))
            assertEquals(source.getString("source_video_sha256"),overrides.getString("source_video_sha256"))
            for (key in overrides.keys()) if (key.startsWith("expected_") || key == "side") fixture.put(key,overrides.get(key))
        }
        val identity = RepEvidenceFingerprint(reference.getString("source_video_sha256"),reference.getString("model_sha256"))
        val referenceFrames = frames(reference.getJSONArray("frames"))
        val valid = referenceFrames.count { it.validity.status == FrameValidityStatus.VALID }
        val extraction = VideoPoseExtraction(VideoRole.REFERENCE,DurationMs(referenceFrames.last().timestamp.value+1),PoseSequence(VideoRole.REFERENCE,referenceFrames),
            VideoPoseDiagnostics(referenceFrames.size,referenceFrames.size,valid,referenceFrames.size-valid,0,0,0,1,1))
        val config = QualifiedRepConfig(side = RepObservedSide.valueOf(fixture.optString("side","LEFT")),
            referenceCurveFit = RepReferenceCurveFit.valueOf(fixture.optString("expected_reference_curve_fit","ORDINARY_LEAST_SQUARES")))
        val compiled = compileQualifiedRepFromExtraction(extraction,fixture.getString("exercise_id"),identity,config)
        assertTrue(compiled.toString(),compiled is QualifiedRepCompilation.Ready)
        val rep = (compiled as QualifiedRepCompilation.Ready).reference
        if (fixture.has("expected_driver_envelope")) {
            val envelope = requireNotNull(rep.driverEnvelope)
            val expectedEnvelope = fixture.getJSONObject("expected_driver_envelope")
            assertEquals(expectedEnvelope.getDouble("lower"),envelope.lower,1e-6)
            assertEquals(expectedEnvelope.getDouble("upper"),envelope.upper,1e-6)
            assertEquals(3,rep.calibration?.baselineAcceptedSelfCycles)
            assertEquals(4,rep.calibration?.acceptedSelfCycles)
            assertEquals(.8,rep.config.minimumReferenceReconstruction,0.0)
            val baseline = compileQualifiedRepFromExtraction(extraction,fixture.getString("exercise_id"),identity,
                config.copy(useObservedDriverEnvelope = false))
            assertTrue(baseline.toString(),baseline is QualifiedRepCompilation.Unavailable && baseline.reason == RepReferenceFailure.LOW_SELF_RECONSTRUCTION)
        }
        assertEquals(fixture.optInt("expected_reference_cycles",3),rep.calibration?.observedCycles)
        var selectionPolicy: String? = null
        if (videos.has("primary_reference")) {
            val primary = videos.getJSONObject("primary_reference")
            val primaryFrames = frames(primary.getJSONArray("frames"))
            val primaryExtraction = extraction.copy(poses = PoseSequence(VideoRole.REFERENCE,primaryFrames))
            val primaryIdentity = identity.copy(poseModelSha256 = primary.getString("model_sha256"))
            val selectionConfig = config.copy(referenceCurveFit = RepReferenceCurveFit.ORDINARY_LEAST_SQUARES)
            val primaryResult = compileQualifiedRepFromExtraction(primaryExtraction,fixture.getString("exercise_id"),primaryIdentity,selectionConfig)
            assertTrue("Fixture must actually need fallback",primaryResult is QualifiedRepCompilation.Unavailable)
            if (config.referenceCurveFit == RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE) {
                val standard = compileQualifiedRepFromExtraction(extraction,fixture.getString("exercise_id"),identity,selectionConfig)
                assertTrue(standard.toString(),standard is QualifiedRepCompilation.Unavailable)
                assertEquals(9,rep.calibration?.acceptedSelfCycles)
                assertEquals(.8,rep.config.minimumReferenceReconstruction,0.0)
            }
            val request = ReferenceExtractionRequest(VideoSource("fixture://verified-reference",Sha256(identity.referenceVideoSha256)),
                SamplingConfiguration(),PoseModelConfiguration(Sha256(primaryIdentity.poseModelSha256)))
            var extractions = 0
            val session = ReferenceActionSessionEngine(object : VideoPoseExtractor {
                override suspend fun extract(role: VideoRole, source: VideoSource, sampling: SamplingConfiguration, model: PoseModelConfiguration): StageResult<VideoPoseExtraction> {
                    extractions++
                    assertEquals(VideoRole.REFERENCE,role);assertEquals(request.source,source)
                    assertEquals(identity.poseModelSha256,model.modelSha256.value)
                    return StageResult.Success(extraction)
                }
            },InMemorySynchronizationPoseCache())
            if (config.referenceCurveFit == RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE) {
                val previous = runBlocking { session.prepareQualifiedReference(primaryExtraction,primaryIdentity,request,
                    fixture.getString("exercise_id"),selectionConfig,PoseModelConfiguration(Sha256(identity.poseModelSha256),"pose-landmarker-heavy"),
                    allowCurveFitRecovery = false) }
                assertTrue(previous.toString(),previous is QualifiedReferencePreparation.Unavailable)
                assertEquals(2,previous.attempts.size)
            }
            val selected = runBlocking { session.prepareQualifiedReference(primaryExtraction,primaryIdentity,request,
                fixture.getString("exercise_id"),selectionConfig,PoseModelConfiguration(Sha256(identity.poseModelSha256),"pose-landmarker-heavy")) }
            assertTrue(selected.toString(),selected is QualifiedReferencePreparation.Ready)
            assertEquals(rep.referenceKey,(selected as QualifiedReferencePreparation.Ready).reference.referenceKey)
            assertEquals("Curve recovery must reuse the original model-specific reference extraction",1,extractions)
            selectionPolicy = selected.selectionPolicy
            if (config.referenceCurveFit == RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE) {
                assertEquals(4,selected.attempts.size)
                assertEquals(listOf(RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,
                    RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE,RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE),selected.attempts.map { it.curveFit })
                assertEquals(QUALIFIED_REFERENCE_SELECTION_POLICY,selectionPolicy)
            }
        }
        val processor = LiveReferenceActionProcessor(rep.profile,15.0,qualifiedRepReference = rep,repEvidenceFingerprint = identity)
        val sourceRows = source.getJSONArray("frames");val sourceFrames = frames(sourceRows)
        val verdicts = mutableListOf<String>();val attempts = mutableListOf<RepAttemptResult>();var output: QualifiedRepOutput? = null
        for ((index,pose) in sourceFrames.withIndex()) {
            val row = sourceRows.getJSONObject(index)
            output = processor.update(pose,row.getInt("image_width"),row.getInt("image_height"),source.getString("model_sha256"),pose).qualifiedReps
            assertNotNull(output);assertFalse(requireNotNull(output).releaseQualified)
            verdicts += output.finalizedAttempts.map { it.verdict.name }
            attempts += output.finalizedAttempts
        }
        // Session termination is an interrupted attempt, never a new qualified count.
        val finished = requireNotNull(processor.finishQualifiedReps(sourceFrames.last().timestamp.value+1))
        verdicts += finished.finalizedAttempts.map { it.verdict.name }
        attempts += finished.finalizedAttempts
        output = finished
        val expected = fixture.getJSONObject("expected_counts")
        val counts = requireNotNull(output).counts
        attempts.filter { it.verdict == RepVerdict.CORRECT || it.verdict == RepVerdict.ACCEPTABLE }.forEach {
            assertTrue("Accepted outputs require original outbound evidence",it.outboundPathObserved)
            assertTrue("Accepted outputs require original return evidence",it.returnPathObserved)
        }
        assertEquals(expected.getInt("qualified"),counts.qualified)
        assertEquals(expected.getInt("correct"),counts.correct)
        assertEquals(expected.getInt("acceptable"),counts.acceptable)
        assertEquals(expected.getInt("rejected"),counts.rejected)
        assertEquals(expected.getInt("unassessable"),counts.unassessable)
        val expectedVerdicts = fixture.getJSONArray("expected_verdicts")
        assertEquals((0 until expectedVerdicts.length()).map { expectedVerdicts.getString(it) },verdicts)
        val expectedAttempts = fixture.getJSONArray("expected_attempts")
        assertEquals(expectedAttempts.length(),attempts.size)
        attempts.forEachIndexed { index,actual ->
            val expectedAttempt = expectedAttempts.getJSONObject(index)
            assertEquals(expectedAttempt.getLong("started_ms"),actual.startedMs)
            assertEquals(expectedAttempt.getLong("completed_ms"),actual.completedMs)
            assertEquals(expectedAttempt.getDouble("maximum_progress"),actual.maximumProgress,1e-6)
            val reasons = expectedAttempt.getJSONArray("reasons")
            val expectedReasons = (0 until reasons.length()).map { reasons.getString(it) }.toSet()
            // Whole-sequence replay retains a tracked identity through a blind sample. The singleton
            // live adapter drops it, so that same interruption may surface as CONTEXT_CHANGED first.
            // Both must invalidate the attempt; form/range/coverage reasons still match exactly.
            val actualReasons = actual.reasons.map { reason ->
                if (reason == RepReason.CONTEXT_CHANGED && "MISSING_CRITICAL_EVIDENCE" in expectedReasons)
                    "MISSING_CRITICAL_EVIDENCE" else reason.name
            }.toSet()
            assertEquals(expectedReasons,actualReasons)
        }
        val evidence = JSONObject().put("kind","real-source-landmark-native-jvm-parity-not-human-accuracy")
            .put("reference_schema",rep.schema).put("observed_path_required",rep.config.requireObservedPath)
            .put("reference_curve_fit",rep.config.referenceCurveFit.name).put("production_selection_policy",selectionPolicy)
            .put("accepted_outputs_with_observed_paths",attempts.count { (it.verdict == RepVerdict.CORRECT || it.verdict == RepVerdict.ACCEPTABLE) && it.outboundPathObserved && it.returnPathObserved })
            .put("model_sha256",identity.poseModelSha256).put("observed_side",config.side.name)
            .put("production_fallback_exercised",videos.has("primary_reference"))
            .put("driver_envelope_used",rep.driverEnvelope != null)
            .put("baseline_accepted_self_cycles",rep.calibration?.baselineAcceptedSelfCycles)
            .put("accepted_self_cycles",rep.calibration?.acceptedSelfCycles)
            .put("qualified",counts.qualified).put("correct",counts.correct).put("acceptable",counts.acceptable)
            .put("rejected",counts.rejected).put("unassessable",counts.unassessable).put("source_frames",sourceFrames.size)
            .put("observed_reference_cycles",rep.calibration?.observedCycles).put("release_qualified",false)
        val target = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"qualified-rep-evidence").apply { mkdirs() }
        File(target,reportName).writeText(evidence.toString(2));Log.i("SenpaiQualifiedRep",evidence.toString())
    }
    private fun frames(rows: JSONArray): List<PoseFrame> = (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index);val points = row.getJSONArray("landmarks")
        val detected = points.length() == 33
        PoseFrame(TimestampMs(row.getLong("timestamp_ms")),index.toLong(),PoseLandmarkId.entries.map { id ->
            if (!detected) PoseLandmark(id,ImageLandmark(0.0,0.0,0.0),visibility = 0.0,presence = 0.0) else {
                val p = points.getJSONArray(id.index)
                PoseLandmark(id,ImageLandmark(p.getDouble(0),p.getDouble(1),p.getDouble(2)),WorldLandmark(p.getDouble(5),p.getDouble(6),p.getDouble(7)),p.getDouble(3),p.getDouble(4))
            }
        },if (detected) FrameValidity.Valid else FrameValidity(FrameValidityStatus.BLIND,0.0,setOf(FrameValidityReason.NO_PERSON)))
    }
}
