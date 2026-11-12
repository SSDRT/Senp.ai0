package ai.senp.motion

import java.io.File
import kotlin.math.max
import kotlin.random.Random
import kotlinx.serialization.json.*

/** Known generated motions test mechanics, not anatomical correctness or real-camera accuracy. */
object QualifiedRepBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val rows = mutableListOf<JsonObject>()
        for (confirmation in listOf(80L,160L,240L)) for (exercise in QualifiedRepCompiler.supportedExerciseIds)
            for (fps in listOf(10,15,30,60)) for (seed in listOf(4,19))
                for (scenario in listOf("good","close","severe","partial","missing_peak","grey","transient","stationary")) {
            val config = QualifiedRepConfig(faultConfirmationMs = confirmation)
            val reference = QualifiedRepTestData.reference(exercise,config)
            val engine = reference.newEngine()
            val random = Random(seed)
            val events = mutableListOf<RepAttemptResult>(); val faults = mutableListOf<RepFault>()
            var now = 0L; var last: QualifiedRepOutput? = null
            while (now <= 2400) {
                val p = if (scenario == "stationary") 0.0 else QualifiedRepTestData.progress(now,if (scenario == "partial") 0.45 else 1.0)
                var frame = QualifiedRepTestData.frame(now,p,exercise)
                val values = frame.intrinsicDescriptor.values.mapValues { (name,value) ->
                    if (name.startsWith("angle.")) (value+random.nextDouble(-1.5,1.5)).coerceIn(0.0,180.0) else value
                }.toMutableMap()
                val quality = reference.requiredFeatures.first { it != reference.driverFeature }
                val signedOffset = if (exercise in setOf("overhead_press","lateral_raise","push_up")) -1.0 else 1.0
                val offset = when (scenario) {
                    "close" -> 9.0
                    "severe" -> if (now in 500..1000) 50.0 else 0.0
                    "grey" -> if (now in 500..1450) 23.0 else 0.0
                    "transient" -> if (now in 550..610) 50.0 else 0.0
                    else -> 0.0
                }
                values[quality] = (values.getValue(quality)+offset*signedOffset).coerceIn(0.0,180.0)
                val confidences = frame.intrinsicDescriptor.featureConfidences.toMutableMap()
                if (scenario == "missing_peak" && p >= 0.82) confidences[quality] = 0.1
                frame = frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(values = values,featureConfidences = confidences))
                last = engine.evaluate(frame,QualifiedRepTestData.identity.poseModelSha256)
                events += last.finalizedAttempts; faults += last.newFaults
                now += max(1,(1000.0/fps*random.nextDouble(0.8,1.2)).toLong())
            }
            val counts = requireNotNull(last).counts
            val expected = when (scenario) {
                "good","close" -> counts.qualified == 1 && counts.rejected == 0
                "severe","partial" -> counts.rejected == 1 && counts.qualified == 0
                "missing_peak","grey" -> counts.unassessable == 1 && counts.qualified == 0
                "transient" -> counts.rejected == 0 // A low-cadence brief excursion may remain unassessable.
                else -> counts.finalized == 0
            }
            rows += buildJsonObject {
                put("confirmation_ms",confirmation);put("exercise",exercise);put("fps",fps);put("seed",seed);put("scenario",scenario)
                put("qualified",counts.qualified);put("rejected",counts.rejected);put("unassessable",counts.unassessable)
                put("event_count",events.size);put("passed",expected)
                put("max_fault_confirmation_delay_ms",faults.maxOfOrNull { it.confirmedMs-it.firstEvidenceMs } ?: 0)
            }
        }
        val report = buildJsonObject {
            put("kind","synthetic-causal-rep-acceptance-mechanics-not-human-accuracy")
            put("scenario_count",rows.size);put("failures",rows.count { !it.getValue("passed").jsonPrimitive.boolean })
            put("rows",JsonArray(rows))
        }
        val target = File(args.single());target.parentFile.mkdirs();target.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),report))
        println("Qualified-rep mechanism scenarios ${rows.size}; failures ${report.getValue("failures")}; report $target")
        check(rows.all { it.getValue("passed").jsonPrimitive.boolean }) { "Inspect the failing scenario rows" }
    }
}
