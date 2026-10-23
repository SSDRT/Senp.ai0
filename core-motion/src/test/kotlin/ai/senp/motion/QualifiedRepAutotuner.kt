package ai.senp.motion

import java.io.File
import kotlin.math.max
import kotlin.random.Random
import kotlinx.serialization.json.*

/** Fully automated operating-point search against generated oracle motions; never human pose truth. */
object QualifiedRepAutotuner {
    private data class Score(val cases: Int, val good: Int, val goodAccepted: Int, val bad: Int, val badAccepted: Int,
        val badRejected: Int, val uncertain: Int, val uncertainAccepted: Int, val falseCounts: Int, val transientRejected: Int, val maxFaultDelayMs: Long) {
        val goodAcceptance get() = goodAccepted.toDouble()/good
        val qualifies get() = badAccepted == 0 && uncertainAccepted == 0 && falseCounts == 0 && transientRejected == 0 && badRejected == bad
        fun json() = buildJsonObject {
            put("cases",cases);put("oracle_good",good);put("oracle_good_accepted",goodAccepted);put("good_acceptance",goodAcceptance)
            put("oracle_bad",bad);put("oracle_bad_accepted",badAccepted);put("oracle_bad_rejected",badRejected)
            put("oracle_uncertain",uncertain);put("oracle_uncertain_accepted",uncertainAccepted);put("false_counts",falseCounts)
            put("brief_transients_hard_rejected",transientRejected)
            put("maximum_fault_confirmation_delay_ms",maxFaultDelayMs);put("generated_constraints_pass",qualifies)
        }
    }
    private val scenarios = listOf("clean","close","close_boundary","severe","partial","missing_peak","grey","transient","stationary","single_spike","start_mid_rep")
    private fun score(config: QualifiedRepConfig, seeds: List<Int>, frameRates: List<Int>): Score {
        var cases = 0;var good = 0;var accepted = 0;var bad = 0;var badAccepted = 0;var rejected = 0
        var uncertain = 0;var uncertainAccepted = 0;var falseCounts = 0;var transientRejected = 0;var maximumDelay = 0L
        for (exercise in QualifiedRepCompiler.supportedExerciseIds) for (seed in seeds) for (fps in frameRates) for (scenario in scenarios) {
            cases++
            val reference = QualifiedRepTestData.reference(exercise,config);val engine = reference.newEngine()
            val random = Random(seed);val quality = reference.requiredFeatures.first { it != reference.driverFeature }
            val sign = if (exercise in setOf("overhead_press","lateral_raise","push_up")) -1 else 1
            val scale = if (seed%2 == 0) .85 else 1.25
            var now = 0L;var output: QualifiedRepOutput? = null
            while (now <= (2400*scale).toLong()) {
                val phaseTime = (now/scale).toLong()
                val p = when (scenario) {
                    "stationary" -> 0.0
                    "single_spike" -> if (phaseTime in 650..680) 1.0 else 0.0
                    else -> QualifiedRepTestData.progress(now,when (scenario) { "partial" -> .45;"close","close_boundary" -> .91;else -> 1.0 },scale)
                }
                if (scenario != "start_mid_rep" || phaseTime >= 600) {
                    var frame = QualifiedRepTestData.frame(now,p,exercise)
                    val values = frame.intrinsicDescriptor.values.mapValues { (_,value) -> (value+random.nextDouble(-2.5,2.5)).coerceIn(0.0,180.0) }.toMutableMap()
                    val offset = when (scenario) {
                        "close" -> 9.0
                        "close_boundary" -> 11.0
                        "severe" -> if (phaseTime in 500..1100) 50.0 else 0.0
                        "grey" -> if (phaseTime in 500..1450) 25.0 else 0.0
                        "transient" -> if (phaseTime in 550..610) 50.0 else 0.0
                        else -> 0.0
                    }
                    values[quality] = (values.getValue(quality)+sign*offset).coerceIn(0.0,180.0)
                    val confidence = frame.intrinsicDescriptor.featureConfidences.toMutableMap()
                    if (scenario == "missing_peak" && p >= .82) confidence[quality] = .1
                    frame = frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(values = values,featureConfidences = confidence))
                    output = engine.evaluate(frame,QualifiedRepTestData.identity.poseModelSha256)
                    maximumDelay = max(maximumDelay,output.newFaults.maxOfOrNull { it.confirmedMs-it.firstEvidenceMs } ?: 0)
                }
                now += max(1,(1000.0/fps*random.nextDouble(.8,1.2)).toLong())
            }
            val counts = requireNotNull(output).counts
            when (scenario) {
                "clean","close","close_boundary" -> { good++;if (counts.qualified == 1) accepted++;if (counts.qualified > 1) falseCounts++ }
                "severe","partial" -> { bad++;badAccepted += counts.qualified;if (counts.rejected == 1) rejected++ }
                "stationary","single_spike","start_mid_rep" -> falseCounts += counts.qualified
                // A sub-dwell transient may remain acceptable or uncertain; it must not latch a severe rejection.
                "transient" -> { transientRejected += counts.rejected;if (counts.qualified > 1) falseCounts++ }
                else -> { uncertain++;uncertainAccepted += counts.qualified }
            }
        }
        return Score(cases,good,accepted,bad,badAccepted,rejected,uncertain,uncertainAccepted,falseCounts,transientRejected,maximumDelay)
    }
    @JvmStatic fun main(args: Array<String>) {
        val base = QualifiedRepConfig();val candidates = mutableListOf<Pair<QualifiedRepConfig,Score>>()
        for (allowance in listOf(10.0,12.0,16.0)) for (dwell in listOf(120L,160L,220L)) for (range in listOf(.80,.85,.90)) {
            val config = base.copy(acceptableAllowanceDegrees = allowance,faultConfirmationMs = dwell,requiredProgress = range)
            candidates += config to score(config,listOf(4,19),listOf(10,15,30))
        }
        val selected = candidates.filter { it.second.qualifies }.maxWithOrNull(compareBy<Pair<QualifiedRepConfig,Score>> { it.second.goodAcceptance }
            .thenBy { -it.second.maxFaultDelayMs }.thenBy { -kotlin.math.abs(it.first.acceptableAllowanceDegrees-base.acceptableAllowanceDegrees) }
            .thenBy { -kotlin.math.abs(it.first.requiredProgress-base.requiredProgress) })
        val candidate = selected?.first ?: base
        // These seeds/frame rates are not read during parameter selection.
        val baselineCheck = score(base,listOf(103,257),listOf(12,24,50))
        val candidateCheck = score(candidate,listOf(103,257),listOf(12,24,50))
        val report = buildJsonObject {
            put("kind","autonomous-generated-oracle-parameter-search-not-human-accuracy");put("release_qualified",false)
            put("candidate_count",candidates.size);put("training_seeds",JsonArray(listOf(4,19).map(::JsonPrimitive)))
            put("checking_seeds",JsonArray(listOf(103,257).map(::JsonPrimitive)))
            put("baseline_check",baselineCheck.json());put("candidate_check",candidateCheck.json())
            put("selected_parameters",parameters(candidate));put("current_production_defaults_changed",false)
            put("candidates",JsonArray(candidates.map { (config,result) -> buildJsonObject { put("parameters",parameters(config));put("generated_training",result.json()) } }))
            put("limits",JsonArray(listOf("Procedural angle features, not camera or human form truth.",
                "Parameter selection uses only training seeds; checking cases share the same generator family.",
                "A synthetic optimum is an experimental candidate, not automatic approval for live deployment.").map(::JsonPrimitive)))
        }
        val output = File(args.single());output.parentFile.mkdirs();output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),report))
        println("Autonomous parameter candidates ${candidates.size}; training cases ${candidates.sumOf { it.second.cases }}; checking cases ${baselineCheck.cases+candidateCheck.cases}; $output")
        println("Generated good acceptance: baseline ${baselineCheck.goodAcceptance}; candidate ${candidateCheck.goodAcceptance}; generated bad accepted ${candidateCheck.badAccepted}")
    }
    private fun parameters(config: QualifiedRepConfig) = buildJsonObject {
        put("acceptable_allowance_degrees",config.acceptableAllowanceDegrees);put("fault_confirmation_ms",config.faultConfirmationMs)
        put("required_progress",config.requiredProgress)
    }
}
