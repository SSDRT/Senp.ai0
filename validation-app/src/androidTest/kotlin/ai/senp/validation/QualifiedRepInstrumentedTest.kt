package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.motion.*
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original real-video landmarks exercise packaged Kotlin, adapters and live composition. Not fault ground truth. */
@RunWith(AndroidJUnit4::class)
class QualifiedRepInstrumentedTest {
    @Test fun originalSourceVideoLandmarksReproduceTheJvmAcceptedRepResultsOnAndroid() {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("qualified-rep-native.json.gz"))
        val fixture = GZIPInputStream(stream).bufferedReader().use { JSONObject(it.readText()) }
        val videos = fixture.getJSONObject("videos")
        val reference = videos.getJSONObject("reference");val source = videos.getJSONObject("source")
        val identity = RepEvidenceFingerprint(reference.getString("source_video_sha256"),reference.getString("model_sha256"))
        val referenceFrames = frames(reference.getJSONArray("frames"))
        val valid = referenceFrames.count { it.validity.status == FrameValidityStatus.VALID }
        val extraction = VideoPoseExtraction(VideoRole.REFERENCE,DurationMs(referenceFrames.last().timestamp.value+1),PoseSequence(VideoRole.REFERENCE,referenceFrames),
            VideoPoseDiagnostics(referenceFrames.size,referenceFrames.size,valid,referenceFrames.size-valid,0,0,0,1,1))
        val compiled = compileQualifiedRepFromExtraction(extraction,fixture.getString("exercise_id"),identity,QualifiedRepConfig())
        assertTrue(compiled.toString(),compiled is QualifiedRepCompilation.Ready)
        val rep = (compiled as QualifiedRepCompilation.Ready).reference
        assertEquals(3,rep.calibration?.observedCycles)
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
            .put("qualified",counts.qualified).put("correct",counts.correct).put("acceptable",counts.acceptable)
            .put("rejected",counts.rejected).put("unassessable",counts.unassessable).put("source_frames",sourceFrames.size)
            .put("observed_reference_cycles",rep.calibration?.observedCycles).put("release_qualified",false)
        val target = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"qualified-rep-evidence").apply { mkdirs() }
        File(target,"native-parity.json").writeText(evidence.toString(2));Log.i("SenpaiQualifiedRep",evidence.toString())
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
