package ai.senp.motion

import ai.senp.core.contracts.*
import kotlin.test.*

class QualifiedMp33EvidenceTest {
    private fun observation(outsideAll: Boolean=false, wristX: Double=.3): CanonicalObservation {
        val points=mapOf("left_shoulder" to listOf(-.3,1.0,0.0),"right_shoulder" to listOf(.3,1.0,0.0),"left_hip" to listOf(-.2,0.0,0.0),"right_hip" to listOf(.2,0.0,0.0),"left_elbow" to listOf(-.5,.6,0.0),"left_wrist" to listOf(-.4,.2,0.0))
        fun channel(world: Boolean)=ObservationChannel(if(world) "human-pose-world" else "human-pose-image",1,if(world) "human_pose_3d" else "human_pose_2d",if(world) "world_metric_3d" else "image_normalized","primary-subject",listOf("x","y","z"),
            points.map { (name,p) -> ObservationValue(name,if(world) p else listOf(if(outsideAll) p[0]*.2+2.0 else if(name=="left_wrist") wristX else p[0]*.2+.5,p[1]*.2+.4,0.0),listOf(true,true,true),.95) },ChannelAvailability.OBSERVED,.95)
        return CanonicalObservation(TimestampMs(100),listOf(channel(false),channel(true)))
    }
    private fun frame(o: CanonicalObservation)=SpatialSynchronizationEngine().analyzeSequence(CanonicalObservationSequence(VideoRole.SOURCE,DurationMs(101),ObservationSampling(analysisFramesPerSecond=15.0),listOf(o))).frames.single()
    @Test fun productionViewMatchesFrozenResearchMaskAcrossBoundariesAndKeepsRawEvidence() {
        for(x in listOf(-.1,-.0001,0.0,.3,1.0,1.0001,1.2)) {
            val raw=observation(wristX=x);val before=raw.copy();val masked=QualifiedMp33Evidence.inImage(raw)
            assertEquals(InImageMp33Evidence.mask(raw),masked);assertEquals(before,raw)
            assertEquals(raw.channels.first(),masked.channels.first());assertEquals(.95,masked.channels.last().values.last().confidence)
            assertEquals(x in 0.0..1.0,"angle.left_elbow" in frame(masked).intrinsicDescriptor.values)
        }
    }
    @Test fun unobservableWorldAnchorsMayFallBackToImageButCannotBecomeTypedThreeDEvidence() {
        val raw=observation(outsideAll=true);assertEquals(SpatialEvidenceKind.THREE_D,frame(raw).evidenceKind)
        val masked=QualifiedMp33Evidence.inImage(raw);assertEquals(ChannelAvailability.MISSING,masked.channels.last().availability)
        assertNotEquals(SpatialEvidenceKind.THREE_D,frame(masked).evidenceKind)
        assertTrue("angle.left_elbow" in frame(masked).intrinsicDescriptor.values,"An image angle must not be mistaken for observed world evidence")
    }
    @Test fun wrongSubjectMissingOrAmbiguousImageCannotCertifyWorldPoints() {
        val raw=observation();val image=raw.channels[0];val world=raw.channels[1]
        assertFailsWith<IllegalArgumentException> { raw.copy(channels=listOf(image,image.copy(schemaVersion=2),world)) }
        for(channels in listOf(listOf(world),listOf(image.copy(subjectId="another-person"),world),listOf(image.copy(componentAxes=listOf("z","y","x")),world))) {
            val masked=QualifiedMp33Evidence.inImage(raw.copy(channels=channels));val w=masked.channels.last()
            assertEquals(ChannelAvailability.MISSING,w.availability);assertEquals(0.0,w.confidence);assertTrue(w.values.all { p -> p.values.all { it==null } })
            assertNotEquals(SpatialEvidenceKind.THREE_D,frame(masked).evidenceKind)
        }
    }
}
