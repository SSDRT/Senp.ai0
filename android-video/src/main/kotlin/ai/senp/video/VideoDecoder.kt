package ai.senp.video

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Sequential MediaExtractor/MediaCodec decoder with timestamp sampling and bounded image buffering. */
class SequentialVideoDecoder(private val config: DecodeConfig = DecodeConfig()) {
    fun decode(
        file: File,
        cancellation: DecodeCancellation = DecodeCancellation.Never,
        onFrame: (DecodedFrame) -> Unit,
    ): DecodeResult {
        if (!file.isFile) throw VideoFailure.SourceMissing(file)

        val startedNanos = System.nanoTime()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        var imageQueue: DecoderImageQueue? = null

        try {
            try {
                extractor.setDataSource(file.absolutePath)
            } catch (error: IOException) {
                throw VideoFailure.Corrupt("Unable to read video container: ${file.absolutePath}", error)
            } catch (error: IllegalArgumentException) {
                throw VideoFailure.Corrupt("Invalid video container: ${file.absolutePath}", error)
            }

            val trackIndex = findVideoTrack(extractor)
                ?: throw VideoFailure.Unsupported("Container has no video track: ${file.absolutePath}")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw VideoFailure.Unsupported("Video track has no MIME type")
            if (!mime.startsWith("video/")) throw VideoFailure.Unsupported("Unsupported track MIME: $mime")

            val sourceWidth = requiredPositiveInt(format, MediaFormat.KEY_WIDTH)
            val sourceHeight = requiredPositiveInt(format, MediaFormat.KEY_HEIGHT)
            val rawRotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }
            val rotation = try {
                FrameGeometry.normalizeRotation(rawRotation)
            } catch (error: IllegalArgumentException) {
                throw VideoFailure.Unsupported(error.message ?: "Unsupported video rotation", error)
            }
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                0L
            }
            val (orientedWidth, orientedHeight) = FrameGeometry.orientedSize(sourceWidth, sourceHeight, rotation)
            val (outputWidth, outputHeight) = FrameGeometry.cappedSize(
                orientedWidth,
                orientedHeight,
                config.longEdgeCapPx,
            )
            val videoInfo = VideoInfo(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                orientedWidth = orientedWidth,
                orientedHeight = orientedHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                rotationDegrees = rotation,
                durationMs = durationUs / 1_000L,
                mime = mime,
            )

            imageQueue = DecoderImageQueue(sourceWidth, sourceHeight, config.frameTimeoutMs)
            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (error: Throwable) {
                throw VideoFailure.Unsupported("No MediaCodec decoder available for $mime", error)
            }

            // Rotation metadata is applied explicitly during YUV conversion, never implicitly by a player.
            if (format.containsKey(MediaFormat.KEY_ROTATION)) format.setInteger(MediaFormat.KEY_ROTATION, 0)
            try {
                codec.configure(format, imageQueue.surface, null, 0)
                codec.start()
                codecStarted = true
            } catch (error: Throwable) {
                throw VideoFailure.Codec("Unable to configure decoder ${codec.name} for $mime", error)
            }

            val transformer = Yuv420FrameTransformer(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                rotationDegrees = rotation,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
            )
            val sampler = TimestampSampler(config.targetFps)
            val timestampGuard = MonotonicTimestampGuard()
            val bufferInfo = MediaCodec.BufferInfo()

            var inputEnded = false
            var outputEnded = false
            var queuedInputSamples = 0
            var decodedFrames = 0
            var emittedFrames = 0
            var skippedBySampler = 0
            var firstPresentationTimeUs: Long? = null
            var lastPresentationTimeUs: Long? = null
            var lastEmittedTimestampMs: Long? = null
            var pixelConversionNanos = 0L
            var lastProgressNanos = System.nanoTime()

