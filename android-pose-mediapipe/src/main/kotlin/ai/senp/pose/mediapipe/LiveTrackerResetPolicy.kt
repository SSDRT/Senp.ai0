package ai.senp.pose.mediapipe

internal enum class LiveTrackerReset { NONE, CONTINUITY_ONLY, RECREATE }

/** A graph that has never produced landmarks has no pose/filter history to retain.
 * Once any native pose was produced, gaps must recreate even if its latest result
 * was empty. Geometry changes always recreate. Logical continuity is never bridged.
 */
internal fun liveTrackerReset(hasPreviousFrame: Boolean, gap: Boolean, geometryChanged: Boolean,
    graphHasProducedPose: Boolean): LiveTrackerReset = when {
    !hasPreviousFrame -> LiveTrackerReset.NONE
    geometryChanged -> LiveTrackerReset.RECREATE
    !gap -> LiveTrackerReset.NONE
    graphHasProducedPose -> LiveTrackerReset.RECREATE
    else -> LiveTrackerReset.CONTINUITY_ONLY
}
