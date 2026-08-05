package ai.senp.core.contracts

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ContractsTest {
    @Test
    fun `pose frame requires all 33 landmarks in canonical index order`() {
        val landmarks = validLandmarks()
        PoseFrame(TimestampMs(0), 0, landmarks, FrameValidity.Valid)

        assertFailsWith<IllegalArgumentException> {
            PoseFrame(TimestampMs(0), 0, landmarks.dropLast(1), FrameValidity.Valid)
        }
        assertFailsWith<IllegalArgumentException> {
            PoseFrame(TimestampMs(0), 0, landmarks.reversed(), FrameValidity.Valid)
        }
    }

    @Test
    fun `immutable frame buffer defensively copies input and output bytes`() {
        val source = byteArrayOf(1, 2, 3, 4)
        val buffer = ImmutableFrameBuffer.copyOf(
            pixelFormat = PixelFormat.GRAY_8,
            widthPx = 2,
            heightPx = 2,
            rowStrideBytes = 2,
            bytes = source,
        )

        source[0] = 99
        val firstRead = buffer.copyBytes()
        assertContentEquals(byteArrayOf(1, 2, 3, 4), firstRead)

        firstRead[1] = 88
        assertContentEquals(byteArrayOf(1, 2, 3, 4), buffer.copyBytes())

        assertFailsWith<IllegalArgumentException> {
            ImmutableFrameBuffer.copyOf(PixelFormat.RGBA_8888, 2, 2, 4, ByteArray(8))
        }
        assertFailsWith<IllegalArgumentException> {
            ImmutableFrameBuffer.copyOf(PixelFormat.RGBA_8888, 2, 2, 8, ByteArray(15))
        }
    }

    @Test
    fun `timestamps hashes probabilities and sampling reject invalid values`() {
        assertFailsWith<IllegalArgumentException> { TimestampMs(-1) }
        assertFailsWith<IllegalArgumentException> { Sha256("not-a-hash") }
        assertFailsWith<IllegalArgumentException> { Sha256("A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { SamplingConfiguration(0, 640) }
        assertFailsWith<IllegalArgumentException> { PoseThresholds(minimumTrackingConfidence = 1.1) }
    }

    @Test
    fun `cache key stable identity covers every behavior shaping field`() {
        val request = request()
        val key = CacheKey.from(request)

        assertEquals(
            Sha256("157ed93121917ab169a0ecbdf8e0f8f97c1e90639253a1f0301a4466dce9661f"),
            key.stableId(),
        )
        assertEquals(key.stableId(), CacheKey.from(request).stableId())
        assertNotEquals(key.stableId(), key.copy(sourceSha256 = Sha256("d".repeat(64))).stableId())
        assertNotEquals(key.stableId(), key.copy(referenceSha256 = Sha256("e".repeat(64))).stableId())
        assertNotEquals(key.stableId(), key.copy(modelSha256 = Sha256("f".repeat(64))).stableId())
        assertNotEquals(key.stableId(), key.copy(modelVariant = "lite").stableId())
        assertNotEquals(
            key.stableId(),
            key.copy(poseThresholds = key.poseThresholds.copy(minimumPresenceConfidence = 0.6)).stableId(),
        )
        assertNotEquals(key.stableId(), key.copy(pipelineVersion = "pipeline-v2").stableId())
        assertNotEquals(
            key.stableId(),
            key.copy(sampling = key.sampling.copy(targetFramesPerSecond = 30)).stableId(),
        )
        assertNotEquals(key.stableId(), key.copy(normalizationVersion = "normalization-v2").stableId())
        assertNotEquals(key.stableId(), key.copy(exerciseProfileVersion = "profile-v2").stableId())

        val injectedLeft = key.copy(
            normalizationVersion = "normalization-v1\nexerciseProfileVersion=profile-v2",
            exerciseProfileVersion = "profile-v3",
        )
        val injectedRight = key.copy(
            normalizationVersion = "normalization-v1",
            exerciseProfileVersion = "profile-v2\nexerciseProfileVersion=profile-v3",
        )
        assertNotEquals(injectedLeft.canonicalForm(), injectedRight.canonicalForm())
        assertNotEquals(injectedLeft.stableId(), injectedRight.stableId())
    }

    @Test
    fun `temporal collections reject duplicate frames overlapping phases and duplicate alignment points`() {
        val frame = DecodedFrame(
            timestamp = TimestampMs(0),
            diagnosticFrameIndex = 0,
            buffer = ImmutableFrameBuffer.copyOf(PixelFormat.GRAY_8, 1, 1, 1, byteArrayOf(1)),
        )
        assertFailsWith<IllegalArgumentException> {
            DecodedVideo(
                role = VideoRole.SOURCE,
                duration = DurationMs(1),
                frames = listOf(frame, frame.copy(diagnosticFrameIndex = 1)),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            PhaseSeries(
                role = VideoRole.SOURCE,
                phases = listOf(
                    PhaseSegment("first", TimestampMs(0), TimestampMs(10), 0, 1.0),
                    PhaseSegment("overlap", TimestampMs(9), TimestampMs(20), 0, 1.0),
                ),
            )
        }

        val point = AlignmentPoint(TimestampMs(0), TimestampMs(0), 0.0, 1.0)
        assertFailsWith<IllegalArgumentException> {
            AlignmentResult("duplicate", listOf(point, point), 1.0)
        }
    }

    @Test
    fun `valid frame cannot carry invalidity reasons`() {
        assertFailsWith<IllegalArgumentException> {
            FrameValidity(
                status = FrameValidityStatus.VALID,
                confidence = 1.0,
                reasons = setOf(FrameValidityReason.SHORT_GAP_INTERPOLATION),
            )
        }
    }

    private fun validLandmarks(): List<PoseLandmark> = List(PoseLandmark.LANDMARK_COUNT) { index ->
        PoseLandmark(
            index = index,
            image = ImageLandmark(index / 32.0, 0.5, 0.0),
            world = WorldLandmark(index / 100.0, 0.0, 0.0),
            visibility = 0.9,
            presence = 0.8,
        )
    }

    private fun request(): AnalysisRequest = AnalysisRequest(
        requestId = "contracts-test",
        requestedAtEpochMs = TimestampMs(1),
        source = VideoSource("fake://source", Sha256("a".repeat(64))),
        reference = VideoSource("fake://reference", Sha256("b".repeat(64))),
        configuration = AnalysisConfiguration(
            model = PoseModelConfiguration(Sha256("c".repeat(64))),
            pipelineVersion = "pipeline-v1",
            sampling = SamplingConfiguration(),
            normalizationVersion = "normalization-v1",
            exerciseProfileVersion = "profile-v1",
        ),
    )
}
