package ai.senp.video

import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.VideoPoseFailureKind
import ai.senp.core.contracts.VideoRole
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoDecoderInstrumentedTest {
    @Test fun missingInputIsCanonical() {
        val missing = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "missing.mp4")
        val result = SequentialVideoDecoder().decode(VideoRole.SOURCE, missing) { StageResult.Success(Unit) }
        assertTrue(result is StageResult.Failure)
        assertEquals(VideoPoseFailureKind.SOURCE_MISSING, (result as StageResult.Failure).failure.let { (it as ai.senp.core.contracts.AnalysisFailure.VideoPose).kind })
    }

    @Test fun corruptContainerIsCanonical() {
        val corrupt = File.createTempFile("senp-corrupt-", ".mp4", ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir)
            .apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        try {
            val result = SequentialVideoDecoder().decode(VideoRole.REFERENCE, corrupt) { StageResult.Success(Unit) }
            assertTrue(result is StageResult.Failure)
            assertEquals(VideoPoseFailureKind.CORRUPT_VIDEO, ((result as StageResult.Failure).failure as ai.senp.core.contracts.AnalysisFailure.VideoPose).kind)
        } finally { corrupt.delete() }
    }
}
