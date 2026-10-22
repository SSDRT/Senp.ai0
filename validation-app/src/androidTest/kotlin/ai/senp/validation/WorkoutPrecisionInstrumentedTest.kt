package ai.senp.validation

import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.VideoSource
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.VideoPoseDiagnostics
import ai.senp.core.contracts.VideoPoseExtraction
import ai.senp.core.contracts.VideoRole
import ai.senp.core.pipeline.VideoPoseExtractor
import ai.senp.motion.ActionTrackingStatus
import ai.senp.motion.WorkoutAbstention
import ai.senp.motion.WorkoutFeatureStatus
import ai.senp.pose.mediapipe.LiveMediaPipePoseEstimator
import ai.senp.pose.mediapipe.LivePoseModel
import ai.senp.sync.v2.InMemorySynchronizationPoseCache
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutPrecisionInstrumentedTest {
    @Test fun nativePoseReferenceCompilationStaticRecognitionAndThresholdFeedbackAreConnected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.context.assets.open("pose.jpg").use { BitmapFactory.decodeStream(it)!! }
        val blank = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            LiveMediaPipePoseEstimator.create(instrumentation.targetContext, LivePoseModel.FULL).use { estimator ->
                val frames = (0 until 30).map { estimator.estimate(image, it * 67L) }
                val extraction = VideoPoseExtraction(VideoRole.REFERENCE, DurationMs(2010), PoseSequence(VideoRole.REFERENCE, frames),
                    VideoPoseDiagnostics(30, 30, 30, 0, 0, 0, 0, 1, 1))
                val unusedExtractor = object : VideoPoseExtractor {
                    override suspend fun extract(role: VideoRole, source: VideoSource, sampling: SamplingConfiguration, model: PoseModelConfiguration): StageResult<VideoPoseExtraction> =
                        error("compiled from supplied native reference frames; extraction is not called")
                }
                val session = ReferenceActionSessionEngine(unusedExtractor, InMemorySynchronizationPoseCache())
                val ready = session.compileReference(extraction, 15.0) as ReferencePreparationOutcome.Ready
                assertTrue(ready.profile.staticHold)
                val processor = session.newLiveProcessor(ready.profile, 15.0)
                var tracked = 0
                var monitored = 0
                val evidence = JSONArray()
                repeat(25) { index ->
                    val timestamp = 2100L + index * 67
                    val output = processor.update(estimator.estimate(image, timestamp))
                    if (output.estimate.status == ActionTrackingStatus.TRACKING) tracked++
                    val precision = requireNotNull(output.precision)
                    monitored = maxOf(monitored, precision.decisions.count { it.status == WorkoutFeatureStatus.MONITORED })
                    assertTrue("self-reference should not trigger correction", precision.triggers.isEmpty())
                    assertNull(output.feedback.primary)
                    evidence.put(JSONObject().put("timestamp_ms", timestamp).put("status", output.estimate.status.name)
                        .put("abstention", precision.abstention.name).put("monitored_features", precision.decisions.count { it.status == WorkoutFeatureStatus.MONITORED }))
                }
                assertTrue("static hold recognition count $tracked", tracked >= 15)
                assertTrue("monitored feature count $monitored", monitored >= 10)
                val missing = processor.update(estimator.estimate(blank, 3900L))
                assertTrue(requireNotNull(missing.precision).abstention != WorkoutAbstention.NONE)
                assertTrue(requireNotNull(missing.precision).activeSuggestions.isEmpty())
                assertNull(missing.feedback.primary)
                val output = File(instrumentation.targetContext.getExternalFilesDir(null), "workout-precision-evidence").apply { mkdirs() }
                File(output, "native-reference-feedback.json").writeText(JSONObject().put("kind", "native-static-self-reference-integration-not-human-accuracy")
                    .put("profile_version", ready.profile.version).put("profile_confidence", ready.profile.confidence)
                    .put("tracked_frames", tracked).put("max_monitored_features", monitored).put("frames", evidence).toString(2))
            }
        } finally { image.recycle(); blank.recycle() }
    }
}
