package ai.senp.motion

import kotlin.math.max
import kotlin.math.min

internal object QualifiedRepTestData {
    val identity = RepEvidenceFingerprint("a".repeat(64),"b".repeat(64))
    fun features(exercise: String, progress: Double): Map<String,Double> = buildMap {
        for (side in listOf("left","right")) {
            put("angle.${side}_knee",if (exercise == "bodyweight_squat") 170-100*progress else 95.0)
            put("angle.${side}_hip",when (exercise) { "bodyweight_squat" -> 165-70*progress; "glute_bridge" -> 100+75*progress; else -> 165.0 })
            put("angle.${side}_elbow",when (exercise) { "biceps_curl","push_up" -> 170-100*progress; "overhead_press" -> 80+90*progress; else -> 165.0 })
            put("angle.${side}_shoulder",when (exercise) { "overhead_press" -> 40+120*progress; "lateral_raise" -> 10+80*progress; "push_up" -> 90-45*progress; else -> 15.0 })
            put("angle.${side}_body_line",179.0)
            put("angle.${side}_ankle",95.0)
        }
    }
    fun profile(exercise: String = "biceps_curl"): ActionProfile {
        val base = WorkoutPrecisionTestData.profile()
        val phases = listOf(0.0,0.4,0.8,1.0,0.8,0.4)
        val states = phases.mapIndexed { index,progress ->
            val values = features(exercise,progress)
            ActionStateProfile("phase_$index",index,index.toDouble()/phases.size,(index+1.0)/phases.size,
                base.states.first().durationMs,base.states.first().features.map { feature ->
                    val value = values[feature.name] ?: feature.reference.median
                    feature.copy(reference = feature.reference.copy(median = value,lower = max(0.0,value-2),upper = min(180.0,value+2)))
                },0.95)
        }
        return base.copy(states = states,transitions = states.indices.map { ActionTransitionProfile(it,(it+1)%states.size,it==states.lastIndex,0.95) })
    }
    fun reference(exercise: String = "biceps_curl",config: QualifiedRepConfig = QualifiedRepConfig()): QualifiedRepReference =
        (QualifiedRepCompiler.compile(profile(exercise),exercise,identity,config) as QualifiedRepCompilation.Ready).reference

    fun frame(time: Long, progress: Double, exercise: String = "biceps_curl", changes: Map<String,Double> = emptyMap()): SpatialObservationFrame {
        val base = WorkoutPrecisionTestData.frame(time,profile(exercise),features(exercise,progress)+changes)
        return base.copy(intrinsicDescriptor = base.intrinsicDescriptor.copy(featureConfidences = base.intrinsicDescriptor.values.mapValues { 0.95 }))
    }
    /** Oracle movement only. Geometry is independently generated, not labelled by the engine under test. */
    fun progress(ms: Long, amplitude: Double = 1.0, durationScale: Double = 1.0): Double {
        val t = ms/durationScale
        return when {
            t < 300 -> 0.0
            t < 900 -> (t-300)/600*amplitude
            t < 1100 -> amplitude
            t < 1700 -> (1700-t)/600*amplitude
            else -> 0.0
        }
    }
    fun stream(fps: Int = 30, amplitude: Double = 1.0, scale: Double = 1.0, offset: Long = 0): List<Pair<Long,Double>> =
        generateSequence(0) { it+1 }.map { (it*1000.0/fps).toLong() }.takeWhile { it <= (2000*scale).toLong() }
            .map { offset+it to progress(it,amplitude,scale) }.toList()
}
