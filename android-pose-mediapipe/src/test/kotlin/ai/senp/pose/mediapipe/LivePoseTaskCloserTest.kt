package ai.senp.pose.mediapipe

import org.junit.Assert.*
import org.junit.Test

class LivePoseTaskCloserTest {
    @Test fun inferenceErrorsStillThrowTheOriginalException() {
        val closer=LivePoseTaskCloser();val error=IllegalStateException("graph Process failed")
        assertSame(error,assertThrows(IllegalStateException::class.java) { closer.onError(error) })
    }

    @Test fun aReportedGraphErrorDuringCloseDoesNotSkipNativeTeardown() {
        val closer=LivePoseTaskCloser();val error=IllegalStateException("earlier Process failure")
        var teardownFinished=false
        val errors=closer.close {
            // TaskRunner.close reports the earlier graph error before graph.tearDown.
            closer.onError(error)
            teardownFinished=true
        }
        assertTrue(teardownFinished);assertEquals(1,errors.size);assertSame(error,errors.single())
    }

    @Test fun unexpectedDirectShutdownFailuresAreNotSuppressed() {
        val closer=LivePoseTaskCloser();val error=IllegalArgumentException("unexpected close failure")
        assertSame(error,assertThrows(IllegalArgumentException::class.java) { closer.close { throw error } })
        assertSame(error,assertThrows(IllegalArgumentException::class.java) { closer.onError(error) })
    }

    @Test fun successfulShutdownHasNoErrorsAndDoesNotChangeLaterErrorSemantics() {
        val closer=LivePoseTaskCloser();assertTrue(closer.close { }.isEmpty())
        val error=IllegalStateException("after close")
        assertSame(error,assertThrows(IllegalStateException::class.java) { closer.onError(error) })
    }
}
