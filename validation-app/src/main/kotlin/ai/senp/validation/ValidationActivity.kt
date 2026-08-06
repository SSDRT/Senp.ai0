package ai.senp.validation

import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.VideoSource
import ai.senp.pose.mediapipe.AndroidVideoPoseExtractor
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
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Emulator-only validation harness; not a product UI or composition root. */
class ValidationActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val status = TextView(this).apply { text = "Senp validation running" }
        setContentView(status)
        thread(name = "senp-validation") {
            runCatching { validate() }
                .onSuccess { summary ->
                    Log.i(TAG, "COMPLETE " + summary)
                    runOnUiThread { status.text = summary }
                }
                .onFailure { error ->
                    Log.e(TAG, "FAILED", error)
                    runOnUiThread { status.text = "FAIL " + error::class.java.simpleName + ": " + error.message }
                }
        }
    }

    private fun validate(): String {
        val videoPath = requireNotNull(intent.getStringExtra(EXTRA_VIDEO)) { "Missing video path" }
        val videoFile = File(videoPath)
        check(videoFile.isFile) { "Missing video: " + videoPath }
        val label = sanitize(intent.getStringExtra(EXTRA_LABEL) ?: videoFile.nameWithoutExtension)
        val requestedCaptures = parseCaptureTimes(intent.getStringExtra(EXTRA_CAPTURE_MS))
        val outputDirectory = File(getExternalFilesDir(null), "evidence/" + label).apply {
            deleteRecursively()
            mkdirs()
        }
        val videoSha = sha256(videoFile)
        val extractor = AndroidVideoPoseExtractor(this)
        val extraction = runBlocking {
            when (val result = extractor.extract(
                VideoRole.SOURCE,
                VideoSource(videoFile.absolutePath, Sha256(videoSha)),
                ai.senp.core.contracts.SamplingConfiguration(),
                PoseModelConfiguration(Sha256(MODEL_SHA256)),
            )) {
                is StageResult.Success -> result.value
                is StageResult.Failure -> error(result.failure.toString())
            }
        }
        val json = Json { prettyPrint = true; encodeDefaults = true }
        File(outputDirectory, "canonical_pose.json").writeText(json.encodeToString(extraction))

        val captures = mutableListOf<Capture>()
        var requestedIndex = 0
        val secondDecode = SequentialVideoDecoder(DecodeConfig()).decode(VideoRole.SOURCE, videoFile) { frame ->
            if (requestedIndex < requestedCaptures.size && frame.timestampMs >= requestedCaptures[requestedIndex]) {
                val pose = extraction.poses.frames.minByOrNull { kotlin.math.abs(it.timestamp.value - frame.timestampMs) }
                captures += saveCapture(outputDirectory, label, frame, pose)
                requestedIndex++
            }
            StageResult.Success(Unit)
        }
        val decode = when (secondDecode) {
            is StageResult.Success -> secondDecode.value
            is StageResult.Failure -> error(secondDecode.failure.toString())
        }
        if (captures.isNotEmpty()) saveContactSheet(outputDirectory, label, captures)
        captures.forEach { it.raw.recycle(); it.overlay.recycle() }

        val summary = buildString {
            appendLine("label=" + label)
            appendLine("video=" + videoFile.absolutePath)
            appendLine("videoSha256=" + videoSha)
            appendLine("mime=" + decode.info.mime)
            appendLine("source=" + decode.info.sourceWidth + "x" + decode.info.sourceHeight)
            appendLine("rotationDegrees=" + decode.info.rotationDegrees)
            appendLine("output=" + decode.info.outputWidth + "x" + decode.info.outputHeight)
            appendLine("durationMs=" + extraction.duration.value)
            appendLine("decodedFrames=" + extraction.diagnostics.decodedFrameCount)
            appendLine("sampledFrames=" + extraction.diagnostics.sampledFrameCount)
            appendLine("detectedFrames=" + extraction.diagnostics.detectedFrameCount)
            appendLine("noPersonFrames=" + extraction.diagnostics.noPersonFrameCount)
            appendLine("unusableFrames=" + extraction.diagnostics.unusableTrackingFrameCount)
            appendLine("decodeNanos=" + extraction.diagnostics.decodeNanos)
            appendLine("inferenceNanos=" + extraction.diagnostics.inferenceNanos)
            appendLine("maxInFlightFrames=" + extraction.diagnostics.maxInFlightFrames)
            appendLine("peakInFlightFrames=" + extraction.diagnostics.peakInFlightFrames)
            appendLine("maximumBufferedImages=" + decode.diagnostics.maximumBufferedImages)
            appendLine("reusedOutputBuffer=" + decode.diagnostics.reusedOutputBuffer)
            appendLine("captureTimestampsMs=" + captures.joinToString(",") { it.timestampMs.toString() })
        }
        File(outputDirectory, "summary.txt").writeText(summary)
        File(outputDirectory, "COMPLETE").writeText("ok\n")
        return "OK " + label + " frames=" + extraction.diagnostics.sampledFrameCount + " detected=" + extraction.diagnostics.detectedFrameCount
    }

    private fun saveCapture(directory: File, label: String, frame: DecodedFrame, pose: PoseFrame?): Capture {
        val raw = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(frame.argb8888, 0, frame.width, 0, 0, frame.width, frame.height)
        }
        val overlay = raw.copy(Bitmap.Config.ARGB_8888, true)
        drawPoseOverlay(overlay, frame.timestampMs, pose)
        val base = label + "_" + frame.timestampMs + "ms"
        saveJpeg(raw, File(directory, base + "_frame.jpg"))
        saveJpeg(overlay, File(directory, base + "_overlay.jpg"))
        return Capture(frame.timestampMs, raw, overlay)
    }

    private fun drawPoseOverlay(bitmap: Bitmap, timestampMs: Long, pose: PoseFrame?) {
        val canvas = Canvas(bitmap)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 255, 120); strokeWidth = 4f }
        val point = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 80, 80); style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; textSize = 22f; setShadowLayer(3f, 1f, 1f, Color.BLACK) }
        canvas.drawText(timestampMs.toString() + " ms " + (pose?.validity?.status ?: "missing"), 12f, 28f, text)
        if (pose == null) return
        val points = pose.landmarks.associateBy { it.id }
        for ((from, to) in CONNECTIONS) {
            val a = points.getValue(from).image
            val b = points.getValue(to).image
            if (drawable(a.x, a.y) && drawable(b.x, b.y)) {
                canvas.drawLine((a.x * bitmap.width).toFloat(), (a.y * bitmap.height).toFloat(), (b.x * bitmap.width).toFloat(), (b.y * bitmap.height).toFloat(), line)
            }
        }
        pose.landmarks.forEach { landmark ->
            val p = landmark.image
            if (drawable(p.x, p.y)) canvas.drawCircle((p.x * bitmap.width).toFloat(), (p.y * bitmap.height).toFloat(), 4.5f, point)
        }
    }

    private fun saveContactSheet(directory: File, label: String, captures: List<Capture>) {
        val tileWidth = captures.first().raw.width
        val tileHeight = captures.first().raw.height
        val header = 34
        val sheet = Bitmap.createBitmap(tileWidth * captures.size, (tileHeight + header) * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.DKGRAY)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f }
        captures.forEachIndexed { index, capture ->
            val x = index * tileWidth.toFloat()
            canvas.drawText("raw " + capture.timestampMs + " ms", x + 8f, 24f, text)
            canvas.drawBitmap(capture.raw, x, header.toFloat(), null)
            val second = tileHeight + header
            canvas.drawText("pose " + capture.timestampMs + " ms", x + 8f, second + 24f, text)
            canvas.drawBitmap(capture.overlay, x, (second + header).toFloat(), null)
        }
        saveJpeg(sheet, File(directory, label + "_contact_sheet.jpg"), 88)
        sheet.recycle()
    }

    private fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 92) {
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) }
    }

    private fun parseCaptureTimes(value: String?): List<Long> = (value ?: "0,1000,2000")
        .split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it >= 0 }.distinct().sorted().take(6).ifEmpty { listOf(0) }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(80).ifEmpty { "video" }
    private fun drawable(x: Double, y: Double) = x.isFinite() && y.isFinite() && x in -0.1..1.1 && y in -0.1..1.1

    private data class Capture(val timestampMs: Long, val raw: Bitmap, val overlay: Bitmap)

    companion object {
        private const val TAG = "SENP_VALIDATION"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_CAPTURE_MS = "capture_ms"
        private const val MODEL_SHA256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
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

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
