package ai.senp.pose.mediapipe

import ai.senp.core.contracts.PoseFrame
import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File
import java.nio.ByteBuffer

/**
 * Low-latency camera pose adapter. CameraX owns latest-frame backpressure; this adapter deliberately
 * uses MediaPipe VIDEO mode synchronously on the analyzer executor so each accepted bitmap has a
 * deterministic result/ownership lifetime while temporal tracking still receives strictly increasing
 * timestamps. Stale camera frames are dropped by CameraX before inference.
 *
 * The output is the same canonical 33-landmark [PoseFrame] used by offline video analysis.
 */
class LiveMediaPipePoseEstimator private constructor(
    private val landmarker: PoseLandmarker,
    private val mapper: PoseResultMapper,
    @Suppress("unused") private val retainedModelBuffer: ByteBuffer?,
) : AutoCloseable {
    private var previousTimestampMs: Long? = null
    private var diagnosticFrameIndex = 0L
    private var closed = false

    @Synchronized
    fun estimate(bitmap: Bitmap, timestampMs: Long): PoseFrame {
        check(!closed) { "Live pose estimator is closed" }
        val previous = previousTimestampMs
        require(previous == null || timestampMs > previous) {
            "live pose timestamps must strictly increase: $previous -> $timestampMs"
        }
        previousTimestampMs = timestampMs

        val image = BitmapImageBuilder(bitmap).build()
        val inferenceStarted = System.nanoTime()
        val result = try {
            image.use { landmarker.detectForVideo(it, timestampMs) }
        } catch (error: Throwable) {
            throw LivePoseException.Inference("MediaPipe live pose inference failed", error)
        }
        val inferenceNanos = System.nanoTime() - inferenceStarted
        val index = diagnosticFrameIndex++
        return try {
            mapper.map(timestampMs, index, result.toRawLive(), inferenceNanos).frame
        } catch (error: Throwable) {
            throw LivePoseException.Inference("MediaPipe live pose mapping failed", error)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        landmarker.close()
    }

    data class Config(
        val detectionConfidence: Float = 0.5f,
        val presenceConfidence: Float = 0.5f,
        val trackingConfidence: Float = 0.5f,
        val minimumUsableLandmarks: Int = 12,
        val usableVisibility: Float = 0.30f,
        val usablePresence: Float = 0.30f,
    ) {
        init {
            require(detectionConfidence in 0f..1f)
            require(presenceConfidence in 0f..1f)
            require(trackingConfidence in 0f..1f)
            require(minimumUsableLandmarks in 1..33)
            require(usableVisibility in 0f..1f)
            require(usablePresence in 0f..1f)
        }
    }

    companion object {
        fun create(
            context: Context,
            modelAssetPath: String = AndroidVideoPoseExtractor.DEFAULT_MODEL_ASSET,
            modelFile: File? = null,
            expectedModelSha256: String,
            config: Config = Config(),
        ): LiveMediaPipePoseEstimator {
            try {
                val resolvedModel = resolveModelOptions(context, modelAssetPath, modelFile, expectedModelSha256)
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(resolvedModel.baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(config.detectionConfidence)
                    .setMinPosePresenceConfidence(config.presenceConfidence)
                    .setMinTrackingConfidence(config.trackingConfidence)
                    .setOutputSegmentationMasks(false)
                    .build()
                return LiveMediaPipePoseEstimator(
                    PoseLandmarker.createFromOptions(context.applicationContext, options),
                    PoseResultMapper(config.minimumUsableLandmarks, config.usableVisibility, config.usablePresence),
                    resolvedModel.retainedBuffer,
                )
            } catch (error: LivePoseException.ModelLoad) {
                throw error
            } catch (error: Throwable) {
                throw LivePoseException.ModelLoad("Unable to initialize live MediaPipe Pose Landmarker", error)
            }
        }
    }
}

sealed class LivePoseException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ModelLoad(message: String, cause: Throwable? = null) : LivePoseException(message, cause)
    class Inference(message: String, cause: Throwable? = null) : LivePoseException(message, cause)
}

private fun com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult.toRawLive(): RawPoseResult {
    val image = landmarks().firstOrNull().orEmpty().map { landmark ->
        RawLandmark(
            landmark.x(),
            landmark.y(),
            landmark.z(),
            landmark.visibility().orElse(null),
            landmark.presence().orElse(null),
        )
    }
    val world = worldLandmarks().firstOrNull().orEmpty().map { landmark ->
        RawLandmark(
            landmark.x(),
            landmark.y(),
            landmark.z(),
            landmark.visibility().orElse(null),
            landmark.presence().orElse(null),
        )
    }
    return RawPoseResult(image, world)
}
