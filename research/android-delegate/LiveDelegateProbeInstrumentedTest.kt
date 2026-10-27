package ai.senp.pose.mediapipe.research

import ai.senp.pose.mediapipe.LiveMediaPipePoseEstimator
import ai.senp.pose.mediapipe.LivePoseModel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in source-set probe; never compiled into the normal app or default test suite. */
@RunWith(AndroidJUnit4::class)
class LiveDelegateProbeInstrumentedTest {
    @Test fun fixedNativeStreamsRecordActualDelegateRawEvidenceAndTotalCallTime() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.context
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"delegate-probe").apply { mkdirs() }
        val output=File(directory,"benchmark.json")
        check(!output.exists()) { "Preserve prior delegate evidence" }
        val bytes=context.assets.open("pose.jpg").use { it.readBytes() }
        val assetSha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val pose=BitmapFactory.decodeByteArray(bytes,0,bytes.size)!!
        val blank=Bitmap.createBitmap(pose.width,pose.height,Bitmap.Config.ARGB_8888)
        val rows=JSONArray()
        val started=SystemClock.elapsedRealtimeNanos()
        try {
            for(model in LivePoseModel.entries) for(scene in listOf("pose","blank")) for(requested in listOf(Delegate.CPU,Delegate.GPU)) {
                val image=if(scene=="pose") pose else blank
                val creationStart=SystemClock.elapsedRealtimeNanos()
                val estimator=LiveMediaPipePoseEstimator.create(context,model,
                    LiveMediaPipePoseEstimator.Config(preferredDelegate=requested))
                val creationNanos=SystemClock.elapsedRealtimeNanos()-creationStart
                estimator.use {
                    val frames=JSONArray()
                    repeat(10) { index ->
                        val timestampMs=index*67L // Declared static diagnostic replay, never original video PTS.
                        val callStart=SystemClock.elapsedRealtimeNanos()
                        val result=it.estimateDetailed(image,timestampMs)
                        val totalNanos=SystemClock.elapsedRealtimeNanos()-callStart
                        assertEquals(model.sha256,result.modelSha256)
                        assertEquals(model.assetPath,result.modelAssetPath)
                        assertTrue(totalNanos>=result.inferenceNanos && result.inferenceNanos>0)
                        assertFalse(image.isRecycled)
                        if(requested==Delegate.CPU) assertEquals(Delegate.CPU,result.delegate)
                        if(requested==Delegate.GPU && result.delegate==Delegate.CPU) assertNotNull(result.fallbackReason)
                        val raw=JSONArray()
                        // The mapper has placeholders for no-person frames. Never export those
                        // placeholders as observed MP33. Every actual pose keeps raw fields.
                        val native=result.rawFrame.landmarks.takeIf { points -> points.any { p -> p.world!=null } }.orEmpty()
                        if(scene=="pose") assertEquals(33,native.size) else assertTrue(native.isEmpty())
                        for(p in native) raw.put(JSONObject().put("id",p.id.index)
                            .put("image",JSONArray(listOf(p.image.x,p.image.y,p.image.z)))
                            .put("world",p.world?.let { w -> JSONArray(listOf(w.xMeters,w.yMeters,w.zMeters)) } ?: JSONObject.NULL)
                            .put("visibility",p.visibility ?: JSONObject.NULL).put("presence",p.presence ?: JSONObject.NULL))
                        frames.put(JSONObject().put("index",index).put("diagnostic_timestamp_ms",timestampMs)
                            .put("warmup",index<2).put("total_call_ns",totalNanos).put("inference_ns",result.inferenceNanos)
                            .put("actual_delegate",result.delegate.name).put("fallback_reason",result.fallbackReason ?: JSONObject.NULL)
                            .put("logical_resets",result.trackerResetCount).put("native_recreations",result.nativeTrackerRecreationCount)
                            .put("validity",result.rawFrame.validity.status.name).put("raw_landmarks",raw))
                    }
                    rows.put(JSONObject().put("model",model.name).put("model_sha256",model.sha256)
                        .put("requested_delegate",requested.name).put("scene",scene).put("creation_ns",creationNanos)
                        .put("width",pose.width).put("height",pose.height).put("frames",frames))
                }
            }
        } finally { pose.recycle();blank.recycle() }
        assertEquals(8,rows.length())
        output.writeText(JSONObject().put("schema","senpai-native-delegate-probe/1")
            .put("kind","static-image-native-diagnostic-not-camera-performance-or-human-rep-precision")
            .put("api",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("fingerprint",Build.FINGERPRINT)
            .put("fixture_sha256",assetSha).put("warmup_frames",2).put("measured_frames",8)
            .put("synthetic_diagnostic_timebase",true).put("real_camera_inference",false)
            .put("started_elapsed_ns",started).put("finished_elapsed_ns",SystemClock.elapsedRealtimeNanos())
            .put("rows",rows).toString(2))
    }
}
