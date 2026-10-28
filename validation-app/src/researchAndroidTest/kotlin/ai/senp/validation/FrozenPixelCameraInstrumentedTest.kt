package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.motion.*
import ai.senp.pose.mediapipe.*
import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Static frozen model pixels under quiet/active CameraX. Never camera-input pose accuracy/latency. */
@RunWith(AndroidJUnit4::class)
class FrozenPixelCameraInstrumentedTest {
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun isolateFrozenModelInputFromConcurrentCameraWork() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val directory=File(context.getExternalFilesDir(null),"frozen-pixel-camera-20261029");check(!directory.exists());directory.mkdirs()
        val manifest=inst.context.assets.open("frozen-pixel/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(8,manifest.getInt("warmup"));assertEquals(24,manifest.getInt("measured"))
        val fixtureBytes=requireNotNull(javaClass.classLoader?.getResourceAsStream("qualified-rep-native.json.gz")).readBytes();assertEquals(manifest.getString("reference_fixture_sha256"),sha(fixtureBytes))
        val fixture=GZIPInputStream(fixtureBytes.inputStream()).bufferedReader().use { JSONObject(it.readText()) };val ref=fixture.getJSONObject("videos").getJSONObject("reference")
        val frames=frames(ref.getJSONArray("frames"));val extraction=VideoPoseExtraction(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),PoseSequence(VideoRole.REFERENCE,frames),VideoPoseDiagnostics(frames.size,frames.size,frames.count { it.validity.status==FrameValidityStatus.VALID },0,0,0,0,1,1))
        val identity=RepEvidenceFingerprint(ref.getString("source_video_sha256"),ref.getString("model_sha256"));assertEquals(LivePoseModel.FULL.sha256,identity.poseModelSha256)
        val compiled=compileQualifiedRepFromExtraction(extraction,fixture.getString("exercise_id"),identity,QualifiedRepConfig(side=RepObservedSide.valueOf(fixture.optString("side","LEFT"))));assertTrue(compiled is QualifiedRepCompilation.Ready)
        val rep=(compiled as QualifiedRepCompilation.Ready).reference
        ReferenceActionProfileStore.set(PreparedReferenceAction(rep.profile,identity.referenceVideoSha256,15.0,repEvidenceFingerprint=identity,qualifiedRepReference=rep,repSource=extraction))
        inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").close()
        val worker=Executors.newSingleThreadExecutor();worker.submit { Process.setThreadPriority(-2) }.get();val blocks=JSONArray()
        try {
            val cases=manifest.getJSONArray("cases");assertEquals(2,cases.length())
            for(caseIndex in 0 until cases.length()) {
                val case=cases.getJSONObject(caseIndex);val bytes=inst.context.assets.open(case.getString("asset")).use { it.readBytes() };assertEquals(case.getString("sha256"),sha(bytes))
                val decoded=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size));val scale=minOf(1f,640f/maxOf(decoded.width,decoded.height))
                val bitmap=if(scale==1f) requireNotNull(decoded.copy(Bitmap.Config.ARGB_8888,false)) else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,Matrix().apply { postScale(scale,scale) },true)
                decoded.recycle();val before=pixels(bitmap)
                try {
                    for ((order,cameraActive) in listOf(false,true,true,false).withIndex()) {
                        val name=case.getString("id")+"-block${order+1}";val folder=File(directory,name);folder.mkdirs();val rows=JSONArray();val errors=JSONArray();val prelude=JSONArray();val done=CountDownLatch(1)
                        val initStarted=System.nanoTime();val estimator=worker.submit<LiveMediaPipePoseEstimator> { LiveMediaPipePoseEstimator.create(context,LivePoseModel.FULL,PoseThresholds()) }.get();val initNs=System.nanoTime()-initStarted
                        var scenario: ActivityScenario<LiveReferenceActionActivity>?=null
                        fun measure(camera: ImageProxy?,sourceBitmap: Bitmap?,conversionNs: Long) {
                            val index=rows.length();if(index>=32) return
                            val cpuStart=Debug.threadCpuTimeNanos();val processStart=Process.getElapsedCpuTime();val start=System.nanoTime()
                            val estimate=estimator.estimateDetailed(bitmap,index*67L);val end=System.nanoTime();val cpuEnd=Debug.threadCpuTimeNanos();val processEnd=Process.getElapsedCpuTime()
                            assertEquals(identity.poseModelSha256,estimate.modelSha256);assertEquals(33,estimate.rawFrame.landmarks.size);assertFalse(bitmap.isRecycled)
                            val row=JSONObject().put("index",index).put("warmup",index<8).put("generated_model_timestamp_ms",index*67L).put("native_detect_ns",estimate.inferenceNanos).put("estimate_call_ns",end-start)
                                .put("estimate_owner_cpu_ns",if(cpuStart>0 && cpuEnd>=cpuStart) cpuEnd-cpuStart else JSONObject.NULL).put("estimate_process_cpu_ms",processEnd-processStart).put("owner_tid",Process.myTid()).put("owner_priority",Process.getThreadPriority(Process.myTid()))
                                .put("camera_conversion_ns",conversionNs).put("camera_capture_ns",camera?.imageInfo?.timestamp ?: JSONObject.NULL).put("camera_rotation",camera?.imageInfo?.rotationDegrees ?: JSONObject.NULL)
                                .put("raw_status",estimate.rawFrame.validity.status.name).put("native_recreations",estimate.nativeTrackerRecreationCount).put("tracker_resets",estimate.trackerResetCount)
                            rows.put(row)
                            if(index==8) {
                                File(folder,"model-input.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                                if(sourceBitmap!=null) File(folder,"actual-camera-source-not-model-input.png").outputStream().use { sourceBitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                                val points=JSONArray();estimate.rawFrame.landmarks.forEach { p -> points.put(JSONObject().put("id",p.id.index).put("image",JSONArray(listOf(p.image.x,p.image.y,p.image.z))).put("world",p.world?.let { JSONArray(listOf(it.xMeters,it.yMeters,it.zMeters)) } ?: JSONObject.NULL).put("visibility",p.visibility ?: JSONObject.NULL).put("presence",p.presence ?: JSONObject.NULL)) }
                                File(folder,"raw-model-prediction.json").writeText(JSONObject().put("generated_model_timestamp_ms",estimate.rawFrame.timestamp.value).put("kind","prediction-on-frozen-static-pixels-not-camera-source").put("raw_status",estimate.rawFrame.validity.status.name).put("raw_landmarks",points).toString(2))
                            }
                            if(index==31) done.countDown()
                        }
                        try {
                            if(cameraActive) {
                                val initialCount=AtomicInteger()
                                val observer=object : LiveCameraDiagnostics {
                                    override fun onError(error: Throwable) { synchronized(prelude) { prelude.put(JSONObject().put("error",error.toString())) } }
                                    override fun onFrame(sample: LiveCameraSample,bitmap: Bitmap,rawPose: PoseFrame) { synchronized(prelude) { prelude.put(JSONObject().put("capture_ns",sample.captureNanos).put("result_ns",sample.resultNanos).put("model_sha256",sample.modelSha256));initialCount.incrementAndGet() } }
                                }
                                scenario=ActivityScenario.launch(Intent(context,LiveReferenceActionActivity::class.java).putExtra(LiveReferenceActionActivity.EXTRA_REFERENCE_SHA256,identity.referenceVideoSha256))
                                scenario.onActivity { it.cameraDiagnostics=observer }
                                val readyDeadline=SystemClock.elapsedRealtime()+25_000L
                                while(SystemClock.elapsedRealtime()<readyDeadline && initialCount.get()<3) Thread.sleep(100)
                                assertTrue("No initial actual camera results",initialCount.get()>=3)
                                lateinit var host: LiveReferenceActionActivity
                                lateinit var analysis: ImageAnalysis
                                lateinit var controllerOwner: ExecutorService
                                scenario.onActivity { activity ->
                                    host=activity;activity.cameraDiagnostics=null
                                    val provider=LiveReferenceActionActivity::class.java.getDeclaredField("cameraProvider").apply { isAccessible=true }.get(activity) as ProcessCameraProvider
                                    analysis=LiveReferenceActionActivity::class.java.getDeclaredField("imageAnalysis").apply { isAccessible=true }.get(activity) as ImageAnalysis
                                    controllerOwner=LiveReferenceActionActivity::class.java.getDeclaredField("analyzerExecutor").apply { isAccessible=true }.get(activity) as ExecutorService
                                    assertTrue(provider.isBound(analysis));analysis.clearAnalyzer()
                                }
                                // Close the unused controller graph on its original owner before sampling.
                                // Exactly one probe graph performs inference in either condition.
                                controllerOwner.submit {
                                    val field=LiveReferenceActionActivity::class.java.getDeclaredField("estimator").apply { isAccessible=true }
                                    (field.get(host) as? LiveMediaPipePoseEstimator)?.close();field.set(host,null)
                                }.get(20,TimeUnit.SECONDS)
                                scenario.onActivity {
                                    analysis.setAnalyzer(worker) { image ->
                                        try {
                                            if(rows.length()<32 && errors.length()==0) {
                                                val conversionStart=System.nanoTime()
                                                LiveCameraFrame.withUprightBitmap(image) { source -> measure(image,source,System.nanoTime()-conversionStart) }
                                            }
                                        } catch(error: Throwable) { errors.put(error.toString());done.countDown() } finally { image.close() }
                                    }
                                }
                            } else worker.execute { try { repeat(32) { measure(null,null,0) } } catch(error: Throwable) { errors.put(error.toString());done.countDown() } }
                            assertTrue("Frozen-pixel block exceeded120s",done.await(120,TimeUnit.SECONDS))
                        } finally {
                            scenario?.close();worker.submit { estimator.close() }.get()
                            assertArrayEquals(before,pixels(bitmap))
                            File(folder,"rows.json").writeText(rows.toString(2));File(folder,"errors.json").writeText(errors.toString(2));File(folder,"camera-startup.json").writeText(prelude.toString(2))
                            blocks.put(JSONObject().put("case_id",case.getString("id")).put("block_order",order+1).put("camera_active",cameraActive).put("folder",name).put("rows",rows.length()).put("model_input_width",bitmap.width).put("model_input_height",bitmap.height).put("model_pixel_sha256",sha(before)).put("estimator_initialization_ns",initNs))
                        }
                        assertEquals(errors.toString(),0,errors.length());assertEquals(32,rows.length())
                    }
                } finally { bitmap.recycle() }
            }
        } finally {
            worker.shutdown();assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS));ReferenceActionProfileStore.clear()
            File(directory,"summary.json").writeText(JSONObject().put("schema","senpai-frozen-pixel-camera/1").put("api",android.os.Build.VERSION.SDK_INT).put("device",android.os.Build.FINGERPRINT).put("compiler",QualifiedRepCompiler.VERSION).put("model_sha256",identity.poseModelSha256).put("input_manifest",manifest).put("blocks",blocks).put("generated_model_timestamps",true).put("model_input_is_actual_camera_frame",false).put("human_rep_precision",JSONObject.NULL).put("readiness_points_earned",0).toString(2))
        }
    }
    private fun pixels(bitmap: Bitmap): ByteArray { val p=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(p,0,bitmap.width,0,0,bitmap.width,bitmap.height);return ByteBuffer.allocate(p.size*4).also { b -> p.forEach { b.putInt(it) } }.array() }
    private fun frames(rows: JSONArray)=(0 until rows.length()).map { i ->
        val row=rows.getJSONObject(i);val points=row.getJSONArray("landmarks");val detected=points.length()==33
        PoseFrame(TimestampMs(row.getLong("timestamp_ms")),i.toLong(),PoseLandmarkId.entries.map { id -> if(!detected) PoseLandmark(id,ImageLandmark(0.0,0.0,0.0),visibility=0.0,presence=0.0) else points.getJSONArray(id.index).let { p -> PoseLandmark(id,ImageLandmark(p.getDouble(0),p.getDouble(1),p.getDouble(2)),WorldLandmark(p.getDouble(5),p.getDouble(6),p.getDouble(7)),p.getDouble(3),p.getDouble(4)) } },if(detected) FrameValidity.Valid else FrameValidity(FrameValidityStatus.BLIND,0.0,setOf(FrameValidityReason.NO_PERSON)))
    }
}
