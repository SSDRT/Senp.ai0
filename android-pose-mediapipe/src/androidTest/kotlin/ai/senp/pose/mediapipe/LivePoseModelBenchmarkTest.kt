package ai.senp.pose.mediapipe

import ai.senp.core.contracts.FrameValidityStatus
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
class LivePoseModelBenchmarkTest {
    @Test fun fullAndHeavyRetainTheSameContractAndReportMeasuredLatency() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val image = context.assets.open("pose.jpg").use { BitmapFactory.decodeStream(it)!! }
        val blank = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val rows = JSONArray()
        try {
            for (model in LivePoseModel.entries) {
                LiveMediaPipePoseEstimator.create(context, model).use { estimator ->
                    val times = mutableListOf<Long>()
                    var valid = 0
                    repeat(16) { i ->
                        val result = estimator.estimateDetailed(image, i * 67L)
                        assertEquals(model.sha256, result.modelSha256)
                        assertEquals(33, result.frame.landmarks.size)
                        assertTrue(result.frame.landmarks.all { it.world != null })
                        if (result.frame.validity.status == FrameValidityStatus.VALID) valid++
                        if (i >= 4) times += result.inferenceNanos
                    }
                    assertEquals(16, valid)
                    assertEquals(FrameValidityStatus.BLIND, estimator.estimate(blank, 1100L).validity.status)
                    val sorted = times.sorted()
                    rows.put(JSONObject().put("model", model.name).put("sha256", model.sha256)
                        .put("valid_frames", valid).put("p50_inference_ms", sorted[sorted.size / 2] / 1e6)
                        .put("p95_inference_ms", sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)] / 1e6))
                }
            }
            val output = File(instrumentation.targetContext.getExternalFilesDir(null), "workout-precision-evidence").apply { mkdirs() }
            File(output, "model-comparison.json").writeText(JSONObject().put("kind", "upstream-static-image-contract-and-emulator-timing")
                .put("human_accuracy_measured", false).put("models", rows).toString(2))
        } finally { image.recycle(); blank.recycle() }
    }
}
