package ai.senp.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class UiprmdCentralTorsoProjectionTest {
    @Test fun candidateProjectionIsExplicitAndCannotFallbackToOriginalProxy() {
        val original=mapOf("angle.right_shoulder" to 90.0,"angle.left_shoulder" to 91.0,"angle.right_arm_central_torso" to 70.0,"angle.right_elbow" to 170.0)
        val projected=centralTorsoResearchProjection(original,"m07",true)
        assertEquals(70.0,projected["angle.right_shoulder"]);assertFalse("angle.left_shoulder" in projected)
        assertFalse("angle.right_arm_central_torso" in projected);assertEquals(170.0,projected["angle.right_elbow"])
        assertEquals(90.0,original["angle.right_shoulder"])
    }
    @Test fun squatAndBaselineKeepOriginalMeasurements() {
        val values=mapOf("angle.left_knee" to 130.0,"angle.right_shoulder" to 90.0)
        assertSame(values,centralTorsoResearchProjection(values,"m01",true))
        assertSame(values,centralTorsoResearchProjection(values,"m07",false))
    }
}
