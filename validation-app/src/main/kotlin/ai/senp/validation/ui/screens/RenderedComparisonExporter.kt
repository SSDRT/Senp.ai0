package ai.senp.validation.ui.screens

import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseSequence
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine

@UnstableApi
internal class RenderedComparisonExporter(
    private val context: Context,
) {
    suspend fun export(
        sourceUri: Uri,
        referenceUri: Uri,
        sourcePoses: PoseSequence,
        referencePoses: PoseSequence,
        plan: RenderedComparisonPlan,
        onProgress: (Int) -> Unit = {},
    ): File {
        require(plan.segments.isNotEmpty()) { "rendered comparison requires at least one trusted playback segment" }
        val outputDirectory = File(context.cacheDir, "rendered-comparisons").apply { mkdirs() }
        val cacheKey = renderCacheKey(sourceUri, referenceUri, sourcePoses, referencePoses, plan)
        val outputFile = File(outputDirectory, "$cacheKey.mp4")
        if (outputFile.isFile && outputFile.length() > MIN_VALID_OUTPUT_BYTES) {
            onProgress(100)
            return outputFile
        }

        val temporaryFile = File(outputDirectory, "$cacheKey.part.mp4")
        temporaryFile.delete()
        val composition = buildComposition(
            sourceUri = sourceUri,
            referenceUri = referenceUri,
            sourcePoses = sourcePoses,
            referencePoses = referencePoses,
            plan = plan,
        )
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .build()

        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            lateinit var transformer: Transformer
            var finished = false

            fun finishProgressPolling() {
                handler.removeCallbacksAndMessages(PROGRESS_TOKEN)
            }

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (finished) return
                    finished = true
                    finishProgressPolling()
                    if (!temporaryFile.isFile || temporaryFile.length() <= MIN_VALID_OUTPUT_BYTES) {
                        temporaryFile.delete()
                        continuation.resumeWithException(IllegalStateException("Rendered comparison export produced an empty file"))
                        return
                    }
                    outputFile.delete()
                    if (!temporaryFile.renameTo(outputFile)) {
                        temporaryFile.copyTo(outputFile, overwrite = true)
                        temporaryFile.delete()
                    }
                    onProgress(100)
                    continuation.resume(outputFile)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    if (finished) return
                    finished = true
                    finishProgressPolling()
                    temporaryFile.delete()
                    continuation.resumeWithException(exportException)
                }
            }

            transformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .addListener(listener)
                .build()

            val progressRunnable = object : Runnable {
                override fun run() {
                    if (finished) return
                    val holder = ProgressHolder()
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress.coerceIn(0, 99))
                    }
                    handler.postAtTime(this, PROGRESS_TOKEN, android.os.SystemClock.uptimeMillis() + 250L)
                }
            }

            continuation.invokeOnCancellation {
                if (!finished) {
                    finished = true
                    finishProgressPolling()
                    transformer.cancel()
                    temporaryFile.delete()
                }
            }

            try {
                transformer.start(composition, temporaryFile.absolutePath)
                handler.postAtTime(progressRunnable, PROGRESS_TOKEN, android.os.SystemClock.uptimeMillis() + 250L)
            } catch (error: Throwable) {
                if (!finished) {
                    finished = true
                    finishProgressPolling()
                    temporaryFile.delete()
                    continuation.resumeWithException(error)
                }
            }
        }
    }

    private fun buildComposition(
        sourceUri: Uri,
        referenceUri: Uri,
        sourcePoses: PoseSequence,
        referencePoses: PoseSequence,
        plan: RenderedComparisonPlan,
    ): Composition {
        val sourceItems = plan.segments.map { segment ->
            buildEditedItem(
                uri = sourceUri,
                clipStartMs = segment.sourceStartMs,
                clipEndMs = segment.sourceEndMs,
                poses = sourcePoses,
                lineColor = SOURCE_LINE_COLOR,
                speed = 1.0f,
            )
        }
        val referenceItems = plan.segments.map { segment ->
            buildEditedItem(
                uri = referenceUri,
                clipStartMs = segment.referenceStartMs,
                clipEndMs = segment.referenceEndMs,
                poses = referencePoses,
                lineColor = REFERENCE_LINE_COLOR,
                speed = segment.referenceSpeed,
            )
        }
        check(sourceItems.isNotEmpty() && sourceItems.size == referenceItems.size)

        return Composition.Builder(
            EditedMediaItemSequence(sourceItems),
            EditedMediaItemSequence(referenceItems),
        )
            .setVideoCompositorSettings(SideBySideVideoCompositorSettings)
            .build()
    }

    private fun buildEditedItem(
        uri: Uri,
        clipStartMs: Long,
        clipEndMs: Long,
        poses: PoseSequence,
        lineColor: Int,
        speed: Float,
    ): EditedMediaItem {
        require(clipEndMs > clipStartMs)
        require(speed.isFinite() && speed > 0f)
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clipStartMs)
            .setEndPositionMs(clipEndMs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()

        val skeletonOverlay = PoseSkeletonBitmapOverlay(
            poses = poses,
            clipStartMs = clipStartMs,
            lineColor = lineColor,
        )
        val videoEffects = mutableListOf<Effect>(
            OverlayEffect(ImmutableList.of<TextureOverlay>(skeletonOverlay)),
        )
        if (abs(speed - 1.0f) > 0.001f) {
            videoEffects += SpeedChangeEffect(speed)
        }
        videoEffects += Presentation.createForHeight(TARGET_SIDE_HEIGHT_PX)

        return EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
    }

    private fun renderCacheKey(
        sourceUri: Uri,
        referenceUri: Uri,
        sourcePoses: PoseSequence,
        referencePoses: PoseSequence,
        plan: RenderedComparisonPlan,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        add(RENDERER_VERSION)
        add(sourceUri.toString())
        add(referenceUri.toString())
        add(sourcePoses.frames.size.toString())
        add(referencePoses.frames.size.toString())
        plan.segments.forEach { segment ->
            add("${segment.sourceStartMs}:${segment.sourceEndMs}:${segment.referenceStartMs}:${segment.referenceEndMs}")
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }.take(32)
    }

    companion object {
        private const val RENDERER_VERSION = "side-by-side-pose-v1"
        private const val TARGET_SIDE_HEIGHT_PX = 640
        private const val MIN_VALID_OUTPUT_BYTES = 16L * 1024L
        private val PROGRESS_TOKEN = Any()
        private val SOURCE_LINE_COLOR = Color.rgb(92, 82, 232)
        private val REFERENCE_LINE_COLOR = Color.rgb(33, 182, 255)
    }
}

