package ai.senp.validation

import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native CameraX + model + lifecycle smoke. Virtual-scene coverage is not pose-accuracy evidence. */
@RunWith(AndroidJUnit4::class)
class LiveCameraInstrumentedTest {
    @Test fun cameraProducesFramesAfterLaunchAndPauseResumeAndClosesOnAnalyzerThread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").close()
        ActivityScenario.launch(LivePushUpActivity::class.java).use { scenario ->
            fun waitForFrames(after: Long): Long {
                val deadline = System.nanoTime() + 20_000_000_000L
                var count = 0L
                while (System.nanoTime() < deadline) {
                    scenario.onActivity { count = it.analyzedFrameCount }
                    if (count >= after + 3) return count
                    Thread.sleep(100)
                }
                assertTrue("Camera/model produced $count frames, expected >=${after + 3}", count >= after + 3)
                return count
            }
            val first = waitForFrames(0)
            recordCameraPreview(scenario,"pushup-launch")
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(750)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitForFrames(first)
            recordCameraPreview(scenario,"pushup-resumed")
        }
        instrumentation.waitForIdleSync()
    }
}
