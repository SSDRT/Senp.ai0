package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.motion.*
import ai.senp.pose.mediapipe.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
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

/** Explicit research source set only. Static inputs/time stamps do not measure live FPS or accuracy. */
@RunWith(AndroidJUnit4::class)
class LiveStageProfileInstrumentedTest {
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun profileProductionComponentsOnFrozenStaticInputsWithoutClaimingCameraLatency() {
        val inst=InstrumentationRegistry.getInstrumentation();val assets=inst.context.assets;val target=inst.targetContext
        val spec=assets.open("stage-profile/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals("senpai-live-stage-profile-inputs/1",spec.getString("schema"));assertEquals(8,spec.getInt("warmup"));assertEquals(24,spec.getInt("measured"))
        val fixtureBytes=requireNotNull(javaClass.classLoader?.getResourceAsStream("qualified-rep-native.json.gz")).readBytes()
        assertEquals(spec.getString("reference_fixture_sha256"),sha(fixtureBytes))
        val fixture=GZIPInputStream(fixtureBytes.inputStream()).bufferedReader().use { JSONObject(it.readText()) };val reference=fixture.getJSONObject("videos").getJSONObject("reference")
        val identity=RepEvidenceFingerprint(reference.getString("source_video_sha256"),reference.getString("model_sha256"));assertEquals(LivePoseModel.FULL.sha256,identity.poseModelSha256)
        val frames=frames(reference.getJSONArray("frames"));val extraction=VideoPoseExtraction(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),PoseSequence(VideoRole.REFERENCE,frames),VideoPoseDiagnostics(frames.size,frames.size,frames.count { it.validity.status==FrameValidityStatus.VALID },0,0,0,0,1,1))
        val compiled=compileQualifiedRepFromExtraction(extraction,fixture.getString("exercise_id"),identity,QualifiedRepConfig());assertTrue(compiled.toString(),compiled is QualifiedRepCompilation.Ready)
        val rep=(compiled as QualifiedRepCompilation.Ready).reference
        val output=File(target.getExternalFilesDir(null),"live-stage-profile");check(!output.exists()) { "Preserve prior profile" };output.mkdirs()
        val cases=spec.getJSONArray("cases");assertEquals(4,cases.length());val results=JSONArray()
        for(caseIndex in 0 until cases.length()) {
            val case=cases.getJSONObject(caseIndex);val name=case.getString("id");val bytes=assets.open(case.getString("asset")).use { it.readBytes() };assertEquals(case.getString("sha256"),sha(bytes))
            val original=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size));val originalPixels=IntArray(original.width*original.height);original.getPixels(originalPixels,0,original.width,0,0,original.width,original.height)
            val generic=LiveReferenceActionProcessor(rep.profile,15.0)
            val qualified=LiveReferenceActionProcessor(rep.profile,15.0,qualifiedRepReference=rep,repEvidenceFingerprint=identity)
            val rows=JSONArray();var evidence: PoseFrame?=null;var preparedWidth=0;var preparedHeight=0
            try {
                LiveMediaPipePoseEstimator.create(target,LivePoseModel.FULL,PoseThresholds()).use { estimator ->
                    repeat(32) { index ->
                        val inputStart=System.nanoTime();val scale=minOf(1f,640f/maxOf(original.width,original.height))
                        // Same zero-rotation scaling as LiveCameraFrame; no CameraX conversion is timed here.
                        val input=if(scale==1f) original.copy(Bitmap.Config.ARGB_8888,false) else Bitmap.createBitmap(original,0,0,original.width,original.height,Matrix().apply { postScale(scale,scale) },true)
                        val inputDone=System.nanoTime();assertNotNull(input);val bitmap=requireNotNull(input)
                        try {
                            preparedWidth=bitmap.width;preparedHeight=bitmap.height
                            val estimateStart=System.nanoTime();val estimate=estimator.estimateDetailed(bitmap,index*67L);val estimateDone=System.nanoTime()
                            assertEquals(identity.poseModelSha256,estimate.modelSha256);assertEquals(33,estimate.rawFrame.landmarks.size);assertFalse(bitmap.isRecycled)
                            fun genericTime(): Long { val t=System.nanoTime();generic.update(estimate.frame,bitmap.width,bitmap.height,estimate.modelSha256,estimate.rawFrame);return System.nanoTime()-t }
                            fun qualifiedTime(): Long { val t=System.nanoTime();qualified.update(estimate.frame,bitmap.width,bitmap.height,estimate.modelSha256,estimate.rawFrame);return System.nanoTime()-t }
                            val genericNs: Long;val qualifiedNs: Long
                            if(index%2==0) { genericNs=genericTime();qualifiedNs=qualifiedTime() } else { qualifiedNs=qualifiedTime();genericNs=genericTime() }
                            require(estimate.inferenceNanos>0 && estimateDone-estimateStart>=estimate.inferenceNanos)
                            rows.put(JSONObject().put("index",index).put("warmup",index<8).put("generated_model_timestamp_ms",index*67L)
                                .put("input_preparation_ns",inputDone-inputStart).put("estimate_call_ns",estimateDone-estimateStart).put("native_detect_ns",estimate.inferenceNanos)
                                .put("other_estimator_ns",estimateDone-estimateStart-estimate.inferenceNanos).put("generic_update_ns",genericNs).put("qualified_update_ns",qualifiedNs)
                                .put("processor_order",if(index%2==0) "generic-first" else "qualified-first").put("raw_status",estimate.rawFrame.validity.status.name)
                                .put("native_recreation_count",estimate.nativeTrackerRecreationCount).put("tracker_reset_count",estimate.trackerResetCount))
                            if(index==8) {
                                evidence=estimate.rawFrame
                                File(output,"$name-input.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                            }
                        } finally { bitmap.recycle() }
                    }
                }
                val after=IntArray(originalPixels.size);original.getPixels(after,0,original.width,0,0,original.width,original.height);assertArrayEquals(originalPixels,after)
                val raw=JSONArray();requireNotNull(evidence).landmarks.forEach { p -> raw.put(JSONObject().put("id",p.id.index).put("image",JSONArray(listOf(p.image.x,p.image.y,p.image.z))).put("world",p.world?.let { JSONArray(listOf(it.xMeters,it.yMeters,it.zMeters)) } ?: JSONObject.NULL).put("visibility",p.visibility ?: JSONObject.NULL).put("presence",p.presence ?: JSONObject.NULL)) }
                File(output,"$name-raw.json").writeText(JSONObject().put("kind","static-input-regression-not-form-labels").put("generated_model_timestamp_ms",evidence!!.timestamp.value).put("source",case).put("raw_landmarks",raw).toString(2))
                results.put(JSONObject().put("case",case).put("prepared_width",preparedWidth).put("prepared_height",preparedHeight).put("rows",rows))
            } finally { original.recycle() }
        }
        File(output,"profile.json").writeText(JSONObject().put("schema","senpai-live-stage-profile/1").put("kind","static-input-native-processing-capacity-not-camera-or-form-accuracy")
            .put("api",Build.VERSION.SDK_INT).put("device",Build.FINGERPRINT).put("compiler",QualifiedRepCompiler.VERSION).put("model_sha256",identity.poseModelSha256)
            .put("reference_key",rep.referenceKey).put("input_manifest",spec).put("duration_clock","System.nanoTime").put("real_camera_capture_measured",false)
            .put("generated_replay_timestamps",true).put("human_rep_precision",JSONObject.NULL).put("readiness_points_earned",0).put("cases",results).toString(2))
    }
    private fun frames(rows: JSONArray)=(0 until rows.length()).map { index ->
        val row=rows.getJSONObject(index);val points=row.getJSONArray("landmarks");val detected=points.length()==33
        PoseFrame(TimestampMs(row.getLong("timestamp_ms")),index.toLong(),PoseLandmarkId.entries.map { id ->
            if(!detected) PoseLandmark(id,ImageLandmark(0.0,0.0,0.0),visibility=0.0,presence=0.0) else points.getJSONArray(id.index).let { p -> PoseLandmark(id,ImageLandmark(p.getDouble(0),p.getDouble(1),p.getDouble(2)),WorldLandmark(p.getDouble(5),p.getDouble(6),p.getDouble(7)),p.getDouble(3),p.getDouble(4)) }
        },if(detected) FrameValidity.Valid else FrameValidity(FrameValidityStatus.BLIND,0.0,setOf(FrameValidityReason.NO_PERSON)))
    }
}
