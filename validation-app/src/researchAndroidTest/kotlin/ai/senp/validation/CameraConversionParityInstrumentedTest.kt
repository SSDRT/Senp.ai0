package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.motion.*
import ai.senp.pose.mediapipe.LiveMediaPipePoseEstimator
import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.SystemClock
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Research-only same-YUV-frame public toBitmap vs pinned library RGBA route.
 * Restricted library helpers are used only as an oracle, never added to production.
 * Pixel disagreement is retained for all six pairs; passing this test is not parity.
 */
@RunWith(AndroidJUnit4::class)
class CameraConversionParityInstrumentedTest {
    private fun sha(b: ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun pixels(bitmap: Bitmap): IntArray=IntArray(bitmap.width*bitmap.height).also { bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height) }
    private fun bytes(pixels: IntArray)=ByteBuffer.allocate(pixels.size*4).also { b -> pixels.forEach { b.putInt(it) } }.array()
    @Test fun retainSixOriginalFrameConversionPairsBeforeAnyPerformanceDecision() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val directory=File(context.getExternalFilesDir(null),"camerax-conversion-parity-20261029");check(!directory.exists());directory.mkdirs()
        val fixtureBytes=requireNotNull(javaClass.classLoader?.getResourceAsStream("qualified-rep-native.json.gz")).readBytes()
        val fixture=GZIPInputStream(fixtureBytes.inputStream()).bufferedReader().use { JSONObject(it.readText()) };val ref=fixture.getJSONObject("videos").getJSONObject("reference")
        val frames=frames(ref.getJSONArray("frames"));val extraction=VideoPoseExtraction(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),PoseSequence(VideoRole.REFERENCE,frames),VideoPoseDiagnostics(frames.size,frames.size,frames.count { it.validity.status==FrameValidityStatus.VALID },0,0,0,0,1,1))
        val identity=RepEvidenceFingerprint(ref.getString("source_video_sha256"),ref.getString("model_sha256"))
        val compiled=compileQualifiedRepFromExtraction(extraction,fixture.getString("exercise_id"),identity,QualifiedRepConfig(side=RepObservedSide.valueOf(fixture.optString("side","LEFT"))))
        assertTrue(compiled.toString(),compiled is QualifiedRepCompilation.Ready);val rep=(compiled as QualifiedRepCompilation.Ready).reference
        ReferenceActionProfileStore.set(PreparedReferenceAction(rep.profile,identity.referenceVideoSha256,15.0,repEvidenceFingerprint=identity,qualifiedRepReference=rep,repSource=extraction))
        inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").close()
        val worker=Executors.newSingleThreadExecutor();val rows=JSONArray();val errors=JSONArray();val done=CountDownLatch(1);val count=AtomicInteger()
        try {
            ActivityScenario.launch<LiveReferenceActionActivity>(Intent(context,LiveReferenceActionActivity::class.java).putExtra(LiveReferenceActionActivity.EXTRA_REFERENCE_SHA256,identity.referenceVideoSha256)).use { scenario ->
                val startup=AtomicInteger()
                scenario.onActivity { it.cameraDiagnostics=object : LiveCameraDiagnostics {
                    override fun onFrame(sample: LiveCameraSample,bitmap: Bitmap,rawPose: PoseFrame) { startup.incrementAndGet() }
                    override fun onError(error: Throwable) { synchronized(errors) { errors.put(error.toString()) } }
                } }
                val initialDeadline=SystemClock.elapsedRealtime()+25_000
                while(SystemClock.elapsedRealtime()<initialDeadline && startup.get()<3) Thread.sleep(100)
                assertTrue("No initial reference-selected camera results",startup.get()>=3)
                lateinit var host: LiveReferenceActionActivity;lateinit var provider: ProcessCameraProvider;lateinit var owner: ExecutorService
                scenario.onActivity { activity ->
                    host=activity;activity.cameraDiagnostics=null
                    provider=LiveReferenceActionActivity::class.java.getDeclaredField("cameraProvider").apply { isAccessible=true }.get(activity) as ProcessCameraProvider
                    val original=LiveReferenceActionActivity::class.java.getDeclaredField("imageAnalysis").apply { isAccessible=true }.get(activity) as ImageAnalysis
                    original.clearAnalyzer();provider.unbindAll()
                    owner=LiveReferenceActionActivity::class.java.getDeclaredField("analyzerExecutor").apply { isAccessible=true }.get(activity) as ExecutorService
                }
                owner.submit {
                    val field=LiveReferenceActionActivity::class.java.getDeclaredField("estimator").apply { isAccessible=true }
                    (field.get(host) as? LiveMediaPipePoseEstimator)?.close();field.set(host,null)
                }.get(20,TimeUnit.SECONDS)
                scenario.onActivity { activity ->
                    val view=LiveReferenceActionActivity::class.java.getDeclaredField("previewView").apply { isAccessible=true }.get(activity) as androidx.camera.view.PreviewView
                    val rotation=view.display?.rotation ?: android.view.Surface.ROTATION_0
                    val preview=Preview.Builder().setResolutionSelector(LiveCameraFrame.resolutionSelector()).setTargetRotation(rotation).build().also { it.surfaceProvider=view.surfaceProvider }
                    val analysis=ImageAnalysis.Builder().setResolutionSelector(LiveCameraFrame.resolutionSelector()).setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build()
                    LiveReferenceActionActivity::class.java.getDeclaredField("imageAnalysis").apply { isAccessible=true }.set(activity,analysis)
                    analysis.setAnalyzer(worker) { image ->
                        if(count.get()>=6 || errors.length()>0) { image.close();return@setAnalyzer }
                        val index=count.get();val folder=File(directory,"pair${index+1}");folder.mkdirs()
                        var converted: ImageProxy?=null
                        val sourceInfo=image.imageInfo
                        val reader=ImageReaderProxys.createIsolatedReader(image.width,image.height,PixelFormat.RGBA_8888,2)
                        try {
                            assertEquals(android.graphics.ImageFormat.YUV_420_888,image.format)
                            val analyzer=ImageAnalysis::class.java.getDeclaredField("mImageAnalysisAbstractAnalyzer").apply { isAccessible=true }.get(analysis)
                            val onePixel=analyzer.javaClass.superclass.getDeclaredField("mOnePixelShiftEnabled").apply { isAccessible=true }.getBoolean(analyzer)
                            val planes=JSONArray();val before=mutableListOf<String>()
                            image.planes.forEachIndexed { n,p ->
                                val b=p.buffer.duplicate();val raw=ByteArray(b.remaining());b.get(raw);val digest=sha(raw);before+=digest
                                File(folder,"source-plane$n.bin").writeBytes(raw)
                                planes.put(JSONObject().put("index",n).put("row_stride",p.rowStride).put("pixel_stride",p.pixelStride).put("buffer_position",p.buffer.position()).put("buffer_limit",p.buffer.limit()).put("bytes",raw.size).put("sha256",digest))
                            }
                            var row: JSONObject?=null
                            LiveCameraFrame.withUprightBitmap(image) { direct ->
                                val directPixels=pixels(direct);File(folder,"direct-yuv-upright.png").outputStream().use { direct.compress(Bitmap.CompressFormat.PNG,100,it) }
                                // Same already-delivered YUV bytes, no second shift/rotation in this oracle.
                                converted=requireNotNull(ImageProcessingUtil.convertYUVToRGB(image,reader,ByteBuffer.allocateDirect(image.width*image.height*4),0,false))
                                val rawRgb=requireNotNull(converted)
                                val forwarded=object : ForwardingImageProxy(rawRgb) { override fun getImageInfo(): ImageInfo=sourceInfo }
                                LiveCameraFrame.withUprightBitmap(forwarded) { rgba ->
                                    assertEquals(direct.width,rgba.width);assertEquals(direct.height,rgba.height)
                                    val referencePixels=pixels(rgba);File(folder,"via-rgba-upright.png").outputStream().use { rgba.compress(Bitmap.CompressFormat.PNG,100,it) }
                                    var different=0;var maxDifference=0;var absoluteSum=0L
                                    for(i in directPixels.indices) {
                                        if(directPixels[i]!=referencePixels[i]) different++
                                        for(shift in listOf(0,8,16,24)) { val d=kotlin.math.abs(((directPixels[i] ushr shift) and 255)-((referencePixels[i] ushr shift) and 255));maxDifference=maxOf(maxDifference,d);absoluteSum+=d }
                                    }
                                    row=JSONObject().put("index",index).put("source_capture_ns",sourceInfo.timestamp).put("rotation_degrees",sourceInfo.rotationDegrees)
                                        .put("source_format",image.format).put("source_width",image.width).put("source_height",image.height).put("source_crop",image.cropRect.toShortString())
                                        .put("actual_one_pixel_shift_enabled",onePixel).put("source_planes",planes).put("prepared_width",direct.width).put("prepared_height",direct.height)
                                        .put("different_pixels",different).put("total_pixels",directPixels.size).put("maximum_channel_difference",maxDifference).put("absolute_channel_difference_sum",absoluteSum)
                                        .put("direct_argb_sha256",sha(bytes(directPixels))).put("via_rgba_argb_sha256",sha(bytes(referencePixels)))
                                }
                            }
                            val after=image.planes.map { p -> val b=p.buffer.duplicate();val raw=ByteArray(b.remaining());b.get(raw);sha(raw) }
                            val result=requireNotNull(row).put("source_plane_bytes_unchanged",before==after)
                            File(folder,"receipt.json").writeText(result.toString(2));rows.put(result)
                            if(count.incrementAndGet()==6) done.countDown()
                        } catch(error: Throwable) { errors.put(error.toString());done.countDown() }
                        finally { converted?.close();image.close();reader.close() }
                    }
                    provider.bindToLifecycle(activity,LiveCameraFrame.camera(provider),preview,analysis)
                }
                assertTrue("Six conversion pairs exceeded60s",done.await(60,TimeUnit.SECONDS))
            }
        } finally {
            worker.shutdown();assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS));ReferenceActionProfileStore.clear()
            File(directory,"errors.json").writeText(errors.toString(2))
            File(directory,"summary.json").writeText(JSONObject().put("schema","senpai-camerax-conversion-parity/1").put("api",android.os.Build.VERSION.SDK_INT).put("device",android.os.Build.FINGERPRINT)
                .put("compiler",QualifiedRepCompiler.VERSION).put("rows",rows).put("requested_pairs",6).put("no_pose_inference_during_pairs",true).put("production_changed",false)
                .put("reference_fixture_sha256",sha(fixtureBytes)).put("human_rep_precision",JSONObject.NULL).put("readiness_points_earned",0).toString(2))
        }
        assertEquals(errors.toString(),0,errors.length());assertEquals(6,count.get())
    }
    private fun frames(rows: JSONArray)=(0 until rows.length()).map { i ->
        val row=rows.getJSONObject(i);val points=row.getJSONArray("landmarks");val detected=points.length()==33
        PoseFrame(TimestampMs(row.getLong("timestamp_ms")),i.toLong(),PoseLandmarkId.entries.map { id -> if(!detected) PoseLandmark(id,ImageLandmark(0.0,0.0,0.0),visibility=0.0,presence=0.0) else points.getJSONArray(id.index).let { p -> PoseLandmark(id,ImageLandmark(p.getDouble(0),p.getDouble(1),p.getDouble(2)),WorldLandmark(p.getDouble(5),p.getDouble(6),p.getDouble(7)),p.getDouble(3),p.getDouble(4)) } },if(detected) FrameValidity.Valid else FrameValidity(FrameValidityStatus.BLIND,0.0,setOf(FrameValidityReason.NO_PERSON)))
    }
}
