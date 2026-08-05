package ai.senp.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PoseContractsTest {
    @Test
    fun schemaContainsEveryNeutralLandmarkInStableOrder() {
        assertEquals(33, PoseLandmarkId.entries.size)
        assertEquals((0..32).toList(), PoseLandmarkId.entries.map(PoseLandmarkId::index))
        assertEquals(PoseLandmarkId.NOSE, PoseLandmarkId.fromIndex(0))
        assertEquals(PoseLandmarkId.RIGHT_FOOT_INDEX, PoseLandmarkId.fromIndex(32))
    }

    @Test
    fun rejectsCocoOnlyOrOtherwiseReducedPose() {
        assertThrows(IllegalArgumentException::class.java) {
            PoseFrame(0L, emptyList())
        }
    }

    @Test
    fun poseInputRequiresExactArgbDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            PoseInputFrame(0L, 2, 2, IntArray(3))
        }
    }

    @Test
    fun confidenceRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException::class.java) {
            LandmarkConfidence(1.1f, 0.5f)
        }
    }
}
