package ai.senp.validation

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

/** Native lifecycle evidence only. A requested mode is never proof of its chosen surface. */
internal fun <T : ComponentActivity> recordCameraPreview(scenario: ActivityScenario<T>, name: String) {
    val inst = InstrumentationRegistry.getInstrumentation()
    val directory = File(inst.targetContext.getExternalFilesDir(null), "preview-preference-evidence").apply { mkdirs() }
    val report = File(directory, "$name.json")
    check(!report.exists()) { "Preserve original preview evidence" }
    var receipt = JSONObject()
    var streaming = false
    val deadline = SystemClock.elapsedRealtime()+5_000L
    do {
        scenario.onActivity { activity ->
            val preview = activity.javaClass.getDeclaredField("previewView").apply { isAccessible=true }.get(activity) as PreviewView
            assertEquals(PreviewView.ImplementationMode.PERFORMANCE, preview.implementationMode)
            assertEquals(PreviewView.ScaleType.FIT_CENTER, preview.scaleType)
            val classes = mutableListOf<String>()
            fun visit(view: View) {
                classes += view.javaClass.name
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(preview)
            streaming = preview.previewStreamState.value == PreviewView.StreamState.STREAMING
            val surfaces = classes.filter { it == "android.view.SurfaceView" || it == "android.view.TextureView" }.distinct()
            assertTrue("Missing or mixed actual preview: $classes", surfaces.size == 1)
            receipt = JSONObject().put("activity", activity.javaClass.name)
                .put("requested_mode", preview.implementationMode.name).put("scale_type", preview.scaleType.name)
                .put("view_classes", JSONArray(classes)).put("actual_surface", surfaces.single())
                .put("stream_state", preview.previewStreamState.value?.name ?: JSONObject.NULL)
                .put("width", preview.width).put("height", preview.height)
                .put("view_observed_ns", SystemClock.elapsedRealtimeNanos())
        }
        if (!streaming) Thread.sleep(100)
    } while (!streaming && SystemClock.elapsedRealtime()<deadline)
    assertTrue("Preview did not report streaming", streaming)
    // STREAMING can precede visible SurfaceView pixels; preserve a separately timestamped image for review.
    val screenAt = SystemClock.elapsedRealtimeNanos()
    val screen = requireNotNull(inst.uiAutomation.takeScreenshot()) { "Missing actual display screenshot" }
    try {
        File(directory, "$name.png").outputStream().use { assertTrue(screen.compress(Bitmap.CompressFormat.PNG,100,it)) }
    } finally { screen.recycle() }
    receipt.put("screen_capture_ns", screenAt).put("screen_export_ns", SystemClock.elapsedRealtimeNanos()-screenAt)
        .put("kind", "actual-preview-lifecycle-not-human-pose-accuracy").put("human_rep_precision", JSONObject.NULL)
    report.writeText(receipt.toString(2))
}
