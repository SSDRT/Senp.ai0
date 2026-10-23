package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.core.pipeline.VideoPoseExtractor
import ai.senp.motion.*
import ai.senp.pose.mediapipe.LivePoseModel
import ai.senp.sync.v2.InMemorySynchronizationPoseCache
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeNotNull
import kotlin.test.*

/** Opt-in replay of original cached observations through the actual Android session selection seam. */
class QualifiedReferenceBackendReplayTest {
    @Test fun productionSelectionMatchesFrozenReferenceOnlyRegistry() = runBlocking {
        val configured = System.getProperty("qualifiedReferenceReplayRoot")
        assumeNotNull(configured)
        val root = File(requireNotNull(configured))
        fun read(file: File, model: LivePoseModel): JsonObject =
            GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }.also {
                assertEquals(model.sha256,it.getValue("model_sha256").jsonPrimitive.content)
                assertEquals(66,it.getValue("sample_period_ms").jsonPrimitive.int)
            }
        fun files(directory: String) = File(root,directory).listFiles { f -> f.name.endsWith(".json.gz") }!!
            .associateBy { it.name.removeSuffix(".json.gz")+".mp4" }.also { assertEquals(48,it.size) }
        val full = files("rep-world-feature-cache")
        val heavy = files("autonomous-heavy-cache")
        assertEquals(full.keys,heavy.keys)
        val filesBySha = full.entries.associate { (name,file) ->
            val data = read(file,LivePoseModel.FULL);val other = read(heavy.getValue(name),LivePoseModel.HEAVY)
            assertEquals(data.getValue("source_video_sha256"),other.getValue("source_video_sha256"))
            assertEquals(data.getValue("frames").jsonArray.map { it.jsonObject.getValue("timestamp_ms") },
                other.getValue("frames").jsonArray.map { it.jsonObject.getValue("timestamp_ms") })
            data.getValue("source_video_sha256").jsonPrimitive.content to file
        }
        val registry = Json.parseToJsonElement(File(root,"autonomous-experiment-summary/reference-backend-selection.json").readText()).jsonArray
        assertEquals(32,registry.size)
        var extractions = 0
        val engine = ReferenceActionSessionEngine(object : VideoPoseExtractor {
            override suspend fun extract(role: VideoRole, source: VideoSource, sampling: SamplingConfiguration, model: PoseModelConfiguration): StageResult<VideoPoseExtraction> {
                assertEquals(VideoRole.REFERENCE,role);assertEquals(LivePoseModel.HEAVY.sha256,model.modelSha256.value)
                val data = read(heavy.getValue(source.uri),LivePoseModel.HEAVY)
                assertEquals(source.sha256.value,data.getValue("source_video_sha256").jsonPrimitive.content)
                extractions++
                return StageResult.Success(extraction(data))
            }
        },InMemorySynchronizationPoseCache(maximumEntries = 32))
        val results = registry.map { item ->
            val row = item.jsonObject
            val sha = row.getValue("reference_video_sha256").jsonPrimitive.content
            val data = read(filesBySha.getValue(sha),LivePoseModel.FULL)
            val fingerprint = RepEvidenceFingerprint(sha,LivePoseModel.FULL.sha256,declaredView = row.getValue("view").jsonPrimitive.content)
            val model = PoseModelConfiguration(Sha256(LivePoseModel.FULL.sha256))
            val request = ReferenceExtractionRequest(VideoSource(data.getValue("video").jsonPrimitive.content,Sha256(sha)),SamplingConfiguration(),model)
            val result = engine.prepareQualifiedReference(extraction(data),fingerprint,request,row.getValue("exercise").jsonPrimitive.content,
                QualifiedRepConfig(side = RepObservedSide.valueOf(row.getValue("side").jsonPrimitive.content)),
                model.copy(modelSha256 = Sha256(LivePoseModel.HEAVY.sha256),modelVariant = "pose-landmarker-heavy"))
            val selected = when (result) {
                is QualifiedReferencePreparation.Ready -> {
                    assertEquals(fingerprint.copy(poseModelSha256 = result.reference.fingerprint.poseModelSha256),result.reference.fingerprint)
                    assertEquals(request.sampling,result.request?.sampling)
                    assertFalse(result.reference.newEngine().finish(0).releaseQualified)
                    if (result.reference.fingerprint.poseModelSha256 == LivePoseModel.FULL.sha256) "full" else "heavy"
                }
                is QualifiedReferencePreparation.Unavailable -> "unavailable"
            }
            assertEquals(row.getValue("selected_model").jsonPrimitive.content,selected,"$sha ${row["side"]}")
            buildJsonObject {
                put("reference_video_sha256",sha);put("exercise",row.getValue("exercise"));put("side",row.getValue("side"));put("view",row.getValue("view"))
                put("selected_model",selected)
                put("attempts",buildJsonArray { result.attempts.forEach { attempt -> add(buildJsonObject {
                    put("model_sha256",attempt.modelSha256);put("failure",attempt.failure?.name?.let(::JsonPrimitive) ?: JsonNull);put("detail",attempt.detail)
                }) } })
            }
        }
        val choices = results.groupingBy { it.getValue("selected_model").jsonPrimitive.content }.eachCount()
        assertEquals(mapOf("full" to 7,"heavy" to 2,"unavailable" to 23),choices)
        val report = buildJsonObject {
            put("kind","production-reference-backend-selection-real-observation-replay-not-human-accuracy")
            put("reference_sides",32);put("primary_ready",7);put("fallback_ready",2);put("unavailable",23)
            put("fallback_extractions",extractions);put("release_qualified",false);put("rep_accuracy_measured",false)
            put("rows",JsonArray(results))
        }
        File(root,"integrated-reference-backend-replay.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),report))
    }

    private fun extraction(data: JsonObject): VideoPoseExtraction {
        val frames = data.getValue("frames").jsonArray.mapIndexed { index,item ->
            val row = item.jsonObject;val landmarks = row.getValue("landmarks").jsonArray
            val detected = landmarks.size == 33
            PoseFrame(TimestampMs(row.getValue("timestamp_ms").jsonPrimitive.long),index.toLong(),PoseLandmarkId.entries.map { id ->
                if (!detected) PoseLandmark(id,ImageLandmark(0.0,0.0,0.0),visibility = 0.0,presence = 0.0) else {
                    val p = landmarks[id.index].jsonArray.map { it.jsonPrimitive.double }
                    PoseLandmark(id,ImageLandmark(p[0],p[1],p[2]),WorldLandmark(p[5],p[6],p[7]),p[3],p[4])
                }
            },if (detected) FrameValidity.Valid else FrameValidity(FrameValidityStatus.BLIND,0.0,setOf(FrameValidityReason.NO_PERSON)))
        }
        val valid = frames.count { it.validity.status == FrameValidityStatus.VALID }
        return VideoPoseExtraction(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),PoseSequence(VideoRole.REFERENCE,frames),
            VideoPoseDiagnostics(frames.size,frames.size,valid,frames.size-valid,0,0,0,1,1))
    }
}
