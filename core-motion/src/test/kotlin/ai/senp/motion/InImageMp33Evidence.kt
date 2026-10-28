package ai.senp.motion

import ai.senp.core.contracts.*

/** Research comparison view only. Original raw observations remain immutable. */
internal object InImageMp33Evidence {
    const val PIPELINE="mp33-raw-world-in-image-evidence-research/1"
    fun mask(observation: CanonicalObservation): CanonicalObservation {
        val image=observation.channels.singleOrNull { it.channelId=="human-pose-image" && it.coordinateSpace=="image_normalized" }
        return observation.copy(channels=observation.channels.map { world ->
            if(world.channelId!="human-pose-world" || world.coordinateSpace!="world_metric_3d") return@map world
            val paired=image?.takeIf { !it.subjectId.isNullOrBlank() && it.subjectId==world.subjectId && it.componentAxes==listOf("x","y","z") }
            val seen=paired?.values.orEmpty().associate { point ->
                point.key to (point.mask[0] && point.mask[1] && point.values[0]?.let { it in 0.0..1.0 }==true && point.values[1]?.let { it in 0.0..1.0 }==true)
            }
            val values=world.values.map { point ->
                if(seen[point.key]==true) point else point.copy(values=List(point.values.size) { null },mask=List(point.mask.size) { false })
            }
            val count=values.sumOf { v -> v.mask.count { it } };val total=values.sumOf { it.mask.size }
            world.copy(values=values,availability=when(count) { 0 -> ChannelAvailability.MISSING;total -> ChannelAvailability.OBSERVED;else -> ChannelAvailability.PARTIAL },confidence=if(count==0) 0.0 else world.confidence)
        })
    }
}