@UnstableApi
private object SideBySideVideoCompositorSettings : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size {
        require(inputSizes.isNotEmpty())
        return Size(
            inputSizes.sumOf(Size::getWidth),
            inputSizes.maxOf(Size::getHeight),
        )
    }

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        return when (inputId) {
            0 -> OverlaySettings.Builder()
                .setBackgroundFrameAnchor(-1f, 0f)
                .setOverlayFrameAnchor(-1f, 0f)
                .build()
            else -> OverlaySettings.Builder()
                .setBackgroundFrameAnchor(1f, 0f)
                .setOverlayFrameAnchor(1f, 0f)
                .build()
        }
    }
}

@UnstableApi
private class PoseSkeletonBitmapOverlay(
    private val poses: PoseSequence,
    private val clipStartMs: Long,
    private val lineColor: Int,
) : BitmapOverlay() {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val invalidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 82, 82)
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private var bitmap: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private var canvas: Canvas = Canvas(bitmap)

    override fun configure(backgroundSize: Size) {
        if (bitmap.width == backgroundSize.width && bitmap.height == backgroundSize.height) return
        bitmap.recycle()
        bitmap = Bitmap.createBitmap(backgroundSize.width, backgroundSize.height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap)
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        bitmap.eraseColor(Color.TRANSPARENT)
        val actualTimestampMs = clipStartMs + (presentationTimeUs / 1_000L).coerceAtLeast(0L)
        val pose = poses.nearestPose(actualTimestampMs) ?: return bitmap
        drawPose(pose)
        return bitmap
    }

    override fun release() {
        super.release()
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun drawPose(pose: PoseFrame) {
        if (pose.validity.status == FrameValidityStatus.BLIND || pose.validity.status == FrameValidityStatus.CONTINUITY_BREAK) return
        val activeLinePaint = if (pose.validity.status == FrameValidityStatus.VALID) linePaint else invalidPaint
        val activeJointPaint = if (pose.validity.status == FrameValidityStatus.VALID) jointPaint else invalidPaint
        val landmarks = pose.landmarks

        SKELETON_CONNECTIONS.forEach { (from, to) ->
            val start = landmarks[from]
            val end = landmarks[to]
            if (!start.drawable() || !end.drawable()) return@forEach
            canvas.drawLine(
                (start.image.x * bitmap.width).toFloat(),
                (start.image.y * bitmap.height).toFloat(),
                (end.image.x * bitmap.width).toFloat(),
                (end.image.y * bitmap.height).toFloat(),
                activeLinePaint,
            )
        }
        landmarks.forEach { landmark ->
            if (!landmark.drawable()) return@forEach
            canvas.drawCircle(
                (landmark.image.x * bitmap.width).toFloat(),
                (landmark.image.y * bitmap.height).toFloat(),
                6f,
                activeJointPaint,
            )
        }
    }

    private fun ai.senp.core.contracts.PoseLandmark.drawable(): Boolean {
        val confidence = minOf(visibility ?: 1.0, presence ?: 1.0)
        return confidence >= 0.3 && image.x in -0.05..1.05 && image.y in -0.05..1.05
    }

    private fun PoseSequence.nearestPose(timestampMs: Long): PoseFrame? {
        val frames = frames
        if (frames.isEmpty()) return null
        var low = 0
        var high = frames.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = frames[mid].timestamp.value
            when {
                value < timestampMs -> low = mid + 1
                value > timestampMs -> high = mid - 1
                else -> return frames[mid]
            }
        }
        val after = frames.getOrNull(low)
        val before = frames.getOrNull(low - 1)
        return when {
            before == null -> after
            after == null -> before
            timestampMs - before.timestamp.value <= after.timestamp.value - timestampMs -> before
            else -> after
        }
    }

    companion object {
        private val SKELETON_CONNECTIONS = listOf(
            11 to 12,
            11 to 23,
            12 to 24,
            23 to 24,
            11 to 13,
            13 to 15,
            15 to 17,
            15 to 19,
            15 to 21,
            12 to 14,
            14 to 16,
            16 to 18,
            16 to 20,
            16 to 22,
            23 to 25,
            25 to 27,
            27 to 29,
            27 to 31,
            24 to 26,
            26 to 28,
            28 to 30,
            28 to 32,
        )
    }
}
