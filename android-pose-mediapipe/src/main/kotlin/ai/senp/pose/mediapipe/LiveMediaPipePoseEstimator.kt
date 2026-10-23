package ai.senp.pose.mediapipe

import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.FrameValidityReason
import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.motion.LivePoseStabilizer
import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/**
 * Low-latency camera pose adapter. CameraX owns latest-frame backpressure; this adapter deliberately
 * uses MediaPipe VIDEO mode synchronously on the analyzer executor so each accepted bitmap has a
 * deterministic result/ownership lifetime while temporal tracking still receives strictly increasing
 * timestamps. Stale camera frames are dropped by CameraX before inference.
 *
 * The output is the same canonical 33-landmark [PoseFrame] used by offline video analysis.
 */
class LiveMediaPipePoseEstimator private constructor(
    private var landmarker: PoseLandmarker,
    private val mapper: PoseResultMapper,
    private val factory: (Delegate) -> PoseLandmarker,
    private val config: Config,
    initialDelegate: Delegate,
    initialFallback: String?,
    private val modelAssetPath: String,
    private val modelSha256: String,
) : AutoCloseable {
    private var previousTimestampMs: Long? = null
    private var diagnosticFrameIndex = 0L
    private var closed = false
    private val ownerThread = Thread.currentThread().id
    private var dimensions: Pair<Int, Int>? = null
    private val stabilizer = LivePoseStabilizer(config.stabilization)
    private var activeDelegate = initialDelegate
    private var fallbackReason = initialFallback
    private var resetCount = 0L

    data class Estimate(
        val rawFrame: PoseFrame,
        val frame: PoseFrame,
        val inferenceNanos: Long,
        val delegate: Delegate,
        val trackerResetCount: Long,
        val fallbackReason: String?,
        val modelAssetPath: String,
        val modelSha256: String,
    )

    @Synchronized
    fun estimate(bitmap: Bitmap, timestampMs: Long): PoseFrame = estimateDetailed(bitmap, timestampMs).frame

    @Synchronized
    fun estimateDetailed(bitmap: Bitmap, timestampMs: Long): Estimate {
        checkOwner()
        check(!closed) { "Live pose estimator is closed" }
        require(timestampMs >= 0 && !bitmap.isRecycled)
        val previous = previousTimestampMs
        require(previous == null || timestampMs > previous) {
            "live pose timestamps must strictly increase: $previous -> $timestampMs"
        }
        val size = bitmap.width to bitmap.height
        var reset = previous != null && (timestampMs - previous > config.stabilization.resetGapMs || dimensions != size)
        if (reset) recreate(activeDelegate)
        previousTimestampMs = timestampMs
        dimensions = size
        val inferenceStarted = System.nanoTime()
        val result = try {
            detect(bitmap, timestampMs)
        } catch (error: RuntimeException) {
            if (activeDelegate != Delegate.GPU) throw LivePoseException.Inference("MediaPipe live pose inference failed", error)
            fallbackReason = "GPU inference failed: ${error.javaClass.simpleName}"
            recreate(Delegate.CPU)
            reset = true
            try { detect(bitmap, timestampMs) } catch (retry: RuntimeException) {
                throw LivePoseException.Inference("MediaPipe CPU fallback inference failed", retry)
            }
        }
        val inferenceNanos = System.nanoTime() - inferenceStarted
        val index = diagnosticFrameIndex++
        return try {
            val raw = mapper.map(timestampMs, index, result.toRawLive(), inferenceNanos).frame
            val input = if (reset) raw.copy(validity = FrameValidity(
                FrameValidityStatus.CONTINUITY_BREAK, 0.0, setOf(FrameValidityReason.TRACKING_RESET),
            )) else raw
            Estimate(raw, stabilizer.update(input, bitmap.width, bitmap.height), inferenceNanos, activeDelegate, resetCount, fallbackReason, modelAssetPath, modelSha256)
        } catch (error: RuntimeException) {
            throw LivePoseException.Inference("MediaPipe live pose mapping failed", error)
        }
    }

    private fun detect(bitmap: Bitmap, timestampMs: Long): com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult {
        // MPImage.close() recycles its Bitmap container in Tasks 0.10.32. Keep the caller's
        // camera/evidence bitmap alive, including when retrying GPU inference on CPU.
        val owned = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw LivePoseException.Inference("Unable to allocate pose input bitmap")
        try {
            return BitmapImageBuilder(owned).build().use { landmarker.detectForVideo(it, timestampMs) }
        } finally {
            if (!owned.isRecycled) owned.recycle()
        }
    }

    private fun recreate(delegate: Delegate) {
        try {
            landmarker.close()
            try {
                landmarker = factory(delegate)
                activeDelegate = delegate
            } catch (error: RuntimeException) {
                if (delegate != Delegate.GPU) throw error
                fallbackReason = "GPU reset failed: ${error.javaClass.simpleName}"
                landmarker = factory(Delegate.CPU)
                activeDelegate = Delegate.CPU
            }
        } catch (error: Exception) {
            closed = true
            throw LivePoseException.ModelLoad("Unable to recreate live pose tracker", error)
        } catch (error: LinkageError) {
            closed = true
            throw LivePoseException.ModelLoad("Native pose runtime is unavailable", error)
        }
        stabilizer.reset()
        resetCount++
    }

    private fun checkOwner() = check(Thread.currentThread().id == ownerThread) {
        "Create, infer and close the live pose estimator on the same analyzer thread"
    }

    @Synchronized
    override fun close() {
        if (closed) return
        checkOwner()
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
        val preferredDelegate: Delegate = Delegate.CPU,
        // MediaPipe VIDEO already smooths. Promote additional filtering only after a labelled holdout.
        val stabilization: LivePoseStabilizer.Config = LivePoseStabilizer.Config(enabled = false),
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
        fun create(context: Context, model: LivePoseModel, thresholds: ai.senp.core.contracts.PoseThresholds): LiveMediaPipePoseEstimator =
            create(context, model, Config(
                detectionConfidence = thresholds.minimumDetectionConfidence.toFloat(),
                presenceConfidence = thresholds.minimumPresenceConfidence.toFloat(),
                trackingConfidence = thresholds.minimumTrackingConfidence.toFloat(),
            ))

        fun create(context: Context, model: LivePoseModel, config: Config = Config()): LiveMediaPipePoseEstimator =
            create(context, model.assetPath, model.sha256, config)

        fun create(
            context: Context,
            modelAssetPath: String = AndroidVideoPoseExtractor.DEFAULT_MODEL_ASSET,
            expectedModelSha256: String,
            config: Config = Config(),
        ): LiveMediaPipePoseEstimator {
            try {
                val modelBuffer = verifiedPoseModelBuffer(context, modelAssetPath, expectedModelSha256)
                // The retained factory also owns the buffer throughout native graph resets.
                val factory: (Delegate) -> PoseLandmarker = { delegate ->
                    val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(modelBuffer.duplicate()).setDelegate(delegate).build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(config.detectionConfidence)
                    .setMinPosePresenceConfidence(config.presenceConfidence)
                    .setMinTrackingConfidence(config.trackingConfidence)
                    .setOutputSegmentationMasks(false)
                    .build()
                    PoseLandmarker.createFromOptions(context.applicationContext, options)
                }
                var active = config.preferredDelegate
                var fallback: String? = null
                val landmarker = try { factory(active) } catch (error: RuntimeException) {
                    if (active != Delegate.GPU) throw error
                    active = Delegate.CPU
                    fallback = "GPU initialization failed: ${error.javaClass.simpleName}"
                    factory(active)
                }
                return LiveMediaPipePoseEstimator(
                    landmarker,
                    PoseResultMapper(config.minimumUsableLandmarks, config.usableVisibility, config.usablePresence),
                    factory, config, active, fallback, modelAssetPath, expectedModelSha256,
                )
            } catch (error: LivePoseException.ModelLoad) {
                throw error
            } catch (error: Exception) {
                throw LivePoseException.ModelLoad("Unable to initialize live MediaPipe Pose Landmarker", error)
            } catch (error: LinkageError) {
                throw LivePoseException.ModelLoad("Native pose runtime is unavailable on this device", error)
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
