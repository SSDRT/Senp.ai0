package ai.senp.pose.mediapipe

import ai.senp.core.contracts.FrameValidityReason
import ai.senp.core.contracts.FrameValidityStatus
import org.junit.Assert.*
import org.junit.Test

class ConfigTest {
    @Test fun independentThresholdsArePreserved() {
        val config = MediaPipePoseEstimator.Config(0.4f, 0.5f, 0.6f)
        assertEquals(0.4f, config.detectionConfidence)
        assertEquals(0.5f, config.presenceConfidence)
        assertEquals(0.6f, config.trackingConfidence)
    }

    @Test fun allNullConfidenceIsPreservedButMapsConservativelyToBlind() {
        val image = (0 until 33).map { index -> RawLandmark(index / 100f, index / 200f, -index / 300f) }
        val estimate = PoseResultMapper().map(100, 7, RawPoseResult(image), 10)
        assertEquals(TrackingState.UNUSABLE, estimate.state)
        assertEquals(33, estimate.frame.landmarks.size)
        assertEquals((0 until 33).toList(), estimate.frame.landmarks.map { it.id.index })
        assertTrue(estimate.frame.landmarks.all { it.world == null && it.visibility == null && it.presence == null })
        assertEquals(FrameValidityStatus.BLIND, estimate.frame.validity.status)
        assertEquals(0.0, estimate.frame.validity.confidence, 0.0)
        assertTrue(FrameValidityReason.UNUSABLE_TRACKING in estimate.frame.validity.reasons)
    }

    @Test fun oneMissingConfidenceDimensionIsConservative() {
        val image = (0 until 33).map { index ->
            if (index % 2 == 0) RawLandmark(0.1f, 0.2f, 0.3f, visibility = 0.95f, presence = null)
            else RawLandmark(0.1f, 0.2f, 0.3f, visibility = null, presence = 0.95f)
        }
        val estimate = PoseResultMapper().map(100, 7, RawPoseResult(image), 10)
        assertEquals(TrackingState.UNUSABLE, estimate.state)
        assertEquals(FrameValidityStatus.BLIND, estimate.frame.validity.status)
        assertEquals(0.0, estimate.frame.validity.confidence, 0.0)
        assertTrue(FrameValidityReason.UNUSABLE_TRACKING in estimate.frame.validity.reasons)
    }

    @Test fun oneMissingConfidenceDimensionDoesNotBecomeUsable() {
        val partial = (0 until 33).map { RawLandmark(0.1f, 0.2f, 0.3f, visibility = 0.9f, presence = null) }
        val estimate = PoseResultMapper().map(0, 0, RawPoseResult(partial), 0)
        assertEquals(TrackingState.UNUSABLE, estimate.state)
        assertEquals(FrameValidityStatus.BLIND, estimate.frame.validity.status)
        assertEquals(0.0, estimate.frame.validity.confidence, 0.0)
        assertTrue(estimate.frame.landmarks.all { it.visibility != null && it.presence == null })
    }

    @Test fun optionalWorldDataMapsOnlyWhenAvailable() {
        val image = (0 until 33).map { RawLandmark(0.1f, 0.2f, 0.3f, 0.9f, 0.8f) }
        val world = (0 until 10).map { RawLandmark(1f, 2f, 3f) }
        val frame = PoseResultMapper().map(0, 0, RawPoseResult(image, world), 0).frame
        assertNotNull(frame.landmarks[9].world)
        assertNull(frame.landmarks[10].world)
        assertEquals(0.9, frame.landmarks[0].visibility!!, 0.0001)
        assertEquals(0.8, frame.landmarks[0].presence!!, 0.0001)
    }

    @Test fun noPersonAndUnusableTrackingAreCanonicalBlindFrames() {
        val mapper = PoseResultMapper()
        val noPerson = mapper.map(0, 0, RawPoseResult(emptyList()), 0)
        assertEquals(TrackingState.NO_PERSON, noPerson.state)
        assertEquals(setOf(FrameValidityReason.NO_PERSON), noPerson.frame.validity.reasons)
        assertEquals(33, noPerson.frame.landmarks.size)

        val weak = (0 until 33).map { RawLandmark(0f, 0f, 0f, 0.1f, 0.1f) }
        val unusable = mapper.map(1, 1, RawPoseResult(weak), 0)
        assertEquals(TrackingState.UNUSABLE, unusable.state)
        assertEquals(FrameValidityStatus.BLIND, unusable.frame.validity.status)
        assertTrue(FrameValidityReason.UNUSABLE_TRACKING in unusable.frame.validity.reasons)
    }

    @Test fun collectorEnforcesMonotonicTimestampsAndOneFrameBound() {
        val collector = ExtractionCollector(ai.senp.core.contracts.VideoRole.SOURCE)
        val estimate = PoseResultMapper().map(0, 0, RawPoseResult(emptyList()), 1)
        collector.acceptingFrame { collector.add(estimate) }
        assertEquals(1, collector.peakInFlightFrames)
        assertEquals(1, collector.noPersonFrameCount)
        assertThrows(IllegalArgumentException::class.java) { collector.add(estimate) }
    }
}
