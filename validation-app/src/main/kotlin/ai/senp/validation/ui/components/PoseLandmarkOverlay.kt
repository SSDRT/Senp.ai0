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

/**
 * Draws the 33-landmark BlazePose skeleton over the video frame.
 *
 * [videoAspectRatio] is width/height of the original video.
 * The overlay computes the "fit" rectangle that the PlayerView (RESIZE_MODE_FIT)
 * uses inside the container, then maps the normalized [0,1] landmark coordinates
 * into that rectangle so the skeleton aligns perfectly with the rendered video.
 */
@Composable
fun PoseLandmarkOverlay(
    poseFrame: PoseFrame?,
    videoAspectRatio: Float,
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

        val canvasW = size.width
        val canvasH = size.height

        // Compute the rectangle where the video is actually rendered (FIT mode).
        // This mirrors what AspectRatioFrameLayout.RESIZE_MODE_FIT does.
        val canvasAspect = canvasW / canvasH
        val videoW: Float
        val videoH: Float
        if (videoAspectRatio > canvasAspect) {
            // Video is wider than container → pillarbox (full width, reduced height)
            videoW = canvasW
            videoH = canvasW / videoAspectRatio
        } else {
            // Video is taller than container → letterbox (full height, reduced width)
            videoH = canvasH
            videoW = canvasH * videoAspectRatio
        }
        val offsetX = (canvasW - videoW) / 2f
        val offsetY = (canvasH - videoH) / 2f

        val landmarks = poseFrame.landmarks

        fun landmarkOffset(lmIndex: Int): Offset {
            val lm = landmarks[lmIndex]
            return Offset(
                x = offsetX + (lm.image.x.toFloat() * videoW),
                y = offsetY + (lm.image.y.toFloat() * videoH)
            )
        }

        // Draw Skeleton Lines
        for ((startIndex, endIndex) in SKELETON_CONNECTIONS) {
            if (startIndex < landmarks.size && endIndex < landmarks.size) {
                val startLm = landmarks[startIndex]
                val endLm = landmarks[endIndex]

                val startPresence = startLm.presence ?: 1.0
                val endPresence = endLm.presence ?: 1.0

                if (startPresence > 0.3 && endPresence > 0.3) {
                    drawLine(
                        color = activeLineColor,
                        start = landmarkOffset(startIndex),
                        end = landmarkOffset(endIndex),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // Draw Landmark Points
        for ((idx, lm) in landmarks.withIndex()) {
            val presence = lm.presence ?: 1.0
            if (presence > 0.3) {
                drawCircle(
                    color = activeJointColor,
                    radius = 6f,
                    center = landmarkOffset(idx)
                )
            }
        }
    }
}
