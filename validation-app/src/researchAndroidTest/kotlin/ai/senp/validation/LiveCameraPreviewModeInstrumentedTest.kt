package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.motion.*
import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Research preview implementation ABBA; actual camera input and original clocks, no production change. */
@RunWith(AndroidJUnit4::class)
class LiveCameraPreviewModeInstrumentedTest {
    @Test fun compareCompatibleAndPerformancePreviewWithActualCameraEvidence() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val directory=File(context.getExternalFilesDir(null),"camera-preview-mode-20261029");check(!directory.exists());directory.mkdirs()
        val bytes=requireNotNull(javaClass.classLoader?.getResourceAsStream("qualified-rep-native.json.gz")).readBytes()
        val fixture=GZIPInputStream(bytes.inputStream()).bufferedReader().use { JSONObject(it.readText()) };val ref=fixture.getJSONObject("videos").getJSONObject("reference")
        val frames=frames(ref.getJSONArray("frames"));val extraction=VideoPoseExtraction(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),PoseSequence(VideoRole.REFERENCE,frames),VideoPoseDiagnostics(frames.size,frames.size,frames.count { it.validity.status==FrameValidityStatus.VALID },0,0,0,0,1,1))
        val identity=RepEvidenceFingerprint(ref.getString("source_video_sha256"),ref.getString("model_sha256"))
        val compiled=compileQualifiedRepFromExtraction(extraction,fixture.getString("exercise_id"),identity,QualifiedRepConfig(side=RepObservedSide.valueOf(fixture.optString("side","LEFT"))))
        assertTrue(compiled.toString(),compiled is QualifiedRepCompilation.Ready);val rep=(compiled as QualifiedRepCompilation.Ready).reference
        ReferenceActionProfileStore.set(PreparedReferenceAction(rep.profile,identity.referenceVideoSha256,15.0,repEvidenceFingerprint=identity,qualifiedRepReference=rep,repSource=extraction))
        inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").close()
        val blocks=JSONArray()
        try {
            for ((index,compatible) in listOf(true,false,false,true).withIndex()) {
                val name="block${index+1}-"+if(compatible) "compatible" else "performance"
                val folder=File(directory,name);folder.mkdirs();val lock=Any();val rows=mutableListOf<String>();val errors=mutableListOf<String>();val memory=JSONArray()
                var delivered=0;var start=0L;var end=0L;var snapshot=false;var exportNs=0L;var actualMode="INITIALIZING";var baselineAfterBind=0;var viewsStart=JSONArray();var viewsEnd=JSONArray();var screenAt=0L;var screenCost=0L
                val observer=object : LiveCameraDiagnostics {
                    override fun onError(error: Throwable) { synchronized(lock) { errors+=error.toString() } }
                    override fun onFrame(sample: LiveCameraSample,bitmap: Bitmap,rawPose: PoseFrame) {
                        val s=sample;val raw=rawPose
                        val cpu=Debug.threadCpuTimeNanos();val tid=Process.myTid();val priority=Process.getThreadPriority(tid)
                        synchronized(lock) {
                            delivered++
                            rows+="${s.captureNanos},${s.analyzerArrivalNanos},${s.resultNanos},${s.inferenceNanos},${s.timestampSource},${s.modelSha256},${s.trackerResetCount},${s.admissionDrops},${s.nativeRecreationCount},$cpu,$tid,$priority,${bitmap.width},${bitmap.height},${raw.validity.status.name}"
                            if(start>0 && !snapshot && s.resultNanos>=start+20_000_000_000L) {
                                val before=SystemClock.elapsedRealtimeNanos();val points=JSONArray()
                                File(folder,"original.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                                raw.landmarks.forEach { p -> points.put(JSONObject().put("id",p.id.index).put("image",JSONArray(listOf(p.image.x,p.image.y,p.image.z))).put("world",p.world?.let { JSONArray(listOf(it.xMeters,it.yMeters,it.zMeters)) } ?: JSONObject.NULL).put("visibility",p.visibility ?: JSONObject.NULL).put("presence",p.presence ?: JSONObject.NULL)) }
                                File(folder,"raw.json").writeText(JSONObject().put("capture_ns",s.captureNanos).put("result_ns",s.resultNanos).put("timestamp_source",s.timestampSource ?: JSONObject.NULL).put("raw_pose_timestamp_ms",raw.timestamp.value).put("raw_status",raw.validity.status.name).put("width",bitmap.width).put("height",bitmap.height).put("raw_landmarks",points).toString(2))
                                exportNs=SystemClock.elapsedRealtimeNanos()-before;snapshot=true
                            }
                        }
                    }
                }
                try {
                    val intent=Intent(context,LiveReferenceActionActivity::class.java).putExtra(LiveReferenceActionActivity.EXTRA_REFERENCE_SHA256,identity.referenceVideoSha256)
                    ActivityScenario.launch<LiveReferenceActionActivity>(intent).use { scenario ->
                        scenario.onActivity { it.cameraDiagnostics=observer }
                        val readyDeadline=SystemClock.elapsedRealtime()+25_000L
                        while(SystemClock.elapsedRealtime()<readyDeadline && synchronized(lock) { delivered<3 }) Thread.sleep(100)
                        synchronized(lock) { assertTrue("No initial camera results",delivered>=3) }
                        scenario.onActivity { activity ->
                            // Test-only access to this frozen app's owned use cases; rebinding stays on main.
                            val provider=LiveReferenceActionActivity::class.java.getDeclaredField("cameraProvider").apply { isAccessible=true }.get(activity) as ProcessCameraProvider
                            val analysis=LiveReferenceActionActivity::class.java.getDeclaredField("imageAnalysis").apply { isAccessible=true }.get(activity) as ImageAnalysis
                            assertTrue(provider.isBound(analysis))
                            val view=LiveReferenceActionActivity::class.java.getDeclaredField("previewView").apply { isAccessible=true }.get(activity) as PreviewView
                            view.implementationMode=if(compatible) PreviewView.ImplementationMode.COMPATIBLE else PreviewView.ImplementationMode.PERFORMANCE
                            val rotation=view.display?.rotation ?: android.view.Surface.ROTATION_0
                            val replacement=Preview.Builder().setResolutionSelector(LiveCameraFrame.resolutionSelector()).setTargetRotation(rotation).build()
                            replacement.surfaceProvider=view.surfaceProvider
                            provider.unbindAll();provider.bindToLifecycle(activity,LiveCameraFrame.camera(provider),replacement,analysis)
                            assertTrue(provider.isBound(analysis) && provider.isBound(replacement))
                            actualMode="REBOUND_PREVIEW_AND_ANALYSIS"
                        }
                        synchronized(lock) { baselineAfterBind=delivered }
                        val warmupEnd=SystemClock.elapsedRealtime()+15_000L
                        while(SystemClock.elapsedRealtime()<warmupEnd) Thread.sleep(250)
                        synchronized(lock) { assertTrue("No results after selected binding",delivered>=baselineAfterBind+3);start=SystemClock.elapsedRealtimeNanos() }
                        scenario.onActivity { viewsStart=previewViews(it) }
                        var nextMemory=start;val deadline=start+45_000_000_000L
                        while(SystemClock.elapsedRealtimeNanos()<deadline) {
                            if(SystemClock.elapsedRealtimeNanos()>=nextMemory) {
                                val info=Debug.MemoryInfo();Debug.getMemoryInfo(info);memory.put(JSONObject().put("elapsed_realtime_ns",SystemClock.elapsedRealtimeNanos()).put("total_pss_kib",info.totalPss));nextMemory+=5_000_000_000L
                            }
                            if(screenAt==0L && SystemClock.elapsedRealtimeNanos()>=start+25_000_000_000L) {
                                screenAt=SystemClock.elapsedRealtimeNanos();val screen=inst.uiAutomation.takeScreenshot()
                                if(screen!=null) { try { File(folder,"screen.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { screen.recycle() } }
                                screenCost=SystemClock.elapsedRealtimeNanos()-screenAt
                            }
                            Thread.sleep(250)
                        }
                        end=SystemClock.elapsedRealtimeNanos();scenario.onActivity { viewsEnd=previewViews(it);it.cameraDiagnostics=null }
                    }
                } finally {
                    synchronized(lock) {
                        File(folder,"frames.csv").bufferedWriter().use { w -> w.write("capture_ns,analyzer_arrival_ns,result_ns,inference_ns,timestamp_source,model_sha256,tracker_resets,admission_drops,native_recreations,callback_thread_cpu_ns,callback_tid,callback_thread_priority,width,height,raw_status\n");rows.forEach { w.write(it+"\n") } }
                        File(folder,"errors.json").writeText(JSONArray(errors).toString(2));File(folder,"memory.json").writeText(memory.toString(2))
                        val summary=JSONObject().put("block",index+1).put("compatible_mode",compatible).put("requested_implementation",if(compatible) "COMPATIBLE" else "PERFORMANCE").put("preview_views_start",viewsStart).put("preview_views_end",viewsEnd).put("screen_capture_ns",screenAt).put("screen_export_ns",screenCost).put("screen_saved",File(folder,"screen.png").exists()).put("actual_mode",actualMode).put("measurement_start_ns",start).put("measurement_end_ns",end).put("warmup_ms",15_000).put("requested_measurement_ms",45_000).put("post_bind_baseline_frames",baselineAfterBind).put("all_callback_frames",delivered).put("snapshot_export_ns",exportNs).put("snapshot_saved",snapshot).put("model_sha256",identity.poseModelSha256).put("maximum_queue_capacity",1)
                        File(folder,"summary.json").writeText(summary.toString(2));blocks.put(summary)
                    }
                }
                synchronized(lock) { assertTrue("Observer errors: $errors",errors.isEmpty());assertTrue("Missing original snapshot",snapshot) }
            }
        } finally {
            ReferenceActionProfileStore.clear()
            File(directory,"summary.json").writeText(JSONObject().put("schema","senpai-camera-preview-mode/1").put("api",Build.VERSION.SDK_INT).put("device",Build.FINGERPRINT).put("compiler",QualifiedRepCompiler.VERSION).put("model_sha256",identity.poseModelSha256).put("reference_key",rep.referenceKey).put("reference_fixture_sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).put("block_order",JSONArray(listOf("COMPATIBLE","PERFORMANCE","PERFORMANCE","COMPATIBLE"))).put("blocks",blocks).put("real_camera_capture_measured",true).put("generated_replay_timestamps",false).put("production_changed",false).put("readiness_points_earned",0).put("human_rep_precision",JSONObject.NULL).toString(2))
        }
    }
    private fun previewViews(activity: LiveReferenceActionActivity): JSONArray {
        val view=LiveReferenceActionActivity::class.java.getDeclaredField("previewView").apply { isAccessible=true }.get(activity) as View
        val result=JSONArray()
        fun visit(current: View) { result.put(current.javaClass.name);if(current is ViewGroup) for(i in 0 until current.childCount) visit(current.getChildAt(i)) }
        visit(view);return result
    }
    private fun frames(rows: JSONArray)=(0 until rows.length()).map { i ->
        val row=rows.getJSONObject(i);val points=row.getJSONArray("landmarks");val detected=points.length()==33
        PoseFrame(TimestampMs(row.getLong("timestamp_ms")),i.toLong(),PoseLandmarkId.entries.map { id -> if(!detected) PoseLandmark(id,ImageLandmark(0.0,0.0,0.0),visibility=0.0,presence=0.0) else points.getJSONArray(id.index).let { p -> PoseLandmark(id,ImageLandmark(p.getDouble(0),p.getDouble(1),p.getDouble(2)),WorldLandmark(p.getDouble(5),p.getDouble(6),p.getDouble(7)),p.getDouble(3),p.getDouble(4)) } },if(detected) FrameValidity.Valid else FrameValidity(FrameValidityStatus.BLIND,0.0,setOf(FrameValidityReason.NO_PERSON)))
    }
}
