package ai.senp.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class UiprmdPelvisKneeProjectionTest {
    @Test fun newKneeSlotHasNoOriginalAngleFallbackAndRetainsDiscardedComponent() {
        val original=mapOf("angle.left_knee" to 90.0,"angle.right_knee" to 91.0,"angle.left_hip" to 100.0,
            "angle.left_knee_pelvis_projection" to 110.0,"angle.left_thigh_out_of_pelvis_plane" to 30.0)
        val projected=pelvisKneeResearchProjection(original,"m01",true)
        assertEquals(110.0,projected["angle.left_knee"]);assertFalse("angle.right_knee" in projected)
        assertFalse("angle.left_knee_pelvis_projection" in projected);assertEquals(30.0,projected["angle.left_thigh_out_of_pelvis_plane"])
        assertEquals(100.0,projected["angle.left_hip"]);assertEquals(90.0,original["angle.left_knee"])
    }
    @Test fun controlRetainsOriginalKneeAndBothModesRetainPriorArmProjection() {
        val values=mapOf("angle.left_knee" to 130.0,"angle.right_shoulder" to 90.0,"angle.right_arm_frontal_projection" to 70.0)
        assertSame(values,pelvisKneeResearchProjection(values,"m01",false))
        for(candidate in listOf(false,true)) {
            val result=pelvisKneeResearchProjection(values,"m07",candidate)
            assertEquals(70.0,result["angle.right_shoulder"]);assertEquals(130.0,result["angle.left_knee"])
        }
    }
}
