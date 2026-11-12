package ai.senp.pose.mediapipe

import org.junit.Test
import org.junit.Assert.assertEquals

class LiveTrackerResetPolicyTest {
    @Test fun anUnprimedGraphStillSignalsEveryGapWithoutUnnecessaryNativeRecreation() {
        assertEquals(LiveTrackerReset.CONTINUITY_ONLY,liveTrackerReset(true,true,false,false))
        assertEquals(LiveTrackerReset.NONE,liveTrackerReset(true,false,false,false))
        assertEquals(LiveTrackerReset.NONE,liveTrackerReset(false,false,true,false))
    }
    @Test fun anyEarlierPoseOrGeometryChangeKeepsTheFullNativeReset() {
        assertEquals(LiveTrackerReset.RECREATE,liveTrackerReset(true,true,false,true))
        assertEquals(LiveTrackerReset.RECREATE,liveTrackerReset(true,true,true,false))
        assertEquals(LiveTrackerReset.RECREATE,liveTrackerReset(true,false,true,false))
        assertEquals(LiveTrackerReset.NONE,liveTrackerReset(true,false,false,true))
    }
}
