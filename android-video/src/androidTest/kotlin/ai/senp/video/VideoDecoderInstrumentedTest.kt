package ai.senp.video

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoDecoderInstrumentedTest {
    @Test
    fun missingInputIsTyped() {
        val missing = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "missing.mp4")
        assertThrows(VideoFailure.SourceMissing::class.java) {
            SequentialVideoDecoder().decode(missing) { }
        }
    }

    @Test
    fun corruptContainerIsTyped() {
        val corrupt = File.createTempFile(
            "senp-corrupt-",
            ".mp4",
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
        ).apply { writeBytes(ByteArray(4096) { index -> (index * 31).toByte() }) }
        try {
            assertThrows(VideoFailure.Corrupt::class.java) {
                SequentialVideoDecoder().decode(corrupt) { }
            }
        } finally {
            corrupt.delete()
        }
    }
}
