package ai.senp.video

import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Configuration for timestamp-driven sequential decoding. */
data class DecodeConfig(
    val targetFps: Double = 15.0,
    val longEdgeCapPx: Int = 640,
    val dequeueTimeoutUs: Long = 10_000L,
    val frameTimeoutMs: Long = 3_000L,
    val stallTimeoutMs: Long = 10_000L,
) {
    init {
        require(targetFps > 0.0 && targetFps <= 120.0) { "targetFps must be in (0, 120]" }
        require(longEdgeCapPx > 0) { "longEdgeCapPx must be positive" }
        require(dequeueTimeoutUs > 0L) { "dequeueTimeoutUs must be positive" }
        require(frameTimeoutMs > 0L) { "frameTimeoutMs must be positive" }
        require(stallTimeoutMs >= frameTimeoutMs) { "stallTimeoutMs must be >= frameTimeoutMs" }
    }
}

fun interface DecodeCancellation {
    fun isCancelled(): Boolean

    companion object {
        val Never = DecodeCancellation { false }
    }
}

sealed class VideoFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class SourceMissing(val file: File) : VideoFailure("Video does not exist or is not a file: ${file.absolutePath}")
    class Unsupported(message: String, cause: Throwable? = null) : VideoFailure(message, cause)
    class Corrupt(message: String, cause: Throwable? = null) : VideoFailure(message, cause)
    class Codec(message: String, cause: Throwable? = null) : VideoFailure(message, cause)
    class FrameTimeout(val timeoutMs: Long) : VideoFailure("Timed out after ${timeoutMs}ms waiting for a decoded image")
    class NonMonotonicTimestamp(val previousUs: Long, val currentUs: Long) :
        VideoFailure("Decoded presentation timestamps must strictly increase: $previousUs -> $currentUs")
    class Cancelled : VideoFailure("Video decoding was cancelled")
    class Consumer(cause: Throwable) : VideoFailure("Decoded frame consumer failed", cause)
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

/**
 * A synchronous frame view. [argb8888] is reused by the decoder and is valid only until the
 * callback returns. Call [copyPixels] when retention is required.
 */
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
    val maximumBufferedImages: Int,
    val reusedOutputBuffer: Boolean,
)

data class DecodeResult(val info: VideoInfo, val diagnostics: DecodeDiagnostics)

object FrameGeometry {
    fun normalizeRotation(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        require(normalized in setOf(0, 90, 180, 270)) {
            "Only right-angle video rotation is supported, got $rotationDegrees"
        }
        return normalized
    }

    fun orientedSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        return when (normalizeRotation(rotationDegrees)) {
            90, 270 -> height to width
            else -> width to height
        }
    }

    fun cappedSize(width: Int, height: Int, longEdgeCapPx: Int = 640): Pair<Int, Int> {
        require(width > 0 && height > 0)
        require(longEdgeCapPx > 0)
        val longEdge = max(width, height)
        if (longEdge <= longEdgeCapPx) return width to height
        val scale = longEdgeCapPx.toDouble() / longEdge.toDouble()
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    fun outputSize(width: Int, height: Int, rotationDegrees: Int, longEdgeCapPx: Int): Pair<Int, Int> {
        val (orientedWidth, orientedHeight) = orientedSize(width, height, rotationDegrees)
        return cappedSize(orientedWidth, orientedHeight, longEdgeCapPx)
    }

    /** Maps one oriented output-space source pixel back into encoded source coordinates. */
    fun inverseRotate(
        orientedX: Int,
        orientedY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
    ): Pair<Int, Int> = when (normalizeRotation(rotationDegrees)) {
        0 -> orientedX to orientedY
        90 -> orientedY to (sourceHeight - 1 - orientedX)
        180 -> (sourceWidth - 1 - orientedX) to (sourceHeight - 1 - orientedY)
        270 -> (sourceWidth - 1 - orientedY) to orientedX
        else -> error("unreachable")
    }
}
