package ai.senp.motion

object ExerciseProfiles {
    private val shoulders = setOf(LandmarkId.LEFT_SHOULDER, LandmarkId.RIGHT_SHOULDER)
    private val elbows = setOf(LandmarkId.LEFT_ELBOW, LandmarkId.RIGHT_ELBOW)
    private val wrists = setOf(LandmarkId.LEFT_WRIST, LandmarkId.RIGHT_WRIST)
    private val hips = setOf(LandmarkId.LEFT_HIP, LandmarkId.RIGHT_HIP)
    private val knees = setOf(LandmarkId.LEFT_KNEE, LandmarkId.RIGHT_KNEE)
    private val ankles = setOf(LandmarkId.LEFT_ANKLE, LandmarkId.RIGHT_ANKLE)
    private val feet = setOf(LandmarkId.LEFT_FOOT_INDEX, LandmarkId.RIGHT_FOOT_INDEX)

    val generic = ExerciseProfile(
        id = "generic",
        required = shoulders + hips + knees + ankles,
        preferred = elbows + wrists + feet,
        sidePolicy = SidePolicy.BOTH,
        minimumRequiredCoverage = 0.75,
    )

    val bicepsCurl = ExerciseProfile(
        id = "biceps_curl",
        required = shoulders + elbows + wrists,
        preferred = hips,
        sidePolicy = SidePolicy.BEST_VISIBLE,
        minimumRequiredCoverage = 0.80,
        weights = QualityWeights(
            visibility = 0.28,
            presence = 0.22,
            requiredCoverage = 0.38,
            preferredQuality = 0.12,
        ),
    )

    val pushup = ExerciseProfile(
        id = "pushup",
        required = shoulders + elbows + wrists + hips + ankles,
        preferred = knees,
        sidePolicy = SidePolicy.BEST_VISIBLE,
        minimumRequiredCoverage = 0.78,
        weights = QualityWeights(
            visibility = 0.22,
            presence = 0.18,
            requiredCoverage = 0.40,
            preferredQuality = 0.20,
            instabilityPenalty = 0.38,
        ),
    )

    val squat = ExerciseProfile(
        id = "squat",
        required = hips + knees + ankles,
        preferred = shoulders + feet,
        sidePolicy = SidePolicy.BOTH,
        minimumRequiredCoverage = 0.82,
        weights = QualityWeights(
            visibility = 0.22,
            presence = 0.18,
            requiredCoverage = 0.45,
            preferredQuality = 0.15,
        ),
    )

    val legRaise = ExerciseProfile(
        id = "leg_raise",
        required = hips + knees + ankles,
        preferred = shoulders + feet,
        sidePolicy = SidePolicy.BEST_VISIBLE,
        minimumRequiredCoverage = 0.78,
    )

    val plank = ExerciseProfile(
        id = "plank",
        required = shoulders + hips + ankles,
        preferred = elbows + wrists + knees,
        sidePolicy = SidePolicy.BEST_VISIBLE,
        minimumRequiredCoverage = 0.80,
        weights = QualityWeights(instabilityPenalty = 0.42),
    )

    val pullup = ExerciseProfile(
        id = "pullup",
        required = shoulders + elbows + wrists + hips,
        preferred = setOf(LandmarkId.NOSE),
        sidePolicy = SidePolicy.BEST_VISIBLE,
        minimumRequiredCoverage = 0.78,
    )
}
