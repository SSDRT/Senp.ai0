package ai.senp.validation

import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.ImageLandmark
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmark
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.SynchronizationStatus
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoPoseDiagnostics
import ai.senp.core.contracts.VideoPoseExtraction
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.VideoSource
import ai.senp.core.contracts.WorldLandmark
import ai.senp.core.pipeline.VideoPoseExtractor
import ai.senp.motion.ActionFeatureKind
import ai.senp.motion.ActionFeatureProfile
import ai.senp.motion.ActionProfile
import ai.senp.motion.ActionProfileValidation
import ai.senp.motion.ActionStateProfile
import ai.senp.motion.ActionTransitionProfile
import ai.senp.motion.ReferenceActionVersions
import ai.senp.motion.ReferenceDeviationMeasurement
import ai.senp.motion.RobustDistribution
import ai.senp.sync.v2.InMemorySynchronizationPoseCache
import ai.senp.sync.v2.VideoSynchronizationOutcome
import ai.senp.sync.v2.VideoSynchronizationPipelineV2
import ai.senp.sync.v2.VideoSynchronizationRequest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ReferenceActionSessionTest {
    @AfterTest
    fun clearProfileStore() {
        ReferenceActionProfileStore.clear()
    }

    @Test
    fun `self validated profile is accepted at documented core thresholds`() {
        val issue = referenceProfileUsabilityIssue(
            profile(
                reconstructionAccuracy = 0.72,
                transitionCoverage = 0.80,
                meanRecognitionConfidence = 0.55,
            ),
        )

        assertNull(issue)
    }

    @Test
    fun `weak self reconstruction is rejected truthfully`() {
        val issue = referenceProfileUsabilityIssue(profile(reconstructionAccuracy = 0.61))

        assertContains(requireNotNull(issue), "61%")
        assertContains(issue, "reconstruct")
    }

    @Test
    fun `weak transition coverage is rejected truthfully`() {
        val issue = referenceProfileUsabilityIssue(profile(transitionCoverage = 0.67))

        assertContains(requireNotNull(issue), "67%")
        assertContains(issue, "state transitions")
    }

    @Test
    fun `weak recognition confidence is rejected truthfully`() {
        val issue = referenceProfileUsabilityIssue(profile(meanRecognitionConfidence = 0.43))

        assertContains(requireNotNull(issue), "43%")
        assertContains(issue, "recognition confidence")
    }

    @Test
    fun `live profile store only exposes explicitly prepared reference`() {
        assertNull(ReferenceActionProfileStore.get())
        val prepared = PreparedReferenceAction(profile(), "a".repeat(64), 15.0)

        ReferenceActionProfileStore.set(prepared)

        assertEquals(prepared, ReferenceActionProfileStore.get())
        ReferenceActionProfileStore.clear()
        assertNull(ReferenceActionProfileStore.get())
    }

    @Test
    fun `generic pose extraction is reused by optional synchronization`() = runBlocking {
        val cache = InMemorySynchronizationPoseCache(maximumEntries = 4)
        var extractionCalls = 0
        val extractor = object : VideoPoseExtractor {
            override suspend fun extract(
                role: VideoRole,
                source: VideoSource,
                sampling: SamplingConfiguration,
                model: PoseModelConfiguration,
            ): StageResult<VideoPoseExtraction> {
                extractionCalls += 1
                return StageResult.Success(singleFrameExtraction(role))
            }
        }
        val session = ReferenceActionSessionEngine(extractor, cache)
        val sync = VideoSynchronizationPipelineV2(videoPoseExtractor = extractor, poseCache = cache)
        val sampling = SamplingConfiguration(targetFramesPerSecond = 15, longEdgeCapPx = 640)
        val model = PoseModelConfiguration(modelSha256 = Sha256("a".repeat(64)))
        val source = VideoSource(uri = "source.mp4", sha256 = Sha256("b".repeat(64)))
        val reference = VideoSource(uri = "reference.mp4", sha256 = Sha256("c".repeat(64)))

        assertTrue(session.extractPose(VideoRole.REFERENCE, reference, sampling, model) is StageResult.Success)
        assertTrue(session.extractPose(VideoRole.SOURCE, source, sampling, model) is StageResult.Success)
        assertEquals(2, extractionCalls)

        sync.synchronize(
            VideoSynchronizationRequest(
                source = source,
                reference = reference,
                sampling = sampling,
                model = model,
            ),
        )

        assertEquals(2, extractionCalls, "Sync-v2 should reuse the generic path pose cache")
    }

    @Test
    fun `sync refusal remains optional metadata beside generic action result`() = runBlocking {
        val extractor = object : VideoPoseExtractor {
            override suspend fun extract(
                role: VideoRole,
                source: VideoSource,
                sampling: SamplingConfiguration,
                model: PoseModelConfiguration,
            ): StageResult<VideoPoseExtraction> = StageResult.Success(singleFrameExtraction(role))
        }
        val sampling = SamplingConfiguration(targetFramesPerSecond = 15, longEdgeCapPx = 640)
        val model = PoseModelConfiguration(modelSha256 = Sha256("a".repeat(64)))
        val outcome = assertIs<VideoSynchronizationOutcome.Success>(
            VideoSynchronizationPipelineV2(videoPoseExtractor = extractor).synchronize(
                VideoSynchronizationRequest(
                    source = VideoSource(uri = "source.mp4", sha256 = Sha256("b".repeat(64))),
                    reference = VideoSource(uri = "reference.mp4", sha256 = Sha256("c".repeat(64))),
                    sampling = sampling,
                    model = model,
                ),
            ),
        )
        assertEquals(SynchronizationStatus.REFUSED, outcome.run.synchronization.result.status)

        val assembled = assembleRecordedComparison("independent-action-result", outcome)

        assertEquals("independent-action-result", assembled.actionResult)
        assertEquals(SynchronizationStatus.REFUSED, assembled.synchronizationRun?.synchronization?.result?.status)
        assertNull(assembled.synchronizationFailure)
    }

    @Test
    fun `reference cue names a relative difference without biomechanical verdict`() {
        val cue = ReferenceDeviationMeasurement(
            timestamp = TimestampMs(1_000L),
            stateId = "state_00",
            feature = "angle.left_elbow",
            referenceRange = 75.0..95.0,
            referenceMedian = 85.0,
            userValue = 112.0,
            signedDeltaOutsideRange = 17.0,
            normalizedDeviation = 1.7,
            confidence = 0.84,
            persistenceCandidate = true,
        ).toReferenceCueLabel()

        assertEquals("Left elbow angle differs from reference", cue)
        assertTrue("correct" !in cue.lowercase())
        assertTrue("perfect" !in cue.lowercase())
    }

    private fun singleFrameExtraction(role: VideoRole): VideoPoseExtraction {
        val landmarks = PoseLandmarkId.entries.mapIndexed { index, id ->
            val sideOffset = if (index % 2 == 0) 0.04 else -0.04
            PoseLandmark(
                id = id,
                image = ImageLandmark(
                    x = 0.45 + sideOffset,
                    y = (0.15 + index * 0.02).coerceAtMost(0.90),
                    z = 0.0,
                ),
                world = WorldLandmark(
                    xMeters = sideOffset,
                    yMeters = index * 0.01,
                    zMeters = 0.02 * (index % 3),
                ),
                visibility = 1.0,
                presence = 1.0,
            )
        }
        val frame = PoseFrame(
            timestamp = TimestampMs(0L),
            diagnosticFrameIndex = 0L,
            landmarks = landmarks,
            validity = FrameValidity.Valid,
        )
        return VideoPoseExtraction(
            role = role,
            duration = DurationMs(1_000L),
            poses = PoseSequence(role, listOf(frame)),
            diagnostics = VideoPoseDiagnostics(
                decodedFrameCount = 1,
                sampledFrameCount = 1,
                detectedFrameCount = 1,
                noPersonFrameCount = 0,
                unusableTrackingFrameCount = 0,
                decodeNanos = 1L,
                inferenceNanos = 1L,
                maxInFlightFrames = 1,
                peakInFlightFrames = 1,
            ),
        )
    }

    private fun profile(
        reconstructionAccuracy: Double = 0.90,
        transitionCoverage: Double = 0.92,
        meanRecognitionConfidence: Double = 0.82,
    ): ActionProfile {
        val distribution = RobustDistribution(
            median = 90.0,
            lower = 80.0,
            upper = 100.0,
            mad = 4.0,
            inlierCount = 20,
            sampleCount = 20,
            outlierFraction = 0.0,
        )
        val feature = ActionFeatureProfile(
            name = "angle.left_elbow",
            kind = ActionFeatureKind.GEOMETRY,
            reference = distribution,
            scale = 10.0,
            repeatability = 0.9,
            motionRelevance = 0.8,
            observability = 0.95,
            stateDiscrimination = 0.8,
            importance = 0.85,
            confidence = 0.9,
        )
        val states = listOf(
            ActionStateProfile(
                id = "state_00",
                index = 0,
                phaseStart = 0.0,
                phaseEndExclusive = 0.5,
                durationMs = distribution.copy(median = 400.0, lower = 300.0, upper = 500.0, mad = 30.0),
                features = listOf(feature),
                confidence = 0.88,
            ),
            ActionStateProfile(
                id = "state_01",
                index = 1,
                phaseStart = 0.5,
                phaseEndExclusive = 1.0,
                durationMs = distribution.copy(median = 400.0, lower = 300.0, upper = 500.0, mad = 30.0),
                features = listOf(feature.copy(reference = distribution.copy(median = 120.0, lower = 110.0, upper = 130.0))),
                confidence = 0.88,
            ),
        )
        return ActionProfile(
            version = ReferenceActionVersions.PROFILE,
            cyclic = false,
            cyclicityConfidence = 0.0,
            referenceRepetitions = 1,
            states = states,
            transitions = listOf(ActionTransitionProfile(0, 1, cyclicWrap = false, confidence = 0.85)),
            cycleDurationMs = null,
            featureScales = mapOf("angle.left_elbow" to 10.0),
            confidence = 0.86,
            validation = ActionProfileValidation(
                reconstructionAccuracy = reconstructionAccuracy,
                transitionCoverage = transitionCoverage,
                meanRecognitionConfidence = meanRecognitionConfidence,
                analyzableFraction = 0.91,
                referenceOutlierFraction = 0.03,
            ),
        )
    }
}
