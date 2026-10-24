package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.motion.*
import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/** Explicit opt-in real CameraX/native inference soak. Virtual scene is not form ground truth. */
@RunWith(AndroidJUnit4::class)
class LiveCameraSoakInstrumentedTest {
    @Test fun selectedReferenceModelRunsContinuouslyWithTimestampedLatencyAndPss() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Run explicitly with -e liveCameraSoak true",InstrumentationRegistry.getArguments().getString("liveCameraSoak") == "true")
        val context = instrumentation.targetContext
        val directory = File(context.getExternalFilesDir(null),"live-camera-soak").apply { mkdirs() }
        val fixture = GZIPInputStream(requireNotNull(javaClass.classLoader?.getResourceAsStream("qualified-rep-native.json.gz")))
            .bufferedReader().use { JSONObject(it.readText()) }
        val reference = fixture.getJSONObject("videos").getJSONObject("reference")
        val observations = reference.getJSONArray("frames")
        val frames = (0 until observations.length()).map { index ->
            val row = observations.getJSONObject(index);val landmarks = row.getJSONArray("landmarks")
            val detected = landmarks.length() == 33
            PoseFrame(TimestampMs(row.getLong("timestamp_ms")),index.toLong(),PoseLandmarkId.entries.map { id ->
                if (!detected) PoseLandmark(id,ImageLandmark(0.0,0.0,0.0),visibility = 0.0,presence = 0.0) else {
                    val p = landmarks.getJSONArray(id.index)
                    PoseLandmark(id,ImageLandmark(p.getDouble(0),p.getDouble(1),p.getDouble(2)),
                        WorldLandmark(p.getDouble(5),p.getDouble(6),p.getDouble(7)),p.getDouble(3),p.getDouble(4))
                }
            },if (detected) FrameValidity.Valid else FrameValidity(FrameValidityStatus.BLIND,0.0,setOf(FrameValidityReason.NO_PERSON)))
        }
        val extraction = VideoPoseExtraction(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),PoseSequence(VideoRole.REFERENCE,frames),
            VideoPoseDiagnostics(frames.size,frames.size,frames.count { it.validity.status == FrameValidityStatus.VALID },0,0,0,0,1,1))
        val identity = RepEvidenceFingerprint(reference.getString("source_video_sha256"),reference.getString("model_sha256"))
        val compiled = compileQualifiedRepFromExtraction(extraction,fixture.getString("exercise_id"),identity,
            QualifiedRepConfig(side = RepObservedSide.valueOf(fixture.optString("side","LEFT"))))
        assertTrue(compiled.toString(),compiled is QualifiedRepCompilation.Ready)
        val rep = (compiled as QualifiedRepCompilation.Ready).reference
        ReferenceActionProfileStore.set(PreparedReferenceAction(rep.profile,identity.referenceVideoSha256,15.0,
            repEvidenceFingerprint = identity,qualifiedRepReference = rep,repSource = extraction))
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").close()
        val lock = Any();val samples = mutableListOf<LiveCameraSample>();val memory = JSONArray();val errors = mutableListOf<String>()
        var nextSnapshot = Long.MAX_VALUE
        val observer = object : LiveCameraDiagnostics {
            override fun onError(error: Throwable) { synchronized(lock) { errors += error.toString() } }
            override fun onFrame(sample: LiveCameraSample,bitmap: Bitmap,rawPose: PoseFrame) {
                synchronized(lock) {
                    samples += sample
                    if (sample.resultNanos >= nextSnapshot) {
                        val name = "capture-${sample.captureNanos}"
                        File(directory,"$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,85,it) }
                        val joints = JSONArray()
                        rawPose.landmarks.forEach { p -> joints.put(JSONObject().put("id",p.id.index)
                            .put("image",JSONArray(listOf(p.image.x,p.image.y,p.image.z)))
                            .put("world",p.world?.let { JSONArray(listOf(it.xMeters,it.yMeters,it.zMeters)) })
                            .put("visibility",p.visibility).put("presence",p.presence)) }
                        File(directory,"$name.json").writeText(JSONObject().put("capture_ns",sample.captureNanos)
                            .put("result_ns",sample.resultNanos).put("status",rawPose.validity.status.name).put("raw_landmarks",joints).toString(2))
                        nextSnapshot += 300_000_000_000L
                    }
                }
            }
        }
        var start = 0L;var end = 0L
        fun sampleMemory() {
            val info = Debug.MemoryInfo();Debug.getMemoryInfo(info)
            memory.put(JSONObject().put("elapsed_realtime_ns",SystemClock.elapsedRealtimeNanos()).put("total_pss_kib",info.totalPss))
        }
        try {
            val intent = Intent(context,LiveReferenceActionActivity::class.java)
                .putExtra(LiveReferenceActionActivity.EXTRA_REFERENCE_SHA256,identity.referenceVideoSha256)
            ActivityScenario.launch<LiveReferenceActionActivity>(intent).use { scenario ->
                scenario.onActivity { it.cameraDiagnostics = observer }
                val warmupDeadline = SystemClock.elapsedRealtime()+30_000
                while (SystemClock.elapsedRealtime()<warmupDeadline) { Thread.sleep(500) }
                synchronized(lock) { assertTrue("No live model results during warmup",samples.isNotEmpty()) }
                // Verify the same reference-selected model survives lifecycle recreation before the uninterrupted window.
                scenario.moveToState(Lifecycle.State.CREATED);Thread.sleep(750);scenario.moveToState(Lifecycle.State.RESUMED)
                val resumeDeadline = SystemClock.elapsedRealtime()+20_000
                val beforeResume = synchronized(lock) { samples.size }
                while (SystemClock.elapsedRealtime()<resumeDeadline && synchronized(lock) { samples.size<beforeResume+3 }) Thread.sleep(100)
                synchronized(lock) { assertTrue("No results after resume",samples.size>=beforeResume+3) }
                start = SystemClock.elapsedRealtimeNanos()
                synchronized(lock) { nextSnapshot = start }
                val deadline = start+600_000_000_000L
                while (SystemClock.elapsedRealtimeNanos()<deadline) {
                    sampleMemory()
                    File(directory,"progress.json").writeText(JSONObject().put("kind","in-progress-device-specific-soak-not-accuracy")
                        .put("started_ns",start).put("now_ns",SystemClock.elapsedRealtimeNanos())
                        .put("frames",synchronized(lock) { samples.size }).put("memory",memory).toString(2))
                    Thread.sleep(minOf(5000L,((deadline-SystemClock.elapsedRealtimeNanos())/1_000_000).coerceAtLeast(1)))
                }
                end = SystemClock.elapsedRealtimeNanos();sampleMemory()
                scenario.onActivity { it.cameraDiagnostics = null }
            }
        } finally {
            ReferenceActionProfileStore.clear()
            synchronized(lock) {
                File(directory,"frames.csv").bufferedWriter().use { writer ->
                    writer.write("capture_ns,analyzer_arrival_ns,result_ns,inference_ns,timestamp_source,model_sha256,tracker_resets,admission_drops,native_recreations\n")
                    samples.forEach { s -> writer.write("${s.captureNanos},${s.analyzerArrivalNanos},${s.resultNanos},${s.inferenceNanos},${s.timestampSource},${s.modelSha256},${s.trackerResetCount},${s.admissionDrops},${s.nativeRecreationCount}\n") }
                }
                File(directory,"memory.json").writeText(memory.toString(2))
                File(directory,"errors.json").writeText(JSONArray(errors).toString(2))
            }
        }
        val measured = synchronized(lock) { samples.filter { it.resultNanos in start..end } }
        val clockValid = measured.isNotEmpty() && measured.all { it.timestampSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME &&
            it.captureNanos>0 && it.analyzerArrivalNanos>=it.captureNanos && it.resultNanos>=it.analyzerArrivalNanos }
        val latency = measured.map { (it.resultNanos-it.captureNanos)/1e6 }.sorted()
        val p95 = if (clockValid) latency[(ceil(latency.size*.95).toInt()-1).coerceAtLeast(0)] else null
        val durationMs = (end-start)/1e6;val fps = measured.size/(durationMs/1000)
        fun pssWindow(from: Long,to: Long): Double {
            val values = (0 until memory.length()).map { memory.getJSONObject(it) }
                .filter { it.getLong("elapsed_realtime_ns") in from..to }.map { it.getDouble("total_pss_kib")/1024 }
            return values.average()
        }
        val firstPss = pssWindow(start,start+120_000_000_000L);val lastPss = pssWindow(end-120_000_000_000L,end+1_000_000_000L)
        val maxPss = (0 until memory.length()).maxOf { memory.getJSONObject(it).getDouble("total_pss_kib")/1024 }
        val mismatches = synchronized(lock) { samples.count { it.modelSha256 != identity.poseModelSha256 } }
        val performancePass = durationMs>=600000 && clockValid && requireNotNull(p95)<=200 && fps>=5 && mismatches==0 && errors.isEmpty()
        val memoryPass = performancePass && maxPss<=768 && lastPss<firstPss*1.10
        val report = JSONObject().put("schema","senpai-live-camera-soak/1").put("kind","device-specific-real-native-camera-inference-not-human-form-accuracy")
            .put("api",Build.VERSION.SDK_INT).put("device",Build.FINGERPRINT).put("emulator",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
            .put("model_sha256",identity.poseModelSha256).put("compiler",QualifiedRepCompiler.VERSION)
            .put("measurement_start_ns",start).put("measurement_end_ns",end).put("duration_ms",durationMs).put("delivered_frames",measured.size)
            .put("delivered_fps",fps).put("clock_comparable",clockValid).put("p95_capture_to_result_ms",p95 ?: JSONObject.NULL)
            .put("timestamp_sources",JSONArray(measured.map { it.timestampSource }.distinct())).put("model_mismatch_count",mismatches)
            .put("analyzer_errors",JSONArray(errors)).put("backpressure_strategy",ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .put("maximum_queue_capacity",1).put("queue_capacity_basis","CameraX KEEP_ONLY_LATEST API contract; no concurrent analyzer execution")
            .put("maximum_pss_mib",maxPss).put("first_window_pss_mib",firstPss).put("last_window_pss_mib",lastPss)
            .put("post_warmup_windows_ms",120000).put("performance_pass",performancePass).put("memory_pass",memoryPass)
        File(directory,"summary.json").writeText(report.toString(2))
        assertTrue("Invalid capture clock or performance criteria failed: $report",performancePass)
        assertTrue("PSS criteria failed: $report",memoryPass)
    }
}
