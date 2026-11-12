package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Independent skeletal labels never enter the engine. No fake MP33/confidence/weights. */
object UiprmdDevelopmentReplay {
    @JvmStatic fun main(args: Array<String>) = UiprmdSkeletalReplay.run(File(args[0]),File(args[1]))
}

/** Explicit validation entry point. No CLI option can authorize final-test subjects. */
object UiprmdValidationReplay {
    @JvmStatic fun main(args: Array<String>) = UiprmdSkeletalReplay.run(
        File(args[0]),File(args[1]),File(args[2]),File(args[3]),
    )
}

/** Versioned re-evaluation of the already-opened validation set; no final-test route. */
object UiprmdReadyFormValidationReplay {
    @JvmStatic fun main(args: Array<String>) = UiprmdSkeletalReplay.run(File(args[0]),File(args[1]),File(args[2]),
        readyFormValidationProtocol = File(args[3]))
}

/** Already-opened development/validation exports only; never a final-test entry point. */
object UiprmdSideBindingReplay {
    @JvmStatic fun main(args: Array<String>) = UiprmdSkeletalReplay.run(
        File(args[0]),File(args[1]),File(args[2]),sideBindingProtocol = File(args[3]),
        researchValidation = args[4].also { require(it in setOf("development","validation")) } == "validation",
        sideBindingEnabled = args[5].also { require(it in setOf("fixed","causal")) } == "causal",
    )
}

internal object UiprmdSkeletalReplay {
    private val json = Json { prettyPrint = true }
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    internal fun verifySubjects(manifest: JsonObject, validation: Boolean) {
        val expected = if (validation) setOf("05","06","07") else setOf("01","02","03","04")
        val scope = if (validation) "validation" else "development"
        require(manifest.getValue("schema").jsonPrimitive.content == "uiprmd-$scope-export/1")
        require(manifest.getValue("subjects_opened").jsonArray.map { it.jsonPrimitive.content }.toSet() == expected)
        require(manifest.getValue("validation_trajectories_opened").jsonPrimitive.boolean == validation)
        require(!manifest.getValue("test_trajectories_opened").jsonPrimitive.boolean)
        val records = manifest.getValue("records").jsonArray.map { it.jsonObject }
        val keys = records.map { Triple(it.getValue("subject").jsonPrimitive.content,
            it.getValue("movement").jsonPrimitive.content,it.getValue("correct").jsonPrimitive.boolean) }
        val expectedKeys = expected.flatMap { s -> listOf("m01","m07").flatMap { m -> listOf(true,false).map { Triple(s,m,it) } } }.toSet()
        require(keys.size == expectedKeys.size && keys.toSet() == expectedKeys)
    }

