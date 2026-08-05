package ai.senp.pose.mediapipe

import ai.senp.pose.ImageLandmark
import ai.senp.pose.LandmarkConfidence
import ai.senp.pose.PoseDiagnostics
import ai.senp.pose.PoseEstimator
import ai.senp.pose.PoseFailure
import ai.senp.pose.PoseFrame
import ai.senp.pose.PoseInputFrame
import ai.senp.pose.PoseLandmark
import ai.senp.pose.PoseLandmarkId
import ai.senp.pose.PoseOutcome
import ai.senp.pose.UnusableTrackingReason
import ai.senp.pose.WorldLandmark
import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

sealed interface PoseModelSource {
    data class Asset(val assetPath: String) : PoseModelSource {
        init {
            require(assetPath.isNotBlank())
        }
    }

    data class LocalFile(val file: File) : PoseModelSource
}

data class PoseModelDescriptor(
    val variant: String = "pose_landmarker_full_float16_v1",
    val sha256: String? = null,
)

class MediaPipePoseEstimator private constructor(
    private val landmarker: PoseLandmarker,
    private val config: Config,
    val model: PoseModelDescriptor,
    @Suppress("unused") private val retainedModelBuffer: ByteBuffer?,
) : PoseEstimator {
    private var previousTimestampMs: Long? = null
    private var reusableBitmap: Bitmap? = null
    private var closed = false

    @Synchronized
    override fun estimate(frame: PoseInputFrame): PoseOutcome {
        check(!closed) { "Pose estimator is closed" }
        val previous = previousTimestampMs
        if (previous != null && frame.timestampMs <= previous) {
            throw PoseFailure.NonMonotonicTimestamp(previous, frame.timestampMs)
        }
        previousTimestampMs = frame.timestampMs

        val bitmap = reusableBitmap(frame.width, frame.height)
        bitmap.setPixels(frame.argb8888, 0, frame.width, 0, 0, frame.width, frame.height)
        val mediaPipeImage = BitmapImageBuilder(bitmap).build()
        val inferenceStarted = System.nanoTime()
        val result = try {
            mediaPipeImage.use { landmarker.detectForVideo(it, frame.timestampMs) }
        } catch (failure: PoseFailure) {
            throw failure
        } catch (error: Throwable) {
            throw PoseFailure.Inference("MediaPipe Pose Landmarker inference failed", error)
        }
        val inferenceNanos = System.nanoTime() - inferenceStarted
        return mapResult(frame.timestampMs, result, inferenceNanos)
    }

    private fun mapResult(timestampMs: Long, result: PoseLandmarkerResult, inferenceNanos: Long): PoseOutcome {
        val mappingStarted = System.nanoTime()
        val imagePoses = result.landmarks()
        val worldPoses = result.worldLandmarks()
        if (imagePoses.isEmpty()) {
            return PoseOutcome.NoPerson(
                timestampMs = timestampMs,
                diagnostics = PoseDiagnostics(
                    inferenceNanos = inferenceNanos,
                    mappingNanos = System.nanoTime() - mappingStarted,
                    visibleLandmarkCount = 0,
                    presentLandmarkCount = 0,
                ),
            )
        }

        val image = imagePoses.first()
        val world = worldPoses.firstOrNull().orEmpty()
        if (image.size != PoseLandmarkId.entries.size || world.size != PoseLandmarkId.entries.size) {
            return PoseOutcome.UnusableTracking(
                timestampMs = timestampMs,
                reason = UnusableTrackingReason.LandmarkCountMismatch(image.size, world.size),
                diagnostics = PoseDiagnostics(
                    inferenceNanos = inferenceNanos,
                    mappingNanos = System.nanoTime() - mappingStarted,
                    visibleLandmarkCount = image.count { (it.visibility().orElse(0f)) >= config.usableVisibility },
                    presentLandmarkCount = image.count { (it.presence().orElse(0f)) >= config.usablePresence },
                ),
            )
        }

        val landmarks = PoseLandmarkId.entries.map { id ->
            val normalized = image[id.index]
            val metric = world[id.index]
            PoseLandmark(
                id = id,
                image = ImageLandmark(
                    xNormalized = normalized.x(),
                    yNormalized = normalized.y(),
                    zNormalized = normalized.z(),
                    confidence = LandmarkConfidence(
                        visibility = normalized.visibility().orElse(null),
                        presence = normalized.presence().orElse(null),
                    ),
                ),
                world = WorldLandmark(
                    xMeters = metric.x(),
                    yMeters = metric.y(),
                    zMeters = metric.z(),
                    confidence = LandmarkConfidence(
                        visibility = metric.visibility().orElse(null),
                        presence = metric.presence().orElse(null),
                    ),
                ),
            )
        }

        val visibleCount = landmarks.count {
            (it.image.confidence.visibility ?: 0f) >= config.usableVisibility
        }
        val presentCount = landmarks.count {
            (it.image.confidence.presence ?: 0f) >= config.usablePresence
        }
        val usableCount = landmarks.count {
            (it.image.confidence.visibility ?: 0f) >= config.usableVisibility &&
                (it.image.confidence.presence ?: 0f) >= config.usablePresence
        }
        val diagnostics = PoseDiagnostics(
            inferenceNanos = inferenceNanos,
            mappingNanos = System.nanoTime() - mappingStarted,
            visibleLandmarkCount = visibleCount,
            presentLandmarkCount = presentCount,
        )
        if (usableCount < config.minimumUsableLandmarks) {
            return PoseOutcome.UnusableTracking(
                timestampMs = timestampMs,
                reason = UnusableTrackingReason.InsufficientConfidence(
                    usableLandmarks = usableCount,
                    requiredLandmarks = config.minimumUsableLandmarks,
                    minimumVisibility = config.usableVisibility,
                    minimumPresence = config.usablePresence,
                ),
                diagnostics = diagnostics,
            )
        }
        return PoseOutcome.Detected(PoseFrame(timestampMs, landmarks), diagnostics)
    }

    private fun reusableBitmap(width: Int, height: Int): Bitmap {
        val existing = reusableBitmap
        if (existing != null && existing.width == width && existing.height == height && !existing.isRecycled) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { reusableBitmap = it }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        reusableBitmap?.recycle()
        reusableBitmap = null
        landmarker.close()
    }

    data class Config(
        val detectionConfidence: Float = 0.5f,
        val presenceConfidence: Float = 0.5f,
        val trackingConfidence: Float = 0.5f,
        val minimumUsableLandmarks: Int = 20,
        val usableVisibility: Float = 0.35f,
        val usablePresence: Float = 0.35f,
    ) {
        init {
            require(detectionConfidence in 0f..1f)
            require(presenceConfidence in 0f..1f)
            require(trackingConfidence in 0f..1f)
            require(minimumUsableLandmarks in 1..PoseLandmarkId.entries.size)
            require(usableVisibility in 0f..1f)
            require(usablePresence in 0f..1f)
        }
    }

    companion object {
        fun create(
            context: Context,
            source: PoseModelSource,
            config: Config = Config(),
            descriptor: PoseModelDescriptor = PoseModelDescriptor(),
        ): MediaPipePoseEstimator {
            var retainedBuffer: ByteBuffer? = null
            val baseOptions = try {
                val builder = BaseOptions.builder()
                when (source) {
                    is PoseModelSource.Asset -> builder.setModelAssetPath(source.assetPath)
                    is PoseModelSource.LocalFile -> {
                        if (!source.file.isFile || source.file.length() <= 0L) {
                            throw PoseFailure.ModelLoad("Missing or empty pose model: ${source.file.absolutePath}")
                        }
                        retainedBuffer = FileInputStream(source.file).channel.use { channel ->
                            channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0L, channel.size())
                        }
                        builder.setModelAssetBuffer(requireNotNull(retainedBuffer))
                    }
                }
                builder.build()
            } catch (failure: PoseFailure) {
                throw failure
            } catch (error: Throwable) {
                throw PoseFailure.ModelLoad("Unable to open MediaPipe Pose Landmarker model", error)
            }

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(config.detectionConfidence)
                .setMinPosePresenceConfidence(config.presenceConfidence)
                .setMinTrackingConfidence(config.trackingConfidence)
                .setOutputSegmentationMasks(false)
                .build()
            val landmarker = try {
                PoseLandmarker.createFromOptions(context.applicationContext, options)
            } catch (error: Throwable) {
                throw PoseFailure.ModelLoad("Unable to initialize MediaPipe Pose Landmarker Full", error)
            }
            return MediaPipePoseEstimator(landmarker, config, descriptor, retainedBuffer)
        }
    }
}
