package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** Recompile the already-frozen licensed RGB reference; no new activity/bout selection. */
object PreparationFaultMmfitAudit {
    private fun sha(file: File, normalized: Boolean=false): String {
        val raw=if(normalized) file.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8) else file.readBytes()
        return MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args[0]);val protocol=Json.parseToJsonElement(File(root,"models/preparation-fault-validation-protocol.json").readText()).jsonObject
        require(protocol.getValue("compiler").jsonPrimitive.content==QualifiedRepCompiler.VERSION && !protocol.getValue("final_test_access").jsonPrimitive.boolean)
        for(item in protocol.getValue("source_receipts").jsonArray) {
            val r=item.jsonObject;require(sha(File(root,r.getValue("path").jsonPrimitive.content),"sha256_lf" in r)==r.getValue(if("sha256_lf" in r) "sha256_lf" else "sha256").jsonPrimitive.content)
        }
        val directory=File(root,"test-artifacts/exercise-knowledge");val output=File(directory,"preparation-fault-mmfit-screen.json")
        check(!output.exists()) { "Preserve prior camera-breadth screen" }
        val old=Json.parseToJsonElement(File(directory,"mmfit/dense-proposals/screen.json").readText()).jsonObject
        val selected=old.getValue("selected").jsonObject
        val file=File(root,selected.getValue("cache_path").jsonPrimitive.content)
        require(sha(file)==selected.getValue("cache_sha256").jsonPrimitive.content)
        val payload=GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val raw=payload.getValue("frames").jsonArray.map { it.jsonObject }
        val interval=payload.getValue("interval").jsonObject
        MmfitReferenceCalibrationAudit.verifyRaw(raw,interval.getValue("start_ms").jsonPrimitive.long,interval.getValue("end_ms").jsonPrimitive.long)
        val bout=selected.getValue("bout").jsonObject
        val observations=raw.filter { it.getValue("timestamp_ms").jsonPrimitive.long in bout.getValue("start_ms").jsonPrimitive.long..bout.getValue("end_ms").jsonPrimitive.long }.map(QualifiedRepReplayTool::observation)
        val sequence=SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures=true)).analyzeSequence(
            CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(observations.last().timestamp.value+1),ObservationSampling(analysisFramesPerSecond=15.0),observations))
        val identity=RepEvidenceFingerprint(payload.getValue("source_video_sha256").jsonPrimitive.content,payload.getValue("model_sha256").jsonPrimitive.content,declaredView="MMFIT_FIXED_CAMERA_SEATED")
        val rows=mutableListOf<JsonObject>()
        for(side in RepObservedSide.entries) for(fit in listOf(RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE)) {
            val result=QualifiedRepCompiler.compile(sequence,"lateral_raise",identity,QualifiedRepConfig(side=side,referenceCurveFit=fit))
            val ready=(result as? QualifiedRepCompilation.Ready)?.reference
            rows+=buildJsonObject {
                put("side",side.name);put("fit",fit.name);put("status",if(ready!=null) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name)
                ready?.calibration?.let { put("observed_cycles",it.observedCycles);put("self_accepted_cycles",it.acceptedSelfCycles) }
            }
        }
        output.writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("kind","existing-real-RGB-reference-recalibration-not-human-accuracy");put("compiler",QualifiedRepCompiler.VERSION)
            put("rows",JsonArray(rows));put("all_existing_sides_retained",rows.all { it.getValue("status").jsonPrimitive.content=="READY" })
        }))
        check(rows.all { it.getValue("status").jsonPrimitive.content=="READY" })
        println("Existing MM-Fit camera reference retained in all four trials")
    }
}
