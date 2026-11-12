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
import androidx.camera.core.ImageProxy
import java.util.concurrent.ExecutorService
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

/** Research input-format ABBA after same-frame pixel gate; unchanged production body/model/preview. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class LiveCameraInputFormatLightObserverInstrumentedTest {
    @Test fun compareFormatsWithOriginalExportsOutsideMeasuredWindows() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val directory=File(context.getExternalFilesDir(null),"camera-input-format-light-observer-20261029");check(!directory.exists());directory.mkdirs()
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
            for ((index,rgba) in listOf(true,false,false,true).withIndex()) {
                val name="block${index+1}-"+if(rgba) "rgba" else "yuv"
                val folder=File(directory,name);folder.mkdirs();val lock=Any();val rows=mutableListOf<String>();val errors=mutableListOf<String>();val memory=JSONArray()
                var currentInput: JSONObject?=null
                var snapshotFinished=0L;var snapshotCount=0;var postScreenBaseline=0;var postScreenRealResults=0
                var delivered=0;var start=0L;var end=0L;var snapshot=false;var exportNs=0L;var actualMode="INITIALIZING";var baselineAfterBind=0;var viewsStart=JSONArray();var viewsEnd=JSONArray();var screenAt=0L;var screenCost=0L
                val observer=object : LiveCameraDiagnostics {
                    override fun onError(error: Throwable) { synchronized(lock) { errors+=error.toString() } }
                    override fun onFrame(sample: LiveCameraSample,bitmap: Bitmap,rawPose: PoseFrame) {
                        val s=sample;val raw=rawPose
                        val cpu=Debug.threadCpuTimeNanos();val tid=Process.myTid();val priority=Process.getThreadPriority(tid)
                        synchronized(lock) {
                            delivered++
                            if(screenAt>0 && screenCost>0 && s.resultNanos>screenAt+screenCost) postScreenRealResults++
                            val input=currentInput?.takeIf { it.getLong("capture_ns")==s.captureNanos }
                            val keys=listOf("format","width","height","rotation","planes","row0","pixel0","row1","pixel1","row2","pixel2","one_pixel_shift","full_crop")
                            val suffix=keys.joinToString(",") { input?.optInt(it,-1)?.toString() ?: "-1" }
                            rows+="${s.captureNanos},${s.analyzerArrivalNanos},${s.resultNanos},${s.inferenceNanos},${s.timestampSource},${s.modelSha256},${s.trackerResetCount},${s.admissionDrops},${s.nativeRecreationCount},$cpu,$tid,$priority,${bitmap.width},${bitmap.height},${raw.validity.status.name},$suffix"
                            if(start==0L && !snapshot && input!=null && actualMode=="REBOUND_FORMAT_PREVIEW_AND_ANALYSIS") {
                                val before=SystemClock.elapsedRealtimeNanos();val points=JSONArray()
                                File(folder,"original.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                                raw.landmarks.forEach { p -> points.put(JSONObject().put("id",p.id.index).put("image",JSONArray(listOf(p.image.x,p.image.y,p.image.z))).put("world",p.world?.let { JSONArray(listOf(it.xMeters,it.yMeters,it.zMeters)) } ?: JSONObject.NULL).put("visibility",p.visibility ?: JSONObject.NULL).put("presence",p.presence ?: JSONObject.NULL)) }
                                File(folder,"raw.json").writeText(JSONObject().put("capture_ns",s.captureNanos).put("result_ns",s.resultNanos).put("timestamp_source",s.timestampSource ?: JSONObject.NULL).put("raw_pose_timestamp_ms",raw.timestamp.value).put("raw_status",raw.validity.status.name).put("width",bitmap.width).put("height",bitmap.height).put("raw_landmarks",points).put("input_image",input ?: JSONObject.NULL).toString(2))
                                snapshotFinished=SystemClock.elapsedRealtimeNanos();exportNs=snapshotFinished-before;snapshotCount=delivered;snapshot=true
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
                            val provider=LiveReferenceActionActivity::class.java.getDeclaredField("cameraProvider").apply { isAccessible=true }.get(activity) as ProcessCameraProvider
                            val old=LiveReferenceActionActivity::class.java.getDeclaredField("imageAnalysis").apply { isAccessible=true }.get(activity) as ImageAnalysis
                            assertTrue(provider.isBound(old));old.clearAnalyzer();provider.unbindAll()
                            val view=LiveReferenceActionActivity::class.java.getDeclaredField("previewView").apply { isAccessible=true }.get(activity) as PreviewView
                            val rotation=view.display?.rotation ?: android.view.Surface.ROTATION_0
                            val replacement=Preview.Builder().setResolutionSelector(LiveCameraFrame.resolutionSelector()).setTargetRotation(rotation).build()
                            replacement.surfaceProvider=view.surfaceProvider
                            val analysis=ImageAnalysis.Builder().setResolutionSelector(LiveCameraFrame.resolutionSelector()).setTargetRotation(rotation)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(if(rgba) ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888 else ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build()
                            LiveReferenceActionActivity::class.java.getDeclaredField("imageAnalysis").apply { isAccessible=true }.set(activity,analysis)
                            val owner=LiveReferenceActionActivity::class.java.getDeclaredField("analyzerExecutor").apply { isAccessible=true }.get(activity) as ExecutorService
                            val actualAnalyzer=LiveReferenceActionActivity::class.java.getDeclaredMethod("analyzeFrame",ImageProxy::class.java).apply { isAccessible=true }
                            analysis.setAnalyzer(owner) { image ->
                                try {
                                    val internal=ImageAnalysis::class.java.getDeclaredField("mImageAnalysisAbstractAnalyzer").apply { isAccessible=true }.get(analysis)
                                    val shift=internal.javaClass.superclass.getDeclaredField("mOnePixelShiftEnabled").apply { isAccessible=true }.getBoolean(internal)
                                    val crop=image.cropRect
                                    currentInput=JSONObject().put("capture_ns",image.imageInfo.timestamp).put("format",image.format).put("width",image.width).put("height",image.height)
                                        .put("rotation",image.imageInfo.rotationDegrees).put("planes",image.planes.size).put("one_pixel_shift",if(shift)1 else 0)
                                        .put("full_crop",if(crop.left==0 && crop.top==0 && crop.right==image.width && crop.bottom==image.height)1 else 0)
                                    for(n in 0..2) { currentInput!!.put("row$n",image.planes.getOrNull(n)?.rowStride ?: -1).put("pixel$n",image.planes.getOrNull(n)?.pixelStride ?: -1) }
                                    // Execute the byte-identical app's complete analyzer, gate, model and processor.
                                    actualAnalyzer.invoke(activity,image)
                                } catch(error: Throwable) { synchronized(lock) { errors+=error.toString() };image.close() }
                                finally { currentInput=null }
                            }
                            val camera=provider.bindToLifecycle(activity,LiveCameraFrame.camera(provider),replacement,analysis)
                            val timestampSource=runCatching { androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
                                .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) }.getOrNull()
                            LiveReferenceActionActivity::class.java.getDeclaredField("cameraTimestampSource").apply { isAccessible=true }.set(activity,timestampSource)
                            assertTrue(provider.isBound(analysis) && provider.isBound(replacement))
                            actualMode="REBOUND_FORMAT_PREVIEW_AND_ANALYSIS"
                        }
                        synchronized(lock) { baselineAfterBind=delivered }
                        val warmupEnd=SystemClock.elapsedRealtime()+15_000L
                        while(SystemClock.elapsedRealtime()<warmupEnd) Thread.sleep(250)
                        synchronized(lock) { assertTrue("Missing pre-window image or post-export results",snapshot && delivered>=snapshotCount+3) }
                        scenario.onActivity { viewsStart=previewViews(it) }
                        screenAt=SystemClock.elapsedRealtimeNanos();val preScreen=inst.uiAutomation.takeScreenshot()
                        if(preScreen!=null) { try { File(folder,"screen.png").outputStream().use { preScreen.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { preScreen.recycle() } }
                        screenCost=SystemClock.elapsedRealtimeNanos()-screenAt
                        synchronized(lock) { postScreenBaseline=delivered }
                        val postScreenDeadline=SystemClock.elapsedRealtime()+20_000L
                        while(SystemClock.elapsedRealtime()<postScreenDeadline && synchronized(lock) { postScreenRealResults<3 }) Thread.sleep(100)
                        synchronized(lock) { assertTrue("No three post-screen results",postScreenRealResults>=3);start=SystemClock.elapsedRealtimeNanos() }
                        var nextMemory=start;val deadline=start+45_000_000_000L
                        while(SystemClock.elapsedRealtimeNanos()<deadline) {
                            if(SystemClock.elapsedRealtimeNanos()>=nextMemory) {
                                val info=Debug.MemoryInfo();Debug.getMemoryInfo(info);memory.put(JSONObject().put("elapsed_realtime_ns",SystemClock.elapsedRealtimeNanos()).put("total_pss_kib",info.totalPss));nextMemory+=5_000_000_000L
                            }

                            Thread.sleep(250)
                        }
                        end=SystemClock.elapsedRealtimeNanos();scenario.onActivity { viewsEnd=previewViews(it);it.cameraDiagnostics=null }
                    }
                } finally {
                    synchronized(lock) {
                        File(folder,"frames.csv").bufferedWriter().use { w -> w.write("capture_ns,analyzer_arrival_ns,result_ns,inference_ns,timestamp_source,model_sha256,tracker_resets,admission_drops,native_recreations,callback_thread_cpu_ns,callback_tid,callback_thread_priority,width,height,raw_status,input_format,input_width,input_height,input_rotation,input_planes,input_row0,input_pixel0,input_row1,input_pixel1,input_row2,input_pixel2,input_one_pixel_shift,input_full_crop\n");rows.forEach { w.write(it+"\n") } }
                        File(folder,"errors.json").writeText(JSONArray(errors).toString(2));File(folder,"memory.json").writeText(memory.toString(2))
                        val summary=JSONObject().put("block",index+1).put("rgba_mode",rgba).put("requested_input_format",if(rgba) "RGBA" else "YUV").put("preview_views_start",viewsStart).put("preview_views_end",viewsEnd).put("screen_capture_ns",screenAt).put("screen_export_ns",screenCost).put("screen_saved",File(folder,"screen.png").exists()).put("actual_mode",actualMode).put("measurement_start_ns",start).put("measurement_end_ns",end).put("warmup_ms",15_000).put("requested_measurement_ms",45_000).put("post_bind_baseline_frames",baselineAfterBind).put("all_callback_frames",delivered).put("snapshot_export_ns",exportNs).put("snapshot_saved",snapshot).put("snapshot_export_finished_ns",snapshotFinished).put("snapshot_export_outside_measurement",true).put("screen_export_outside_measurement",true).put("post_screen_baseline_frames",postScreenBaseline).put("model_sha256",identity.poseModelSha256).put("maximum_queue_capacity",1)
                        File(folder,"summary.json").writeText(summary.toString(2));blocks.put(summary)
                    }
                }
                synchronized(lock) { assertTrue("Observer errors: $errors",errors.isEmpty());assertTrue("Missing original snapshot",snapshot) }
            }
        } finally {
            ReferenceActionProfileStore.clear()
            File(directory,"summary.json").writeText(JSONObject().put("schema","senpai-camera-input-format-light-observer/1").put("api",Build.VERSION.SDK_INT).put("device",Build.FINGERPRINT).put("compiler",QualifiedRepCompiler.VERSION).put("model_sha256",identity.poseModelSha256).put("reference_key",rep.referenceKey).put("reference_fixture_sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).put("block_order",JSONArray(listOf("RGBA","YUV","YUV","RGBA"))).put("blocks",blocks).put("real_camera_capture_measured",true).put("generated_replay_timestamps",false).put("production_changed",false).put("readiness_points_earned",0).put("human_rep_precision",JSONObject.NULL).toString(2))
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
