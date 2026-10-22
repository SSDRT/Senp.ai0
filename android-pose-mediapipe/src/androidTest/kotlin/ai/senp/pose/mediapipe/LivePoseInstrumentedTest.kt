package ai.senp.pose.mediapipe

import ai.senp.core.contracts.FrameValidityReason
import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.PoseFrame
import ai.senp.motion.LivePoseStabilizer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class LivePoseInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.context
    private fun create(config: LiveMediaPipePoseEstimator.Config = LiveMediaPipePoseEstimator.Config()) =
        LiveMediaPipePoseEstimator.create(context, expectedModelSha256 = SHA, config = config)
    private fun poseImage(): Bitmap = context.assets.open("pose.jpg").use { BitmapFactory.decodeStream(it)!! }

    @Test fun positivePoseMatchesUpstreamRegressionAndKeepsRawWorldAndConfidence() {
        val bitmap = poseImage()
        try {
            create().use { estimator ->
                val result = estimator.estimateDetailed(bitmap, 0)
                assertEquals(FrameValidityStatus.VALID, result.frame.validity.status)
                assertEquals(result.rawFrame, result.frame) // Extra smoothing is not silently enabled.
                assertEquals(33, result.frame.landmarks.size)
                assertTrue(result.frame.landmarks.all { it.world != null && it.visibility != null && it.presence != null })
                val expected = context.assets.open("pose_landmarks.pbtxt").bufferedReader().use { it.readText() }
                val points = Regex("landmark \\{\\s*x: ([^\\s]+)\\s*y: ([^\\s]+)").findAll(expected).take(33).toList()
                assertEquals(33, points.size)
                val errors = result.rawFrame.landmarks.mapIndexed { index, joint ->
                    maxOf(abs(joint.image.x - points[index].groupValues[1].toDouble()), abs(joint.image.y - points[index].groupValues[2].toDouble()))
                }
                assertTrue("upstream maximum coordinate error ${errors.max()}", errors.max() < 0.05)
                assertTrue(result.inferenceNanos > 0)
                assertFalse("the adapter must not recycle a caller-owned bitmap", bitmap.isRecycled)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun blankFramesHaveNoPersonTimestampAndCloseRulesAreEnforced() {
        val blank = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val estimator = create()
        try {
            val frame = estimator.estimate(blank, 0)
            assertEquals(FrameValidityStatus.BLIND, frame.validity.status)
            assertTrue(FrameValidityReason.NO_PERSON in frame.validity.reasons)
            assertThrows(IllegalArgumentException::class.java) { estimator.estimate(blank, 0) }
            assertThrows(IllegalArgumentException::class.java) { estimator.estimate(blank, -1) }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val rejected = executor.submit<Boolean> {
                    try { estimator.estimate(blank, 33); false } catch (_: IllegalStateException) { true }
                }.get()
                assertTrue(rejected)
            } finally { executor.shutdown() }
        } finally { estimator.close(); blank.recycle() }
        estimator.close()
        val afterClose = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            assertThrows(IllegalStateException::class.java) { estimator.estimate(afterClose, 33) }
        } finally { afterClose.recycle() }
    }

    @Test fun modelMismatchAndMissingAssetsAreTyped() {
        assertThrows(LivePoseException.ModelLoad::class.java) {
            LiveMediaPipePoseEstimator.create(context, expectedModelSha256 = "0".repeat(64))
        }
        assertThrows(LivePoseException.ModelLoad::class.java) {
            LiveMediaPipePoseEstimator.create(context, "missing.task", SHA)
        }
    }

    @Test fun geometryAndLongGapResetNativeTrackingAndSignalContinuityBreak() {
        val bitmap = poseImage()
        val resized = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
        try {
            create().use { estimator ->
                estimator.estimate(bitmap, 0)
                val gap = estimator.estimateDetailed(bitmap, 1000)
                assertEquals(1L, gap.trackerResetCount)
                assertEquals(FrameValidityStatus.CONTINUITY_BREAK, gap.frame.validity.status)
                val dimensions = estimator.estimateDetailed(resized, 1033)
                assertEquals(2L, dimensions.trackerResetCount)
                assertTrue(FrameValidityReason.TRACKING_RESET in dimensions.frame.validity.reasons)
                assertEquals(FrameValidityStatus.VALID, estimator.estimate(resized, 1066).validity.status)
            }
        } finally { bitmap.recycle(); resized.recycle() }
    }

    @Test fun optionalGpuInitializesOrFallsBackToCpuWithDiagnostics() {
        val blank = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            create(LiveMediaPipePoseEstimator.Config(preferredDelegate = Delegate.GPU)).use { estimator ->
                val result = estimator.estimateDetailed(blank, 0)
                assertEquals(FrameValidityStatus.BLIND, result.frame.validity.status)
                if (result.delegate == Delegate.CPU) assertNotNull(result.fallbackReason)
            }
        } finally { blank.recycle() }
    }

    @Test fun timestampedNativeReplayWritesRawFilteredOverlaysAndLatencyEvidence() {
        val source = poseImage()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "live-pose-evidence").apply { mkdirs() }
        val frames = JSONArray()
        val times = mutableListOf<Long>()
        val sheet = Bitmap.createBitmap(4 * source.width, 3 * source.height, Bitmap.Config.ARGB_8888)
        val sheetCanvas = Canvas(sheet)
        var valid = 0
        try {
            create(LiveMediaPipePoseEstimator.Config(stabilization = LivePoseStabilizer.Config())).use { estimator ->
                repeat(24) { index ->
                    val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.BLACK)
                    if (index !in 10..12) canvas.drawBitmap(source, (sin(index * 1.7) * 4).toFloat(), 0f, null)
                    try {
                        val timestamp = index * 67L
                        val result = estimator.estimateDetailed(bitmap, timestamp)
                        times += result.inferenceNanos
                        if (result.frame.validity.status == FrameValidityStatus.VALID) valid++
                        if (index in 10..12) assertEquals("blank replay frame $index", FrameValidityStatus.BLIND, result.frame.validity.status)
                        frames.put(JSONObject().put("timestamp_ms", timestamp).put("status", result.frame.validity.status.name)
                            .put("raw_status", result.rawFrame.validity.status.name)
                            .put("inference_ms", result.inferenceNanos / 1e6).put("delegate", result.delegate.name)
                            .put("raw", joints(result.rawFrame)).put("filtered", joints(result.frame)))
                        if (index % 2 == 0) {
                            drawPose(canvas, result.rawFrame, Color.RED, bitmap.width, bitmap.height)
                            drawPose(canvas, result.frame, Color.GREEN, bitmap.width, bitmap.height)
                            canvas.drawText("${timestamp}ms ${result.frame.validity.status}", 10f, 25f, Paint().apply { color = Color.YELLOW; textSize = 20f })
                            File(output, "frame-$timestamp.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                            val cell = index / 2
                            sheetCanvas.drawBitmap(bitmap, (cell % 4 * source.width).toFloat(), (cell / 4 * source.height).toFloat(), null)
                        }
                    } finally { bitmap.recycle() }
                }
            }
            assertTrue("positive replay coverage $valid/21", valid >= 18)
            val sorted = times.sorted()
            val report = JSONObject().put("kind", "upstream-image-replay-not-labelled-video")
                .put("model_sha256", SHA).put("valid_frames", valid).put("total_frames", 24)
                .put("width", source.width).put("height", source.height)
                .put("p50_inference_ms", sorted[sorted.size / 2] / 1e6)
                .put("p95_inference_ms", sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)] / 1e6)
                .put("frames", frames)
            File(output, "replay.json").writeText(report.toString(2))
            File(output, "contact-sheet.jpg").outputStream().use { sheet.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally { source.recycle(); sheet.recycle() }
    }

    private fun joints(frame: PoseFrame): JSONArray = JSONArray().apply {
        frame.landmarks.forEach { p -> put(JSONObject().put("id", p.id.index).put("x", p.image.x).put("y", p.image.y)
            .put("z", p.image.z).put("visibility", p.visibility).put("presence", p.presence)
            .put("world", p.world?.let { JSONArray(listOf(it.xMeters, it.yMeters, it.zMeters)) })) }
    }

    private fun drawPose(canvas: Canvas, frame: PoseFrame, color: Int, width: Int, height: Int) {
        if (frame.validity.status != FrameValidityStatus.VALID) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 2f }
        val edges = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28)
        edges.forEach { (a, b) ->
            val p = frame.landmarks[a]; val q = frame.landmarks[b]
            if (minOf(p.visibility ?: 0.0, q.visibility ?: 0.0, p.presence ?: 0.0, q.presence ?: 0.0) >= 0.5) {
                canvas.drawLine((p.image.x * width).toFloat(), (p.image.y * height).toFloat(), (q.image.x * width).toFloat(), (q.image.y * height).toFloat(), paint)
            }
        }
    }

    companion object { private const val SHA = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1" }
}
