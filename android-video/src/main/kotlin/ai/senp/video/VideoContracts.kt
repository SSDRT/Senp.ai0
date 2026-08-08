package ai.senp.video

import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class DecodeConfig(
    val targetFps: Double = 15.0,
    val longEdgeCapPx: Int = 640,
    val dequeueTimeoutUs: Long = 10_000L,
    val frameTimeoutMs: Long = 3_000L,
    val stallTimeoutMs: Long = 10_000L,
) {
    init {
        require(targetFps > 0.0 && targetFps <= 120.0)
        require(longEdgeCapPx > 0)
        require(dequeueTimeoutUs > 0L)
        require(frameTimeoutMs > 0L)
        require(stallTimeoutMs >= frameTimeoutMs)
    }
}

fun interface DecodeCancellation {
    fun isCancelled(): Boolean
    companion object { val Never = DecodeCancellation { false } }
}

data class VideoInfo(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val orientedWidth: Int,
    val orientedHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val rotationDegrees: Int,
    val durationMs: Long,
    val mime: String,
)

/** Decoder-owned synchronous view. The ARGB array is reused after the callback returns. */
class DecodedFrame internal constructor(
    val timestampMs: Long,
    val presentationTimeUs: Long,
    val width: Int,
    val height: Int,
    val argb8888: IntArray,
) {
    fun copyPixels(): IntArray = argb8888.copyOf()
}

data class DecodeDiagnostics(
    val queuedInputSamples: Int,
    val decodedFrames: Int,
    val emittedFrames: Int,
    val skippedBySampler: Int,
    val firstPresentationTimeUs: Long?,
    val lastPresentationTimeUs: Long?,
    val decodeNanos: Long,
    val pixelConversionNanos: Long,
    val consumerNanos: Long,
    val maximumBufferedImages: Int,
    val reusedOutputBuffer: Boolean,
)

data class DecodeResult(val info: VideoInfo, val diagnostics: DecodeDiagnostics)

object FrameGeometry {
    fun normalizeRotation(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        require(normalized in setOf(0, 90, 180, 270))
        return normalized
    }

    fun orientedSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        return when (normalizeRotation(rotationDegrees)) { 90, 270 -> height to width; else -> width to height }
    }

    fun cappedSize(width: Int, height: Int, longEdgeCapPx: Int = 640): Pair<Int, Int> {
        require(width > 0 && height > 0 && longEdgeCapPx > 0)
        val longEdge = max(width, height)
        if (longEdge <= longEdgeCapPx) return width to height
        val scale = longEdgeCapPx.toDouble() / longEdge
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    fun visibleSize(
        codedWidth: Int,
        codedHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRightInclusive: Int,
        cropBottomInclusive: Int,
    ): Pair<Int, Int> {
        require(codedWidth > 0 && codedHeight > 0)
        require(cropLeft >= 0 && cropTop >= 0)
        require(cropRightInclusive in cropLeft until codedWidth)
        require(cropBottomInclusive in cropTop until codedHeight)
        return (cropRightInclusive - cropLeft + 1) to (cropBottomInclusive - cropTop + 1)
    }

    fun croppedSourceCoordinate(
        orientedX: Int,
        orientedY: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        rotationDegrees: Int,
        cropLeft: Int,
        cropTop: Int,
    ): Pair<Int, Int> {
        require(cropLeft >= 0 && cropTop >= 0)
        val local = inverseRotate(orientedX, orientedY, visibleWidth, visibleHeight, rotationDegrees)
        return (local.first + cropLeft) to (local.second + cropTop)
    }

    fun inverseRotate(orientedX: Int, orientedY: Int, sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): Pair<Int, Int> =
        when (normalizeRotation(rotationDegrees)) {
            0 -> orientedX to orientedY
            90 -> orientedY to (sourceHeight - 1 - orientedX)
            180 -> (sourceWidth - 1 - orientedX) to (sourceHeight - 1 - orientedY)
            270 -> (sourceWidth - 1 - orientedY) to orientedX
            else -> error("unreachable")
        }
}

internal sealed class VideoDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class SourceMissing(val file: File) : VideoDecodeException("Video does not exist: " + file.absolutePath)
    class Unsupported(message: String, cause: Throwable? = null) : VideoDecodeException(message, cause)
    class Corrupt(message: String, cause: Throwable? = null) : VideoDecodeException(message, cause)
    class Codec(message: String, cause: Throwable? = null) : VideoDecodeException(message, cause)
    class Timeout(message: String) : VideoDecodeException(message)
    class NonMonotonic(val previousUs: Long, val currentUs: Long) : VideoDecodeException("Non-monotonic timestamp: $previousUs -> $currentUs")
    class Cancelled : VideoDecodeException("Video decoding cancelled")
}
