package ai.senp.motion

import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.FrameValidityReason
import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.ImageLandmark
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmark
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.WorldLandmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun liveFrame(ms: Long, x: Double = 0.5): PoseFrame = PoseFrame(
    TimestampMs(ms), ms,
    PoseLandmarkId.entries.map { id -> PoseLandmark(id, ImageLandmark(x, 0.5, 0.0), WorldLandmark(x, 0.0, 0.0), 0.9, 0.9) },
    FrameValidity.Valid,
)

class LivePoseStabilizerTest {
    @Test fun preservesRawConfidenceIdsTimestampAndMissingWorld() {
        val filter = LivePoseStabilizer()
        filter.update(liveFrame(0), 640, 480)
        val raw = liveFrame(33, 0.6).let { it.copy(landmarks = it.landmarks.map { joint -> joint.copy(world = null) }) }
        val filtered = filter.update(raw, 640, 480)
        assertEquals(raw.timestamp, filtered.timestamp)
        assertEquals(raw.validity, filtered.validity)
        assertEquals(raw.landmarks.map { it.id }, filtered.landmarks.map { it.id })
        assertTrue(filtered.landmarks.all { it.world == null && it.presence == 0.9 && it.visibility == 0.9 })
        assertTrue(filtered.landmarks[0].image.x in 0.5..0.6)
        assertEquals(0.6, raw.landmarks[0].image.x)
    }

    @Test fun missingPersonAndLowConfidenceNeverReuseOldJoints() {
        val filter = LivePoseStabilizer()
        filter.update(liveFrame(0), 640, 480)
        val blind = liveFrame(33, 0.0).copy(validity = FrameValidity(FrameValidityStatus.BLIND, 0.0, setOf(FrameValidityReason.NO_PERSON)))
        assertEquals(blind, filter.update(blind, 640, 480))
        assertEquals(liveFrame(66, 0.8), filter.update(liveFrame(66, 0.8), 640, 480))
        val partial = liveFrame(99).let { it.copy(landmarks = it.landmarks.mapIndexed { i, p -> if (i == 15) p.copy(visibility = null) else p }) }
        assertEquals(partial.landmarks[15], filter.update(partial, 640, 480).landmarks[15])
        val recovered = liveFrame(132, 0.7)
        assertEquals(recovered.landmarks[15], filter.update(recovered, 640, 480).landmarks[15])
    }

    @Test fun faceAndHandsCannotValidateMissingBodyAndOffscreenBodyIsBlind() {
        val filter = LivePoseStabilizer()
        val face = liveFrame(0).let { it.copy(landmarks = it.landmarks.map { p -> if (p.id.index in 11..16 || p.id.index in 23..28) p.copy(presence = 0.1) else p }) }
        assertEquals(FrameValidityStatus.BLIND, filter.update(face, 640, 480).validity.status)
        val outside = liveFrame(33, 1.2)
        assertTrue(FrameValidityReason.OUT_OF_FRAME in filter.update(outside, 640, 480).validity.reasons)
    }

    @Test fun gapDimensionsAndExplicitResetDoNotBlendDifferentTracks() {
        val filter = LivePoseStabilizer()
        filter.update(liveFrame(0), 640, 480)
        assertEquals(liveFrame(1000, 0.9), filter.update(liveFrame(1000, 0.9), 640, 480))
        assertEquals(liveFrame(1033, 0.2), filter.update(liveFrame(1033, 0.2), 480, 640))
        assertFailsWith<IllegalArgumentException> { filter.update(liveFrame(1033), 480, 640) }
        assertFailsWith<IllegalArgumentException> { filter.update(liveFrame(1032), 480, 640) }
        filter.reset()
        assertEquals(liveFrame(0), filter.update(liveFrame(0), 640, 480))
    }

    @Test fun disabledFilterRetainsRawButStillGatesUnusableBody() {
        val filter = LivePoseStabilizer(LivePoseStabilizer.Config(enabled = false))
        filter.update(liveFrame(0), 640, 480)
        assertEquals(liveFrame(33, 0.8), filter.update(liveFrame(33, 0.8), 640, 480))
        assertEquals(FrameValidityStatus.BLIND, filter.update(liveFrame(66, -1.0), 640, 480).validity.status)
    }

    @Test fun rejectsInvalidConfigurationAndDimensions() {
        assertFailsWith<IllegalArgumentException> { LivePoseStabilizer.Config(imageBeta = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { LivePoseStabilizer.Config(minCutoffHz = 0.0) }
        assertFailsWith<IllegalArgumentException> { LivePoseStabilizer.Config(resetGapMs = 0) }
        assertFailsWith<IllegalArgumentException> { LivePoseStabilizer().update(liveFrame(0), 0, 480) }
    }

    @Test fun tunedCandidateReducesStaticJitterWhileBoundingMovingErrorAcrossCaptureRates() {
        for (fps in listOf(10, 15, 30, 60)) for (irregular in listOf(false, true)) {
            val raw = LivePoseTuningBenchmark.measure(LivePoseStabilizer.Config(enabled = false), fps, irregular)
            val candidate = LivePoseTuningBenchmark.measure(LivePoseStabilizer.Config(), fps, irregular)
            assertTrue(candidate.jitter < raw.jitter * 0.88, "static $fps FPS irregular=$irregular")
            assertTrue(candidate.movingRmse < raw.movingRmse * 1.06, "moving $fps FPS irregular=$irregular")
        }
        val fixed = LivePoseTuningBenchmark.measure(LivePoseStabilizer.Config(imageBeta = 0.0), 30)
        val adaptive = LivePoseTuningBenchmark.measure(LivePoseStabilizer.Config(), 30)
        assertTrue(adaptive.movingRmse < fixed.movingRmse * 0.5)
    }
}
