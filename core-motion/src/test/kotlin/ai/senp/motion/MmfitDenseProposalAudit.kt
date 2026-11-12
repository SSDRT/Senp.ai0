package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** Dense reference-only verification of every sparse activity seed, never human-form truth. */
object MmfitDenseProposalAudit {
    private fun sha(file: File, normalize: Boolean = false): String {
        val bytes = if (normalize) file.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8) else file.readBytes()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args[0]);val directory=File(root,"test-artifacts/exercise-knowledge/mmfit/dense-proposals")
        val protocolFile=File(root,"models/mmfit-dense-proposal-protocol.json")
        val protocol=Json.parseToJsonElement(protocolFile.readText()).jsonObject
        val extractionFile=File(directory,"extraction.json")
        val extraction=Json.parseToJsonElement(extractionFile.readText()).jsonObject
        val output=File(directory,"screen.json");check(!output.exists()) { "Preserve dense screen" }
        require(protocol.getValue("schema").jsonPrimitive.content=="mmfit-dense-proposal/1")
        require(protocol.getValue("selection").jsonPrimitive.content=="first-chronological-fine-eligible-bout")
        for (key in listOf("allow_final_test_access","annotation_archive_access","activity_gates_changed","production_gates_changed")) require(!protocol.getValue(key).jsonPrimitive.boolean)
        for (element in protocol.getValue("source_receipts").jsonArray) {
            val receipt=element.jsonObject;val normalized="sha256_lf" in receipt
            require(sha(File(root,receipt.getValue("path").jsonPrimitive.content),normalized)==receipt.getValue(if(normalized) "sha256_lf" else "sha256").jsonPrimitive.content)
        }
        require(extraction.getValue("protocol_sha256").jsonPrimitive.content==sha(protocolFile))
        val rows=mutableListOf<JsonObject>()
        if(extraction.getValue("selected")!=JsonNull) {
            val selected=extraction.getValue("selected").jsonObject
            val all=extraction.getValue("windows").jsonArray.map { it.jsonObject }
            val first=all.first { it.getValue("eligible_bouts").jsonArray.isNotEmpty() }
            require(selected.getValue("cache_path")==first.getValue("cache_path") && selected.getValue("bout")==first.getValue("eligible_bouts").jsonArray.first())
            val file=File(root,selected.getValue("cache_path").jsonPrimitive.content)
            require(file.canonicalFile.parentFile==directory.canonicalFile && sha(file)==selected.getValue("cache_sha256").jsonPrimitive.content)
            val payload=GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            require(payload.getValue("source_video_sha256")==protocol.getValue("video_sha256") && payload.getValue("model_sha256")==protocol.getValue("model_sha256"))
            require(payload.getValue("protocol_sha256").jsonPrimitive.content==sha(protocolFile) && payload.getValue("sample_period_ms").jsonPrimitive.int==66)
            val window=payload.getValue("interval").jsonObject
            val raw=payload.getValue("frames").jsonArray.map { it.jsonObject }
            MmfitReferenceCalibrationAudit.verifyRaw(raw,window.getValue("start_ms").jsonPrimitive.long,window.getValue("end_ms").jsonPrimitive.long)
            val bout=selected.getValue("bout").jsonObject
            val observations=raw.filter { it.getValue("timestamp_ms").jsonPrimitive.long in bout.getValue("start_ms").jsonPrimitive.long..bout.getValue("end_ms").jsonPrimitive.long }.map(QualifiedRepReplayTool::observation)
            val sequence=SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures=true)).analyzeSequence(
                CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(observations.last().timestamp.value+1),ObservationSampling(analysisFramesPerSecond=15.0),observations))
            val identity=RepEvidenceFingerprint(protocol.getValue("video_sha256").jsonPrimitive.content,protocol.getValue("model_sha256").jsonPrimitive.content,declaredView="MMFIT_FIXED_CAMERA_SEATED")
            for(side in RepObservedSide.entries) for(fit in listOf(RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE)) {
                val result=QualifiedRepCompiler.compile(sequence,"lateral_raise",identity,QualifiedRepConfig(side=side,referenceCurveFit=fit))
                val ready=(result as? QualifiedRepCompilation.Ready)?.reference
                rows+=buildJsonObject {
                    put("exercise","lateral_raise");put("variant","seated-dumbbell-lateral-raise");put("side",side.name);put("fit",fit.name)
                    put("status",if(ready!=null) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name)
                    put("detail",(result as? QualifiedRepCompilation.Unavailable)?.detail ?: "Ready under unchanged production gates")
                    put("observations",observations.size)
                    ready?.calibration?.let { c ->
                        put("selected_cycles",c.observedCycles);put("self_accepted_cycles",c.acceptedSelfCycles);put("self_acceptance_fraction",c.selfAcceptanceFraction)
                        put("first_selected_ms",c.firstSelectedMs);put("last_selected_ms",c.lastSelectedMs)
                    }
                }
            }
        }
        val report=buildJsonObject {
            put("schema","mmfit-dense-proposal-screen/1");put("kind","camera-reference-calibration-not-human-rep-precision")
            put("protocol_sha256",sha(protocolFile));put("extraction_sha256",sha(extractionFile));put("selected",extraction.getValue("selected"))
            put("rows",JsonArray(rows));put("ready_sides",JsonArray(rows.filter { it.getValue("status").jsonPrimitive.content=="READY" }.map { it.getValue("side") }.distinct()))
            put("production_changed",false);put("independent_form_precision",JsonNull);put("frozen_reference_cohort_changed",false)
        }
        output.writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report))
        println("MM-Fit dense seed verification: ${report["ready_sides"]}; no human-rep precision claim")
    }
}
