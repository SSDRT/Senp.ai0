package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** Real source observations, original confidence and PTS. Clip labels are metadata, never rep truth. */
object QualifiedRepReplayTool {
    private val json = Json { prettyPrint = true }
    private data class Video(val name: String, val payload: JsonObject, val spatial: SpatialSequenceAnalysis)
    @JvmStatic fun main(args: Array<String>) {
        val directory = File(args[0]); val destination = File(args[1]);destination.mkdirs()
        val spatial = SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures = true))
        val videos = directory.listFiles { f -> f.name.endsWith(".json.gz") }!!.sortedBy { it.name }.map { file ->
            val payload = GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            require(payload.getValue("sample_period_ms").jsonPrimitive.int == 66)
            val frames = payload.getValue("frames").jsonArray.map { row -> observation(row.jsonObject) }
            val sequence = CanonicalObservationSequence(VideoRole.SOURCE,DurationMs(frames.last().timestamp.value+1),ObservationSampling(analysisFramesPerSecond = 15.0),frames)
            Video(payload.getValue("video").jsonPrimitive.content,payload,spatial.analyzeSequence(sequence))
        }
        require(videos.size == 48)
        val ids = mapOf("squat" to "bodyweight_squat","push_up" to "push_up","bicep_curl" to "biceps_curl","shoulder" to "overhead_press")
        val profiles = mutableMapOf<String,ReferenceActionCompilation>()
        val selfChecks = mutableMapOf<String,JsonObject>()
        val compiler = ReferenceActionCompiler(ReferenceActionCompilerConfig(targetStateCount = 12,minimumSamplesPerState = 5,proportionsAreContextOnly = true))
        val rows = mutableListOf<JsonObject>()
        for (video in videos) for (side in RepObservedSide.entries) {
            require(video.name.matches(Regex("subject_[0-9]{3}_(bicep_curl|push_up|shoulder|squat)_(good|bad)_(front|side)\\.mp4")))
            val traceFile = File(destination,"${video.name.removeSuffix(".mp4")}-${side.name.lowercase()}.json")
            require(traceFile.canonicalFile.parentFile == destination.canonicalFile)
            check(!traceFile.exists() || traceFile.delete()) { "Cannot replace managed replay trace" }
            val exercise = video.payload.getValue("exercise").jsonPrimitive.content
            val view = video.payload.getValue("view").jsonPrimitive.content
            val subject = video.payload.getValue("subject").jsonPrimitive.content
            val referenceSubject = if (subject == "001") "005" else "001"
            val ref = videos.single { it.payload.getValue("subject").jsonPrimitive.content == referenceSubject &&
                it.payload.getValue("exercise").jsonPrimitive.content == exercise && it.payload.getValue("view").jsonPrimitive.content == view &&
                it.payload.getValue("quality").jsonPrimitive.content == "good" }
            val compiled = profiles.getOrPut(ref.name) { compiler.compile(ref.spatial) }
            val base = buildJsonObject {
                put("video",video.name);put("reference_video",ref.name);put("exercise",ids.getValue(exercise));put("side",side.name);put("view",view)
                put("weak_whole_clip_label",video.payload.getValue("quality"));put("frame_count",video.spatial.frames.size)
                put("video_sha256",video.payload.getValue("source_video_sha256"));put("reference_video_sha256",ref.payload.getValue("source_video_sha256"))
                put("evaluated_start_ms",video.spatial.frames.first().timestamp.value);put("evaluated_end_ms",video.spatial.duration.value)
            }
            val modelSha = video.payload.getValue("model_sha256").jsonPrimitive.content
            val fingerprint = RepEvidenceFingerprint(ref.payload.getValue("source_video_sha256").jsonPrimitive.content,modelSha,declaredView = view)
            val rep = QualifiedRepCompiler.compile(ref.spatial,ids.getValue(exercise),fingerprint,QualifiedRepConfig(side = side))
            if (rep is QualifiedRepCompilation.Unavailable) {
                rows += JsonObject(base+mapOf("status" to JsonPrimitive(rep.reason.name),"detail" to JsonPrimitive(rep.detail)));continue
            }
            val ready = (rep as QualifiedRepCompilation.Ready).reference
            val selfCheck = selfChecks.getOrPut(ready.referenceKey) {
                val selfEngine = ready.newEngine()
                val calibration = requireNotNull(ready.calibration)
                var self: QualifiedRepOutput? = null
                for (frame in ref.spatial.frames.filter { it.timestamp.value >= calibration.firstSelectedMs-400 && it.timestamp.value <= calibration.lastSelectedMs+200 }) {
                    self = selfEngine.evaluate(frame,modelSha,captureContext = view)
                }
                buildJsonObject {
                    put("observed_calibration_cycles",calibration.observedCycles)
                    put("accepted",self?.counts?.qualified ?: 0);put("rejected",self?.counts?.rejected ?: 0);put("unassessable",self?.counts?.unassessable ?: 0)
                }
            }
            val engine = ready.newEngine();val legacy = (compiled as? ReferenceActionCompilation.Success)?.let { ActionStateRecognizer(it.profile) }
            val trace = mutableListOf<JsonObject>();val attempts = mutableListOf<JsonObject>();val faults = mutableListOf<JsonObject>()
            var result: QualifiedRepOutput? = null
            for (frame in video.spatial.frames) {
                val old = legacy?.accept(frame)
                result = engine.evaluate(frame,modelSha,captureContext = view)
                result.finalizedAttempts.forEach { attempts += attempt(it) }
                result.newFaults.forEach { faults += fault(it) }
                trace += buildJsonObject {
                    put("timestamp_ms",frame.timestamp.value);put("stage",result.stage.name)
                    put("progress",result.progress?.let(::JsonPrimitive) ?: JsonNull);put("active_disqualified",result.activeDisqualified)
                    put("qualified_count",result.counts.qualified);put("legacy_raw_count",old?.completedRepetitions?.let(::JsonPrimitive) ?: JsonNull)
                    put("unavailable_reason",result.unavailableReason?.name?.let(::JsonPrimitive) ?: JsonNull)
                    put("values",buildJsonObject {
                        for (feature in ready.requiredFeatures) {
                            val name = side.observedFeature(feature,ready.config.mirrorMode)
                            val value = frame.intrinsicDescriptor.values[name]?.takeIf { (frame.intrinsicDescriptor.featureConfidences[name] ?: 0.0) >= ready.config.minimumConfidence }
                            put(name,value?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    })
                    put("bands",buildJsonObject {
                        for (feature in ready.requiredFeatures) {
                            val b = ready.band(feature,result.progress ?: 0.0,result.stage == RepStage.RETURNING)
                            put(feature,buildJsonObject {
                                put("lower",if (feature == ready.driverFeature && b.ruleId == null) minOf(ready.readyAngle,ready.farAngle) else b.lower)
                                put("upper",if (feature == ready.driverFeature && b.ruleId == null) maxOf(ready.readyAngle,ready.farAngle) else b.upper)
                                put("acceptable_allowance",b.acceptable);put("reject_allowance",b.reject)
                            })
                        }
                    })
                }
            }
            val finished = engine.finish(video.spatial.duration.value)
            finished.finalizedAttempts.forEach { attempts += attempt(it) }
            val record = buildJsonObject {
                base.forEach { (key,value) -> put(key,value) }
                put("status","REPLAYED");put("reference_key",ready.referenceKey);put("release_qualified",false)
                put("observed_reference_cycles",ready.calibration?.observedCycles ?: 0)
                put("reference_self_replay",selfCheck)
                put("counts",buildJsonObject {
                    put("qualified",finished.counts.qualified);put("correct",finished.counts.correct);put("acceptable",finished.counts.acceptable)
                    put("rejected",finished.counts.rejected);put("unassessable",finished.counts.unassessable)
                    put("legacy_raw",legacy?.finish()?.completedRepetitions?.let(::JsonPrimitive) ?: JsonNull)
                })
                put("attempts",JsonArray(attempts));put("faults",JsonArray(faults))
            }
            rows += record
            traceFile.writeText(
                json.encodeToString(JsonObject.serializer(),JsonObject(record+("trace" to JsonArray(trace)))))
        }
        val report = buildJsonObject {
            put("kind","real-video-cross-person-reference-replay-without-independent-rep-labels")
            put("video_count",videos.size);put("source_observations",videos.sumOf { it.spatial.frames.size });put("exercise_side_runs",rows.size)
            put("replayed",rows.count { it.getValue("status").jsonPrimitive.content == "REPLAYED" })
            put("rep_accuracy_measured",false);put("rows",JsonArray(rows))
        }
        File(destination,"summary.json").writeText(json.encodeToString(JsonObject.serializer(),report))
        println("Real video replay ${report.getValue("replayed")}/${rows.size}; source frames ${report.getValue("source_observations")}; $destination")
    }
    internal fun observation(row: JsonObject): CanonicalObservation {
        val points = row.getValue("landmarks").jsonArray.map { it.jsonArray.map { n -> n.jsonPrimitive.double } }
        val channels = listOf(false,true).map { world ->
            val values = PoseLandmarkId.entries.map { id ->
                val p = points.getOrNull(id.index)
                ObservationValue(id.name.lowercase(),if (p == null) List(3) { null } else if (world) p.subList(5,8) else p.subList(0,3),
                    List(3) { p != null },if (p == null) 0.0 else minOf(p[3],p[4]))
            }
            ObservationChannel(if (world) "human-pose-world" else "human-pose-image",1,if (world) "human_pose_3d" else "human_pose_2d",
                if (world) "world_metric_3d" else "image_normalized","primary-subject",listOf("x","y","z"),values,
                if (points.isEmpty()) ChannelAvailability.MISSING else ChannelAvailability.OBSERVED,values.map { it.confidence }.average())
        }
        return CanonicalObservation(TimestampMs(row.getValue("timestamp_ms").jsonPrimitive.long),channels)
    }
    private fun attempt(a: RepAttemptResult) = buildJsonObject {
        put("attempt_id",a.attemptId);put("started_ms",a.startedMs);put("completed_ms",a.completedMs);put("verdict",a.verdict.name)
        put("reasons",JsonArray(a.reasons.map { JsonPrimitive(it.name) }));put("maximum_progress",a.maximumProgress)
        put("visible_fraction",a.visibleFraction);put("ambiguous_fraction",a.ambiguousFraction)
    }
    private fun fault(f: RepFault) = buildJsonObject {
        put("reason",f.reason.name);put("feature",f.feature?.let(::JsonPrimitive) ?: JsonNull)
        put("first_evidence_ms",f.firstEvidenceMs);put("confirmed_ms",f.confirmedMs)
        put("deviation_degrees",f.deviationDegrees?.let(::JsonPrimitive) ?: JsonNull)
    }
}
