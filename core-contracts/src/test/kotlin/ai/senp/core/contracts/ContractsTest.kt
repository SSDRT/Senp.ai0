package ai.senp.core.contracts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class ContractsTest {
 private val json=Json{encodeDefaults=true;explicitNulls=true}
 @Test fun `canonical 33 landmark ordering and optional world data`() {
  val landmarks=PoseLandmarkId.entries.map{PoseLandmark(it,ImageLandmark(0.5,0.5,0.0),if(it.index%2==0)WorldLandmark(0.0,0.0,0.0)else null,0.9,0.8)}
  val frame=PoseFrame(TimestampMs(0),0,landmarks,FrameValidity.Valid);assertNull(frame.landmarks[1].world)
  assertFailsWith<IllegalArgumentException>{PoseFrame(TimestampMs(0),0,landmarks.reversed(),FrameValidity.Valid)}
 }
 @Test fun `all validity states serialize stably`() {
  val values=listOf(FrameValidity.Valid,FrameValidity(FrameValidityStatus.REPAIRED,.3,setOf(FrameValidityReason.SHORT_GAP_INTERPOLATION)),FrameValidity(FrameValidityStatus.DEGRADED,.4,setOf(FrameValidityReason.BELOW_TRACKING_THRESHOLD)),FrameValidity(FrameValidityStatus.BLIND,0.0,setOf(FrameValidityReason.LONG_GAP)),FrameValidity(FrameValidityStatus.CONTINUITY_BREAK,.2,setOf(FrameValidityReason.TRACKING_RESET)))
  val first=json.encodeToString(values);assertEquals(first,json.encodeToString(values));assertContains(first,"CONTINUITY_BREAK")
 }
 @Test fun `bounded diagnostics reject producer overflow`() {
  assertFailsWith<IllegalArgumentException>{VideoPoseDiagnostics(10,5,5,0,0,1,1,1,2)}
  VideoPoseDiagnostics(10,5,4,1,0,1,1,2,2)
 }
 @Test fun `pose sequence timestamps are strict`() {
  val f=frame(0);assertFailsWith<IllegalArgumentException>{PoseSequence(VideoRole.SOURCE,listOf(f,f.copy(diagnosticFrameIndex=1)))}
 }
 @Test fun `cache identity remains stable`() { val key=CacheKey.from(request());assertEquals(key.stableId(),CacheKey.from(request()).stableId());assertNotEquals(key.stableId(),key.copy(pipelineVersion="v2").stableId()) }
 private fun frame(t:Long)=PoseFrame(TimestampMs(t),0,PoseLandmarkId.entries.map{PoseLandmark(it,ImageLandmark(.5,.5,0.0),null,.9,.8)},FrameValidity.Valid)
 private fun request()=AnalysisRequest("test",TimestampMs(1),VideoSource("fake://s",Sha256("a".repeat(64))),VideoSource("fake://r",Sha256("b".repeat(64))),AnalysisConfiguration(PoseModelConfiguration(Sha256("c".repeat(64))),"v1",SamplingConfiguration(),"n1","p1"))
}
