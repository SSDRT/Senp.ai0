package ai.senp.validation

import ai.senp.pose.PoseInputFrame
import ai.senp.pose.PoseLandmarkId
import ai.senp.pose.PoseOutcome
import ai.senp.pose.mediapipe.MediaPipePoseEstimator
import ai.senp.pose.mediapipe.PoseModelDescriptor
import ai.senp.pose.mediapipe.PoseModelSource
import ai.senp.video.DecodeConfig
import ai.senp.video.DecodedFrame
import ai.senp.video.SequentialVideoDecoder
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Test-only emulator harness. It is not part of the product composition root or frontend. */
class ValidationActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val status = TextView(this).apply { text = "Senp headless validation running" }
        setContentView(status)
        thread(name = "senp-validation") {
            runCatching { validate() }
                .onSuccess { summary ->
                    Log.i(TAG, "COMPLETE $summary")
                    runOnUiThread { status.text = summary }
                }
                .onFailure { error ->
                    Log.e(TAG, "FAILED", error)
                    runOnUiThread { status.text = "FAIL ${error::class.java.simpleName}: ${error.message}" }
                }
        }
    }

    private fun validate(): String {
        val videoPath = requireNotNull(intent.getStringExtra(EXTRA_VIDEO)) { "Missing --es $EXTRA_VIDEO" }
        val label = sanitize(intent.getStringExtra(EXTRA_LABEL) ?: File(videoPath).nameWithoutExtension)
        val requestedCaptures = parseCaptureTimes(intent.getStringExtra(EXTRA_CAPTURE_MS))
        val outputDirectory = File(getExternalFilesDir(null), "evidence/$label").apply {
            deleteRecursively()
            mkdirs()
        }

        val captures = mutableListOf<Capture>()
        var captureIndex = 0
        var detected = 0
        var noPerson = 0
        var unusable = 0
        var inferenceNanos = 0L
        var mappingNanos = 0L
        var firstPoseTimestampMs: Long? = null
        var lastPoseTimestampMs: Long? = null

        val estimatorConfig = MediaPipePoseEstimator.Config(
            detectionConfidence = 0.5f,
            presenceConfidence = 0.5f,
            trackingConfidence = 0.5f,
            minimumUsableLandmarks = 20,
            usableVisibility = 0.35f,
            usablePresence = 0.35f,
        )
        verifyModelAsset()
        val estimator = MediaPipePoseEstimator.create(
            context = this,
            source = PoseModelSource.Asset(MODEL_ASSET),
            config = estimatorConfig,
            descriptor = PoseModelDescriptor(sha256 = MODEL_SHA256),
        )

        val result = estimator.use { poseEstimator ->
            SequentialVideoDecoder(DecodeConfig(targetFps = 15.0, longEdgeCapPx = 640)).decode(File(videoPath)) { frame ->
                val outcome = poseEstimator.estimate(
                    PoseInputFrame(frame.timestampMs, frame.width, frame.height, frame.argb8888),
                )
                if (firstPoseTimestampMs == null) firstPoseTimestampMs = frame.timestampMs
                lastPoseTimestampMs = frame.timestampMs
                inferenceNanos += outcome.diagnostics.inferenceNanos
                mappingNanos += outcome.diagnostics.mappingNanos
                when (outcome) {
                    is PoseOutcome.Detected -> detected++
                    is PoseOutcome.NoPerson -> noPerson++
                    is PoseOutcome.UnusableTracking -> unusable++
                }

                if (captureIndex < requestedCaptures.size && frame.timestampMs >= requestedCaptures[captureIndex]) {
                    captures += saveCapture(outputDirectory, label, frame, outcome)
                    captureIndex++
                }
            }
        }

        if (captures.isNotEmpty()) {
            saveContactSheet(outputDirectory, label, captures)
            captures.forEach { capture ->
                capture.frame.recycle()
                capture.overlay.recycle()
            }
        }

        val summary = JSONObject()
            .put("label", label)
            .put("videoPath", videoPath)
            .put("modelVariant", "pose_landmarker_full_float16_v1")
            .put("modelBytes", MODEL_BYTES)
            .put("modelSha256", MODEL_SHA256)
            .put("mime", result.info.mime)
            .put("sourceWidth", result.info.sourceWidth)
            .put("sourceHeight", result.info.sourceHeight)
            .put("rotationDegrees", result.info.rotationDegrees)
            .put("orientedWidth", result.info.orientedWidth)
            .put("orientedHeight", result.info.orientedHeight)
            .put("outputWidth", result.info.outputWidth)
            .put("outputHeight", result.info.outputHeight)
            .put("durationMs", result.info.durationMs)
            .put("queuedInputSamples", result.diagnostics.queuedInputSamples)
            .put("decodedFrames", result.diagnostics.decodedFrames)
            .put("emittedFrames", result.diagnostics.emittedFrames)
            .put("skippedBySampler", result.diagnostics.skippedBySampler)
            .put("firstPresentationTimeUs", result.diagnostics.firstPresentationTimeUs)
            .put("lastPresentationTimeUs", result.diagnostics.lastPresentationTimeUs)
            .put("decodeNanos", result.diagnostics.decodeNanos)
            .put("pixelConversionNanos", result.diagnostics.pixelConversionNanos)
            .put("maximumBufferedImages", result.diagnostics.maximumBufferedImages)
            .put("reusedOutputBuffer", result.diagnostics.reusedOutputBuffer)
            .put("detectedPoseFrames", detected)
            .put("noPersonFrames", noPerson)
            .put("unusableTrackingFrames", unusable)
            .put("firstPoseTimestampMs", firstPoseTimestampMs)
            .put("lastPoseTimestampMs", lastPoseTimestampMs)
            .put("poseInferenceNanos", inferenceNanos)
            .put("poseMappingNanos", mappingNanos)
            .put("captureTimestampsMs", JSONArray(captures.map(Capture::timestampMs)))
            .put("captureDirectory", outputDirectory.absolutePath)

        val summaryFile = File(outputDirectory, "summary.json")
        summaryFile.writeText(summary.toString(2))
        File(outputDirectory, "COMPLETE").writeText("ok\n")
        return "OK $label frames=${result.diagnostics.emittedFrames} poses=$detected output=${result.info.outputWidth}x${result.info.outputHeight} evidence=${outputDirectory.absolutePath}"
    }

    private fun verifyModelAsset() {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        assets.open(MODEL_ASSET).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                byteCount += read
            }
        }
        check(byteCount == MODEL_BYTES) {
            "Pose model size mismatch: expected $MODEL_BYTES, got $byteCount"
        }
        val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        check(sha256 == MODEL_SHA256) {
            "Pose model SHA-256 mismatch: expected $MODEL_SHA256, got $sha256"
        }
    }

    private fun saveCapture(
        directory: File,
        label: String,
        frame: DecodedFrame,
        outcome: PoseOutcome,
    ): Capture {
        val baseName = "${label}_${frame.timestampMs}ms"
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(frame.argb8888, 0, frame.width, 0, 0, frame.width, frame.height)
        }
        val overlay = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        drawPoseOverlay(overlay, frame.timestampMs, outcome)
        saveJpeg(bitmap, File(directory, "${baseName}_frame.jpg"))
        saveJpeg(overlay, File(directory, "${baseName}_overlay.jpg"))
        return Capture(frame.timestampMs, bitmap, overlay, outcome is PoseOutcome.Detected)
    }

    private fun drawPoseOverlay(bitmap: Bitmap, timestampMs: Long, outcome: PoseOutcome) {
        val canvas = Canvas(bitmap)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 255, 120)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 80, 80)
            strokeWidth = 3f
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            textSize = 22f
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        canvas.drawText("${timestampMs} ms ${outcome::class.java.simpleName}", 12f, 28f, textPaint)

        if (outcome !is PoseOutcome.Detected) return
        val points = outcome.frame.landmarks.associateBy { it.id }
        for ((from, to) in CONNECTIONS) {
            val a = points.getValue(from).image
            val b = points.getValue(to).image
            if (isDrawable(a.xNormalized, a.yNormalized) && isDrawable(b.xNormalized, b.yNormalized)) {
                canvas.drawLine(
                    a.xNormalized * bitmap.width,
                    a.yNormalized * bitmap.height,
                    b.xNormalized * bitmap.width,
                    b.yNormalized * bitmap.height,
                    linePaint,
                )
            }
        }
        outcome.frame.landmarks.forEach { landmark ->
            val image = landmark.image
            if (isDrawable(image.xNormalized, image.yNormalized)) {
                canvas.drawCircle(
                    image.xNormalized * bitmap.width,
                    image.yNormalized * bitmap.height,
                    4.5f,
                    pointPaint,
                )
            }
        }
    }

    private fun saveContactSheet(directory: File, label: String, captures: List<Capture>) {
        val tileWidth = captures.first().frame.width
        val tileHeight = captures.first().frame.height
        val headerHeight = 34
        val sheet = Bitmap.createBitmap(
            tileWidth * captures.size,
            (tileHeight + headerHeight) * 2,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.DKGRAY)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
        }
        captures.forEachIndexed { index, capture ->
            val x = index * tileWidth.toFloat()
            canvas.drawText("raw ${capture.timestampMs} ms", x + 8f, 24f, text)
            canvas.drawBitmap(capture.frame, x, headerHeight.toFloat(), null)
            val secondHeaderY = tileHeight + headerHeight
            canvas.drawText(
                "pose ${capture.timestampMs} ms detected=${capture.detected}",
                x + 8f,
                secondHeaderY + 24f,
                text,
            )
            canvas.drawBitmap(capture.overlay, x, (secondHeaderY + headerHeight).toFloat(), null)
        }
        saveJpeg(sheet, File(directory, "${label}_contact_sheet.jpg"), quality = 88)
        sheet.recycle()
    }

    private fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 92) {
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "JPEG encode failed: $file" }
        }
    }

    private fun parseCaptureTimes(value: String?): List<Long> =
        (value ?: "0,1000,2000")
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it >= 0L }
            .distinct()
            .sorted()
            .take(6)
            .ifEmpty { listOf(0L) }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(80).ifEmpty { "video" }

    private fun isDrawable(x: Float, y: Float): Boolean =
        x.isFinite() && y.isFinite() && x in -0.1f..1.1f && y in -0.1f..1.1f

    private data class Capture(
        val timestampMs: Long,
        val frame: Bitmap,
        val overlay: Bitmap,
        val detected: Boolean,
    )

    companion object {
        private const val TAG = "SENP_VALIDATION"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_CAPTURE_MS = "capture_ms"
        private const val MODEL_ASSET = "pose_landmarker_full.task"
        private const val MODEL_BYTES = 9_398_198L
        private const val MODEL_SHA256 =
            "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"

        private val CONNECTIONS = listOf(
            PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.RIGHT_SHOULDER,
            PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.LEFT_ELBOW,
            PoseLandmarkId.LEFT_ELBOW to PoseLandmarkId.LEFT_WRIST,
            PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkId.RIGHT_ELBOW,
            PoseLandmarkId.RIGHT_ELBOW to PoseLandmarkId.RIGHT_WRIST,
            PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.LEFT_HIP,
            PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkId.RIGHT_HIP,
            PoseLandmarkId.LEFT_HIP to PoseLandmarkId.RIGHT_HIP,
            PoseLandmarkId.LEFT_HIP to PoseLandmarkId.LEFT_KNEE,
            PoseLandmarkId.LEFT_KNEE to PoseLandmarkId.LEFT_ANKLE,
            PoseLandmarkId.RIGHT_HIP to PoseLandmarkId.RIGHT_KNEE,
            PoseLandmarkId.RIGHT_KNEE to PoseLandmarkId.RIGHT_ANKLE,
            PoseLandmarkId.LEFT_ANKLE to PoseLandmarkId.LEFT_HEEL,
            PoseLandmarkId.LEFT_HEEL to PoseLandmarkId.LEFT_FOOT_INDEX,
            PoseLandmarkId.RIGHT_ANKLE to PoseLandmarkId.RIGHT_HEEL,
            PoseLandmarkId.RIGHT_HEEL to PoseLandmarkId.RIGHT_FOOT_INDEX,
        )
    }
}