            while (!outputEnded) {
                if (cancellation.isCancelled()) throw VideoFailure.Cancelled()
                var progressed = false

                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(config.dequeueTimeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw VideoFailure.Codec("Decoder returned a null input buffer")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            val sampleTimeUs = extractor.sampleTime
                            if (sampleTimeUs < 0L) {
                                throw VideoFailure.Corrupt("Extractor returned a negative sample timestamp")
                            }
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                            queuedInputSamples++
                        }
                        progressed = true
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, config.dequeueTimeoutUs)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> progressed = true
                    else -> if (outputIndex >= 0) {
                        val flags = bufferInfo.flags
                        val endOfStream = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val codecConfig = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val hasFrame = !codecConfig && !(endOfStream && bufferInfo.size == 0)

                        if (!hasFrame) {
                            codec.releaseOutputBuffer(outputIndex, false)
                        } else {
                            val presentationTimeUs = bufferInfo.presentationTimeUs
                            timestampGuard.accept(presentationTimeUs)
                            if (firstPresentationTimeUs == null) firstPresentationTimeUs = presentationTimeUs
                            lastPresentationTimeUs = presentationTimeUs
                            decodedFrames++

                            val emit = sampler.shouldEmit(presentationTimeUs)
                            codec.releaseOutputBuffer(outputIndex, emit)
                            if (emit) {
                                val image = imageQueue.awaitImage()
                                val conversionStarted = System.nanoTime()
                                val pixels = image.use(transformer::transform)
                                pixelConversionNanos += System.nanoTime() - conversionStarted
                                val normalizedTimestampMs =
                                    (presentationTimeUs - requireNotNull(firstPresentationTimeUs)) / 1_000L
                                val previousMs = lastEmittedTimestampMs
                                if (previousMs != null && normalizedTimestampMs <= previousMs) {
                                    throw VideoFailure.NonMonotonicTimestamp(previousMs * 1_000L, normalizedTimestampMs * 1_000L)
                                }
                                val frame = DecodedFrame(
                                    timestampMs = normalizedTimestampMs,
                                    presentationTimeUs = presentationTimeUs,
                                    width = outputWidth,
                                    height = outputHeight,
                                    argb8888 = pixels,
                                )
                                try {
                                    onFrame(frame)
                                } catch (failure: VideoFailure) {
                                    throw failure
                                } catch (error: Throwable) {
                                    throw VideoFailure.Consumer(error)
                                }
                                lastEmittedTimestampMs = normalizedTimestampMs
                                emittedFrames++
                            } else {
                                skippedBySampler++
                            }
                        }
                        outputEnded = endOfStream
                        progressed = true
                    }
                }

                if (progressed) {
                    lastProgressNanos = System.nanoTime()
                } else if (System.nanoTime() - lastProgressNanos > config.stallTimeoutMs * 1_000_000L) {
                    throw VideoFailure.Codec("Decoder made no progress for ${config.stallTimeoutMs}ms")
                }
            }

            if (decodedFrames == 0) {
                throw VideoFailure.Corrupt("Video track decoded zero frames: ${file.absolutePath}")
            }

            return DecodeResult(
                info = videoInfo,
                diagnostics = DecodeDiagnostics(
                    queuedInputSamples = queuedInputSamples,
                    decodedFrames = decodedFrames,
                    emittedFrames = emittedFrames,
                    skippedBySampler = skippedBySampler,
                    firstPresentationTimeUs = firstPresentationTimeUs,
                    lastPresentationTimeUs = lastPresentationTimeUs,
                    decodeNanos = System.nanoTime() - startedNanos,
                    pixelConversionNanos = pixelConversionNanos,
                    maximumBufferedImages = imageQueue.maximumObservedDepth,
                    reusedOutputBuffer = emittedFrames > 1,
                ),
            )
        } catch (failure: VideoFailure) {
            throw failure
        } catch (error: MediaCodec.CodecException) {
            throw VideoFailure.Codec("MediaCodec failed while decoding ${file.absolutePath}", error)
        } catch (error: Throwable) {
            throw VideoFailure.Codec("Unexpected decode failure for ${file.absolutePath}", error)
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { imageQueue?.close() }
            extractor.release()
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }

    private fun requiredPositiveInt(format: MediaFormat, key: String): Int {
        if (!format.containsKey(key)) throw VideoFailure.Unsupported("Video track is missing $key")
        return format.getInteger(key).also {
            if (it <= 0) throw VideoFailure.Corrupt("Video track has invalid $key=$it")
        }
    }
}

private class DecoderImageQueue(
    width: Int,
    height: Int,
    private val frameTimeoutMs: Long,
) : AutoCloseable {
    private val handlerThread = HandlerThread("senp-video-image-reader").apply { start() }
    private val queue = ArrayBlockingQueue<Image>(2)
    private val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
    @Volatile
    var maximumObservedDepth: Int = 0
        private set

    val surface: Surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ imageReader ->
            while (true) {
                val image = runCatching { imageReader.acquireNextImage() }.getOrNull() ?: break
                if (!queue.offer(image)) image.close()
                maximumObservedDepth = maxOf(maximumObservedDepth, queue.size)
            }
        }, Handler(handlerThread.looper))
    }

    fun awaitImage(): Image = queue.poll(frameTimeoutMs, TimeUnit.MILLISECONDS)
        ?: throw VideoFailure.FrameTimeout(frameTimeoutMs)

    override fun close() {
        reader.setOnImageAvailableListener(null, null)
        while (true) queue.poll()?.close() ?: break
        reader.close()
        handlerThread.quitSafely()
        runCatching { handlerThread.join(2_000L) }
    }
}
