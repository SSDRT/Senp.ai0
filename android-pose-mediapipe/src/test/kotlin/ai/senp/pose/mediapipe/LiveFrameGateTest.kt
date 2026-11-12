package ai.senp.pose.mediapipe

import org.junit.Assert.*
import org.junit.Test

class LiveFrameGateTest {
    @Test fun dropsDuplicateBackwardSubMillisecondAndOverBudgetFramesWithoutInventingTime() {
        val gate = LiveFrameGate()
        assertEquals(1000L, gate.accept(1_000_000_000))
        assertNull(gate.accept(1_000_000_000))
        assertNull(gate.accept(999_000_000))
        assertNull(gate.accept(1_000_000_001))
        assertNull(gate.accept(1_016_000_000))
        assertEquals(1033L, gate.accept(1_033_000_000))
        assertEquals(4L, gate.droppedFrames)
    }
    @Test fun slowCaptureAndLongPauseKeepCaptureTime() {
        val gate = LiveFrameGate()
        assertNull(gate.accept(-1))
        assertEquals(0L, gate.accept(0))
        assertEquals(100L, gate.accept(100_000_000))
        assertEquals(10000L, gate.accept(10_000_000_000))
    }
}
