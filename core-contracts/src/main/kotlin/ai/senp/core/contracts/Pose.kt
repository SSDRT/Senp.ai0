package ai.senp.core.contracts

import kotlinx.serialization.Serializable

class ImmutableFrameBuffer private constructor(
    val pixelFormat: PixelFormat,
    val widthPx: Int,
    val heightPx: Int,
    val rowStrideBytes: Int,
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(widthPx > 0) { "frame width must be positive" }
        require(heightPx > 0) { "frame height must be positive" }
        val minimumRowStride = widthPx.toLong() * pixelFormat.bytesPerPixel
        val minimumByteCount = rowStrideBytes.toLong() * heightPx
        require(rowStrideBytes.toLong() >= minimumRowStride) {
            "row stride must hold at least one complete pixel row"
        }
        require(this.bytes.size.toLong() >= minimumByteCount) {
            "frame buffer must contain every declared row"
        }
    }

    val byteCount: Int
        get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ImmutableFrameBuffer &&
            pixelFormat == other.pixelFormat &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            rowStrideBytes == other.rowStrideBytes &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = pixelFormat.hashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        result = 31 * result + rowStrideBytes
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    companion object {
        fun copyOf(
            pixelFormat: PixelFormat,
            widthPx: Int,
            heightPx: Int,
            rowStrideBytes: Int,
            bytes: ByteArray,
        ): ImmutableFrameBuffer = ImmutableFrameBuffer(
            pixelFormat = pixelFormat,
            widthPx = widthPx,
            heightPx = heightPx,
            rowStrideBytes = rowStrideBytes,
            bytes = bytes,
        )
    }
}

enum class PixelFormat(val bytesPerPixel: Int) {
    RGBA_8888(4),
    RGB_888(3),
    GRAY_8(1),
}

data class DecodedFrame(
    val timestamp: TimestampMs,
    val diagnosticFrameIndex: Long,
    val buffer: ImmutableFrameBuffer,
) {
    init {
        require(diagnosticFrameIndex >= 0) { "diagnostic frame index must be non-negative" }
    }
}

data class DecodedVideo(
    val role: VideoRole,
    val duration: DurationMs,
    val frames: List<DecodedFrame>,
) {
    init {
        require(frames.zipWithNext().all { (left, right) -> left.timestamp < right.timestamp }) {
            "decoded frame timestamps must be strictly increasing"
        }
        require(frames.lastOrNull()?.timestamp?.value?.let { it < duration.value } ?: true) {
            "decoded frame timestamps must precede the video duration"
        }
    }
}

@Serializable
data class ImageLandmark(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        requireFinite(x, "image landmark x")
        requireFinite(y, "image landmark y")
        requireFinite(z, "image landmark z")
    }
}

@Serializable
data class WorldLandmark(
    val xMeters: Double,
    val yMeters: Double,
    val zMeters: Double,
) {
    init {
        requireFinite(xMeters, "world landmark x")
        requireFinite(yMeters, "world landmark y")
        requireFinite(zMeters, "world landmark z")
    }
}

@Serializable
data class PoseLandmark(
    val index: Int,
    val image: ImageLandmark,
    val world: WorldLandmark,
    val visibility: Double,
    val presence: Double,
) {
    init {
        require(index in 0 until LANDMARK_COUNT) { "landmark index must be in 0..32" }
        requireProbability(visibility, "landmark visibility")
        requireProbability(presence, "landmark presence")
    }

    companion object {
        const val LANDMARK_COUNT: Int = 33
    }
}

@Serializable
enum class FrameValidityStatus {
    VALID,
    REPAIRED,
    LOW_CONFIDENCE,
    MISSING,
}

@Serializable
enum class FrameValidityReason {
    BELOW_DETECTION_THRESHOLD,
    BELOW_PRESENCE_THRESHOLD,
    BELOW_TRACKING_THRESHOLD,
    SHORT_GAP_INTERPOLATION,
    LONG_GAP,
    OUT_OF_FRAME,
    NON_FINITE_INPUT,
}

@Serializable
data class FrameValidity(
    val status: FrameValidityStatus,
    val confidence: Double,
    val reasons: Set<FrameValidityReason> = emptySet(),
) {
    init {
        requireProbability(confidence, "frame validity confidence")
        require(status != FrameValidityStatus.VALID || reasons.isEmpty()) {
            "valid frames cannot contain invalidity reasons"
        }
    }

    companion object {
        val Valid: FrameValidity = FrameValidity(FrameValidityStatus.VALID, 1.0)
    }
}

@Serializable
data class PoseFrame(
    val timestamp: TimestampMs,
    val diagnosticFrameIndex: Long,
    val landmarks: List<PoseLandmark>,
    val validity: FrameValidity,
) {
    init {
        require(diagnosticFrameIndex >= 0) { "diagnostic frame index must be non-negative" }
        require(landmarks.size == PoseLandmark.LANDMARK_COUNT) { "pose frame must contain exactly 33 landmarks" }
        require(landmarks.map(PoseLandmark::index) == (0 until PoseLandmark.LANDMARK_COUNT).toList()) {
            "pose landmarks must be ordered and indexed 0..32"
        }
    }
}

@Serializable
data class PoseSequence(
    val role: VideoRole,
    val frames: List<PoseFrame>,
) {
    init {
        require(frames.zipWithNext().all { (left, right) -> left.timestamp < right.timestamp }) {
            "pose frame timestamps must be strictly increasing"
        }
    }
}