    fun run(directory: File, output: File, referenceDirectory: File = directory, validationProtocol: File? = null,
        sideBindingProtocol: File? = null, researchValidation: Boolean = false, sideBindingEnabled: Boolean = true,
        readyFormValidationProtocol: File? = null) {
        require(!researchValidation || sideBindingProtocol != null)
        require(validationProtocol == null || sideBindingProtocol == null)
        require(readyFormValidationProtocol == null || (validationProtocol == null && sideBindingProtocol == null && !researchValidation))
        val validation = validationProtocol != null || researchValidation || readyFormValidationProtocol != null
        val manifest = Json.parseToJsonElement(File(directory,"manifest.json").readText()).jsonObject
        verifySubjects(manifest,validation)
        if (readyFormValidationProtocol != null) {
            val protocol = Json.parseToJsonElement(readyFormValidationProtocol.readText()).jsonObject
            verifyReadyFormScope(protocol)
            require(sha(File(directory,"manifest.json")) == protocol.getValue("validation_manifest_sha256").jsonPrimitive.content)
            require(sha(File(referenceDirectory,"manifest.json")) == protocol.getValue("development_manifest_sha256").jsonPrimitive.content)
            val root = readyFormValidationProtocol.canonicalFile.parentFile.parentFile
            for (element in protocol.getValue("source_receipts").jsonArray) {
                val receipt = element.jsonObject;val file = File(root,receipt.getValue("path").jsonPrimitive.content).canonicalFile
                require(file.toPath().startsWith(root.toPath()))
                val actual = if ("sha256_lf" in receipt) MessageDigest.getInstance("SHA-256").digest(
                    file.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } else sha(file)
                require(actual == receipt.getValue(if ("sha256_lf" in receipt) "sha256_lf" else "sha256").jsonPrimitive.content)
            }
        }
        if (sideBindingProtocol != null) {
            val protocol = Json.parseToJsonElement(sideBindingProtocol.readText()).jsonObject
            require(protocol.getValue("schema").jsonPrimitive.content == "uiprmd-side-binding-protocol/1")
            require(protocol.getValue("compiler").jsonPrimitive.content == QualifiedRepCompiler.VERSION)
            require(protocol.getValue("policy").jsonPrimitive.content == ExperimentalRepSideBinding.VERSION)
            require(!protocol.getValue("allow_final_test_access").jsonPrimitive.boolean)
            val root = sideBindingProtocol.canonicalFile.parentFile.parentFile
            protocol.getValue("source_sha256").jsonObject.forEach { (path,digest) ->
                val file = File(root,path).canonicalFile
                require(file.toPath().startsWith(root.toPath()) && sha(file) == digest.jsonPrimitive.content) { "Frozen research source changed: $path" }
            }
            val scope = if (validation) "validation" else "development"
            require(sha(File(directory,"manifest.json")) == protocol.getValue("${scope}_manifest_sha256").jsonPrimitive.content)
            require(sha(File(referenceDirectory,"manifest.json")) == protocol.getValue("development_manifest_sha256").jsonPrimitive.content)
        }
        if (validationProtocol != null) {
            val protocol = Json.parseToJsonElement(validationProtocol.readText()).jsonObject
            require(protocol.getValue("compiler").jsonPrimitive.content == QualifiedRepCompiler.VERSION)
            require(!protocol.getValue("evaluation").jsonObject.getValue("allow_final_test_access").jsonPrimitive.boolean)
            require(manifest.getValue("candidate_protocol_sha256").jsonPrimitive.content == sha(validationProtocol))
        }
        val backend = manifest.getValue("backend_sha256").jsonPrimitive.content
        val pipeline = manifest.getValue("pipeline").jsonPrimitive.content
        require(pipeline == "uiprmd-vicon39-marker-angle/1")
        fun readRecord(root: File, row: JsonObject): JsonObject {
            val file = File(root,row.getValue("file").jsonPrimitive.content)
            require(file.canonicalFile.parentFile == root.canonicalFile && sha(file) == row.getValue("sha256").jsonPrimitive.content)
            return Json.parseToJsonElement(file.readText()).jsonObject.also { r ->
                for (key in listOf("subject","movement","correct")) require(r.getValue(key) == row.getValue(key))
                require(r.getValue("backend_sha256").jsonPrimitive.content == backend && r.getValue("pipeline").jsonPrimitive.content == pipeline)
                require(r.getValue("sample_period_ms").jsonPrimitive.int == 10)
            }
        }
        val records = manifest.getValue("records").jsonArray.map { readRecord(directory,it.jsonObject) }
        val references = if (!validation) records else {
            val referenceManifest = Json.parseToJsonElement(File(referenceDirectory,"manifest.json").readText()).jsonObject
            verifySubjects(referenceManifest,false)
            referenceManifest.getValue("records").jsonArray.map { it.jsonObject }.filter {
                it.getValue("subject").jsonPrimitive.content == "01" && it.getValue("correct").jsonPrimitive.boolean
            }.map { readRecord(referenceDirectory,it) }.also { require(it.size == 2) }
        }
        fun sequence(record: JsonObject, role: VideoRole): SpatialSequenceAnalysis {
            val frames = record.getValue("frames").jsonArray.mapIndexed { index,item ->
                val row = item.jsonObject;val time = row.getValue("timestamp_ms").jsonPrimitive.long
                require(time == index*10L)
                val values = row.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.double }
                SpatialObservationFrame(TimestampMs(time),SpatialEvidenceKind.THREE_D,null,null,null,
                    // Binary acquisition observability, not MediaPipe probability or expert form certainty.
                    SpatialIntrinsicDescriptor(values,if (values.isEmpty()) 0.0 else 1.0,values.mapValues { 1.0 }),0.0,
                    "uiprmd-s"+record.getValue("subject").jsonPrimitive.content,0)
            }
            return SpatialSequenceAnalysis(role,DurationMs(record.getValue("duration_ms").jsonPrimitive.long),
                ObservationSampling(inputNominalFramesPerSecond = 100.0,analysisFramesPerSecond = 100.0),frames,1.0)
        }
        val rows = records.map { record ->
            val subject = record.getValue("subject").jsonPrimitive.content
            val movement = record.getValue("movement").jsonPrimitive.content
            // Fixed cross-person reference, chosen without source outcomes. Final subjects will use s01 only.
            val referenceSubject = if (!validation && subject == "01") "02" else "01"
            val reference = references.single { it.getValue("subject").jsonPrimitive.content == referenceSubject &&
                it.getValue("movement").jsonPrimitive.content == movement && it.getValue("correct").jsonPrimitive.boolean }
            val fingerprint = RepEvidenceFingerprint(reference.getValue("source_sha256").jsonPrimitive.content,backend,pipeline,"vicon39-global-marker-proxies")
            val config = QualifiedRepConfig(side = RepObservedSide.valueOf(record.getValue("side").jsonPrimitive.content))
            val compiled = QualifiedRepCompiler.compile(sequence(reference,VideoRole.REFERENCE),record.getValue("exercise_id").jsonPrimitive.content,fingerprint,config)
            buildJsonObject {
                for (key in listOf("subject","movement","exercise_id","correct","side","source_member","source_sha256","episodes")) put(key,record.getValue(key))
                put("reference_subject",referenceSubject)
                put("reference_sha256",reference.getValue("source_sha256"))
                if (compiled is QualifiedRepCompilation.Unavailable) {
                    put("status","REFERENCE_UNAVAILABLE");put("failure",compiled.reason.name);put("detail",compiled.detail)
                    put("attempts",JsonArray(emptyList()))
                } else {
                    val ready = (compiled as QualifiedRepCompilation.Ready).reference
                    val engine = ready.newEngine();val attempts = mutableListOf<RepAttemptResult>()
                    // This changes the declared research variant, not the frozen RIGHT-side benchmark.
                    val sideBinding = if (sideBindingProtocol != null && sideBindingEnabled && movement == "m07") ExperimentalRepSideBinding(ready) else null
                    val bindingEvents = mutableListOf<JsonObject>()
                    sequence(record,VideoRole.SOURCE).let { source ->
                        var previousBinding: Triple<ExperimentalRepSideBinding.State,RepObservedSide?,String?>? = null
                        for (frame in source.frames) {
                            if (sideBinding == null) attempts += engine.evaluate(frame,backend,pipeline,fingerprint.declaredView).finalizedAttempts
                            else {
                                val result = sideBinding.evaluate(frame,backend,pipeline,fingerprint.declaredView)
                                val binding = Triple(result.state,result.side,result.reason)
                                if (binding != previousBinding) bindingEvents += buildJsonObject {
                                    put("timestamp_ms",frame.timestamp.value);put("state",result.state.name)
                                    put("side",result.side?.name);put("locked_ms",result.lockedMs);put("reason",result.reason)
                                    put("mapped_reference_key",result.output?.referenceKey)
                                }
                                previousBinding = binding
                                attempts += result.output?.finalizedAttempts.orEmpty()
                            }
                        }
                        attempts += if (sideBinding == null) engine.finish(source.duration.value).finalizedAttempts
                            else sideBinding.finish(source.duration.value)?.finalizedAttempts.orEmpty()
                    }
                    put("status","REPLAYED");put("reference_key",ready.referenceKey)
                    if (sideBinding != null) {
                        put("research_variant","standing-unilateral-shoulder-abduction-interchangeable-side")
                        put("binding_events",JsonArray(bindingEvents))
                    }
                    put("reference_self_cycles",ready.calibration?.acceptedSelfCycles ?: 0)
                    put("attempts",JsonArray(attempts.map { a -> buildJsonObject {
                        put("attempt_id",a.attemptId);put("started_ms",a.startedMs);put("completed_ms",a.completedMs)
                        put("verdict",a.verdict.name);put("maximum_progress",a.maximumProgress)
                        put("visible_fraction",a.visibleFraction);put("ambiguous_fraction",a.ambiguousFraction)
                        put("reasons",JsonArray(a.reasons.map { JsonPrimitive(it.name) }))
                        put("faults",JsonArray(a.faults.map { f -> buildJsonObject {
                            put("reason",f.reason.name);put("feature",f.feature);put("first_ms",f.firstEvidenceMs);put("confirmed_ms",f.confirmedMs)
                        } }))
                    } }))
                }
            }
        }
        val report = buildJsonObject {
            put("schema",if (validation) "uiprmd-validation-replay/1" else "uiprmd-development-replay/1");put("measurement_domain","skeletal")
            put("kind",if (validation) "validation-only-independent-skeletal-label-diagnostic" else "development-only-independent-label-diagnostic-not-held-out-accuracy")
            if (validationProtocol != null) {
                put("candidate_protocol_sha256",sha(validationProtocol))
                put("reference_manifest_sha256",sha(File(referenceDirectory,"manifest.json")))
                put("final_test_trajectories_opened",false)
            }
            if (readyFormValidationProtocol != null) {
                put("candidate_protocol_sha256",sha(readyFormValidationProtocol))
                put("reference_manifest_sha256",sha(File(referenceDirectory,"manifest.json")))
                put("final_test_trajectories_opened",false)
                put("scope","already-opened-validation")
            }
            put("manifest_sha256",sha(File(directory,"manifest.json")));put("compiler",QualifiedRepCompiler.VERSION)
            put("raw_landmark_network_trained",false);put("config","production-defaults-reference-match")
            if (sideBindingProtocol != null) {
                put("schema","uiprmd-side-binding-replay/1")
                put("kind","research-variant-selection-not-frozen-right-side-score-or-live-camera-accuracy")
                put("side_binding_policy",ExperimentalRepSideBinding.VERSION)
                put("candidate_protocol_sha256",sha(sideBindingProtocol))
                put("final_test_trajectories_opened",false)
                put("config",if (sideBindingEnabled) "production-defaults-with-opt-in-research-side-binding" else "production-defaults-fixed-side-control")
                put("mapping_mode",if (sideBindingEnabled) "causal" else "fixed")
                put("scope",if (validation) "validation" else "development")
            }
            put("rows",JsonArray(rows))
        }
        output.parentFile.mkdirs();output.writeText(json.encodeToString(JsonObject.serializer(),report))
        println("UI-PRMD ${if (validation) "validation" else "development"}: ${rows.count { it.getValue("status").jsonPrimitive.content == "REPLAYED" }}/${rows.size} recordings replayed; $output")
    }

    internal fun verifyReadyFormScope(protocol: JsonObject) {
        require(protocol.getValue("schema").jsonPrimitive.content == "ready-form-validation/1")
        require(protocol.getValue("compiler").jsonPrimitive.content == QualifiedRepCompiler.VERSION)
        require(protocol.getValue("validation_access").jsonPrimitive.boolean && !protocol.getValue("final_test_access").jsonPrimitive.boolean)
        require(protocol.getValue("subjects").jsonArray.map { it.jsonPrimitive.content } == listOf("05","06","07"))
        require(protocol.getValue("reference_subject").jsonPrimitive.content == "01")
    }
}
