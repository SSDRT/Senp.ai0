package ai.senp.validation.ui.components

import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.PoseFrame
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

private val SKELETON_CONNECTIONS = listOf(
    // Shoulders & Torso
    Pair(11, 12),
    Pair(11, 23),
    Pair(12, 24),
    Pair(23, 24),
    // Left Arm
    Pair(11, 13),
    Pair(13, 15),
    Pair(15, 17),
    Pair(15, 19),
    Pair(15, 21),
    // Right Arm
    Pair(12, 14),
    Pair(14, 16),
    Pair(16, 18),
    Pair(16, 20),
    Pair(16, 22),
    // Left Leg
    Pair(23, 25),
    Pair(25, 27),
    Pair(27, 29),
    Pair(27, 31),
    // Right Leg
    Pair(24, 26),
    Pair(26, 28),
    Pair(28, 30),
    Pair(28, 32),
)

@Composable
fun PoseLandmarkOverlay(
    poseFrame: PoseFrame?,
    modifier: Modifier = Modifier,
    jointColor: Color = Color(0xFF00E5FF),
    lineColor: Color = Color(0xFF76FF03),
    invalidColor: Color = Color(0xFFFF5252),
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (poseFrame == null) return@Canvas

        val isFrameValid = poseFrame.validity.status == FrameValidityStatus.VALID
        val activeLineColor = if (isFrameValid) lineColor else invalidColor
        val activeJointColor = if (isFrameValid) jointColor else invalidColor

        val width = size.width
        val height = size.height

        val landmarks = poseFrame.landmarks

        // Draw Skeleton Lines
        for ((startIndex, endIndex) in SKELETON_CONNECTIONS) {
            if (startIndex < landmarks.size && endIndex < landmarks.size) {
                val startLm = landmarks[startIndex]
                val endLm = landmarks[endIndex]

                val startPresence = startLm.presence ?: 1.0
                val endPresence = endLm.presence ?: 1.0

                if (startPresence > 0.3 && endPresence > 0.3) {
                    val startOffset = Offset(
                        x = (startLm.image.x * width).toFloat(),
                        y = (startLm.image.y * height).toFloat()
                    )
                    val endOffset = Offset(
                        x = (endLm.image.x * width).toFloat(),
                        y = (endLm.image.y * height).toFloat()
                    )

                    drawLine(
                        color = activeLineColor,
                        start = startOffset,
                        end = endOffset,
                        strokeWidth = 4f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // Draw Landmark Points
        for (lm in landmarks) {
            val presence = lm.presence ?: 1.0
            if (presence > 0.3) {
                val center = Offset(
                    x = (lm.image.x * width).toFloat(),
                    y = (lm.image.y * height).toFloat()
                )
                drawCircle(
                    color = activeJointColor,
                    radius = 6f,
                    center = center
                )
            }
        }
    }
}
