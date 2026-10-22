package ai.senp.pose.mediapipe

/** Official, pinned, offline models. Higher capacity still requires device/labelled validation. */
enum class LivePoseModel(val assetPath: String, val sha256: String) {
    FULL("pose_landmarker_full.task", "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"),
    HEAVY("pose_landmarker_heavy.task", "64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b"),
}
