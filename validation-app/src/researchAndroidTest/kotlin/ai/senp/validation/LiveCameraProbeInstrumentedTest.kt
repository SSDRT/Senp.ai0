package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.motion.*
import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
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

/** Research-only actual CameraX probe; short duration and virtual input earn no accuracy/performance points. */
@RunWith(AndroidJUnit4::class)
class LiveCameraProbeInstrumentedTest {
    @Test fun recordCurrentActivityCameraDurationsWithoutSubstitutingAnUnknownClock() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val directory=File(context.getExternalFilesDir(null),"live-camera-probe-20261028");check(!directory.exists()) { "Preserve prior camera probe" };directory.mkdirs()
        val bytes=requireNotNull(javaClass.classLoader?.getResourceAsStream("qualified-rep-native.json.gz")).readBytes()
        val fixture=GZIPInputStream(bytes.inputStream()).bufferedReader().use { JSONObject(it.readText()) };val ref=fixture.getJSONObject("videos").getJSONObject("reference")
        val frames=frames(ref.getJSONArray("frames"));val extraction=VideoPoseExtraction(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),PoseSequence(VideoRole.REFERENCE,frames),VideoPoseDiagnostics(frames.size,frames.size,frames.count { it.validity.status==FrameValidityStatus.VALID },0,0,0,0,1,1))
        val identity=RepEvidenceFingerprint(ref.getString("source_video_sha256"),ref.getString("model_sha256"))
        val compiled=compileQualifiedRepFromExtraction(extraction,fixture.getString("exercise_id"),identity,QualifiedRepConfig(side=RepObservedSide.valueOf(fixture.optString("side","LEFT"))))
        assertTrue(compiled.toString(),compiled is QualifiedRepCompilation.Ready);val rep=(compiled as QualifiedRepCompilation.Ready).reference
        ReferenceActionProfileStore.set(PreparedReferenceAction(rep.profile,identity.referenceVideoSha256,15.0,repEvidenceFingerprint=identity,qualifiedRepReference=rep,repSource=extraction))
        inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").close()
        val lock=Any();val samples=mutableListOf<LiveCameraSample>();val errors=mutableListOf<String>();val memory=JSONArray();val snapshots=JSONArray()
        var start=0L;var end=0L;var snapshotIndex=0;val offsets=listOf(0L,30_000_000_000L,55_000_000_000L)
        var beforePause=0;var beforeResume=0;var afterResume=0;var nextMemory=0L
        val observer=object : LiveCameraDiagnostics {
            override fun onError(error: Throwable) { synchronized(lock) { errors+=error.toString() } }
            override fun onFrame(sample: LiveCameraSample,bitmap: Bitmap,rawPose: PoseFrame) {
                synchronized(lock) {
                    samples+=sample
                    if(start>0 && snapshotIndex<offsets.size && sample.resultNanos>=start+offsets[snapshotIndex]) {
                        val exportStart=SystemClock.elapsedRealtimeNanos();val name="capture-${sample.captureNanos}"
                        File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                        val joints=JSONArray();rawPose.landmarks.forEach { p -> joints.put(JSONObject().put("id",p.id.index).put("image",JSONArray(listOf(p.image.x,p.image.y,p.image.z))).put("world",p.world?.let { JSONArray(listOf(it.xMeters,it.yMeters,it.zMeters)) } ?: JSONObject.NULL).put("visibility",p.visibility ?: JSONObject.NULL).put("presence",p.presence ?: JSONObject.NULL)) }
                        val receipt=JSONObject().put("kind","original-virtual-camera-frame-and-raw-prediction-not-human-rep-truth").put("capture_ns",sample.captureNanos).put("analyzer_arrival_ns",sample.analyzerArrivalNanos).put("result_ns",sample.resultNanos)
                            .put("timestamp_source",sample.timestampSource ?: JSONObject.NULL).put("raw_pose_timestamp_ms",rawPose.timestamp.value).put("raw_status",rawPose.validity.status.name).put("width",bitmap.width).put("height",bitmap.height).put("raw_landmarks",joints)
                        File(directory,"$name.json").writeText(receipt.toString(2));snapshots.put(JSONObject().put("file",name).put("requested_offset_ns",offsets[snapshotIndex]).put("result_ns",sample.resultNanos).put("observer_export_ns",SystemClock.elapsedRealtimeNanos()-exportStart));snapshotIndex++
                    }
                }
            }
        }
        fun sampleMemory() { val info=Debug.MemoryInfo();Debug.getMemoryInfo(info);memory.put(JSONObject().put("elapsed_realtime_ns",SystemClock.elapsedRealtimeNanos()).put("total_pss_kib",info.totalPss)) }
        try {
            val intent=Intent(context,LiveReferenceActionActivity::class.java).putExtra(LiveReferenceActionActivity.EXTRA_REFERENCE_SHA256,identity.referenceVideoSha256)
            ActivityScenario.launch<LiveReferenceActionActivity>(intent).use { scenario ->
                scenario.onActivity { it.cameraDiagnostics=observer }
                val warmupEnd=SystemClock.elapsedRealtime()+30_000L
                while(SystemClock.elapsedRealtime()<warmupEnd) Thread.sleep(250)
                synchronized(lock) { assertTrue("No camera results in warmup",samples.isNotEmpty());beforePause=samples.size }
                scenario.moveToState(Lifecycle.State.CREATED);Thread.sleep(750);scenario.moveToState(Lifecycle.State.RESUMED)
                synchronized(lock) { beforeResume=samples.size }
                val resumeDeadline=SystemClock.elapsedRealtime()+20_000L
                while(SystemClock.elapsedRealtime()<resumeDeadline && synchronized(lock) { samples.size<beforeResume+3 }) Thread.sleep(100)
                synchronized(lock) { afterResume=samples.size;assertTrue("No camera results after resume",afterResume>=beforeResume+3);start=SystemClock.elapsedRealtimeNanos() }
                nextMemory=start
                val deadline=start+60_000_000_000L
                while(SystemClock.elapsedRealtimeNanos()<deadline) {
                    if(SystemClock.elapsedRealtimeNanos()>=nextMemory) { sampleMemory();nextMemory+=5_000_000_000L }
                    Thread.sleep(250)
                }
                end=SystemClock.elapsedRealtimeNanos();sampleMemory();scenario.onActivity { it.cameraDiagnostics=null }
            }
        } finally {
            ReferenceActionProfileStore.clear()
            synchronized(lock) {
                File(directory,"frames.csv").bufferedWriter().use { writer ->
                    writer.write("capture_ns,analyzer_arrival_ns,result_ns,inference_ns,timestamp_source,model_sha256,tracker_resets,admission_drops,native_recreations\n")
                    samples.forEach { s -> writer.write("${s.captureNanos},${s.analyzerArrivalNanos},${s.resultNanos},${s.inferenceNanos},${s.timestampSource},${s.modelSha256},${s.trackerResetCount},${s.admissionDrops},${s.nativeRecreationCount}\n") }
                }
                File(directory,"memory.json").writeText(memory.toString(2));File(directory,"errors.json").writeText(JSONArray(errors).toString(2));File(directory,"snapshots.json").writeText(snapshots.toString(2))
                File(directory,"summary.json").writeText(JSONObject().put("schema","senpai-live-camera-probe/1").put("kind","short-current-activity-camera-diagnostics-not-form-accuracy-or-ten-minute-qualification")
                    .put("api",Build.VERSION.SDK_INT).put("device",Build.FINGERPRINT).put("compiler",QualifiedRepCompiler.VERSION).put("model_sha256",identity.poseModelSha256).put("reference_key",rep.referenceKey)
                    .put("reference_fixture_sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).put("measurement_start_ns",start).put("measurement_end_ns",end)
                    .put("warmup_ms",30_000).put("requested_measurement_ms",60_000).put("frames_before_pause",beforePause).put("resume_baseline_frames",beforeResume).put("frames_after_resume",afterResume).put("maximum_queue_capacity",1).put("backpressure_strategy",0)
                    .put("real_camera_capture_measured",true).put("generated_replay_timestamps",false).put("readiness_points_earned",0).put("human_rep_precision",JSONObject.NULL).toString(2))
            }
        }
        synchronized(lock) {
            assertTrue("Camera observer reported errors: $errors",errors.isEmpty());assertTrue("No measured camera results",samples.any { it.resultNanos in start..end })
            assertTrue("Reference-selected model changed",samples.all { it.modelSha256==identity.poseModelSha256 })
        }
    }
    private fun frames(rows: JSONArray)=(0 until rows.length()).map { i ->
        val row=rows.getJSONObject(i);val points=row.getJSONArray("landmarks");val detected=points.length()==33
        PoseFrame(TimestampMs(row.getLong("timestamp_ms")),i.toLong(),PoseLandmarkId.entries.map { id -> if(!detected) PoseLandmark(id,ImageLandmark(0.0,0.0,0.0),visibility=0.0,presence=0.0) else points.getJSONArray(id.index).let { p -> PoseLandmark(id,ImageLandmark(p.getDouble(0),p.getDouble(1),p.getDouble(2)),WorldLandmark(p.getDouble(5),p.getDouble(6),p.getDouble(7)),p.getDouble(3),p.getDouble(4)) } },if(detected) FrameValidity.Valid else FrameValidity(FrameValidityStatus.BLIND,0.0,setOf(FrameValidityReason.NO_PERSON)))
    }
}
