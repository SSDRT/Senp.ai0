package ai.senp.motion
import ai.senp.core.contracts.*
import kotlin.test.*

class InImageMp33EvidenceTest {
    private fun observation(x: Double=.5,y: Double=.5): CanonicalObservation {
        val positions=mapOf("left_shoulder" to listOf(-.3,1.0,0.0),"right_shoulder" to listOf(.3,1.0,0.0),"left_hip" to listOf(-.2,0.0,0.0),"right_hip" to listOf(.2,0.0,0.0),"left_elbow" to listOf(-.5,.6,0.0),"left_wrist" to listOf(-.4,.2,0.0))
        fun channel(world: Boolean)=ObservationChannel(if(world) "human-pose-world" else "human-pose-image",1,if(world) "human_pose_3d" else "human_pose_2d",if(world) "world_metric_3d" else "image_normalized","primary-subject",listOf("x","y","z"),
            positions.map { (name,p) -> ObservationValue(name,if(world) p else if(name=="left_wrist") listOf(x,y,0.0) else listOf(.5,.5,0.0),listOf(true,true,true),.95) },ChannelAvailability.OBSERVED,.95)
        return CanonicalObservation(TimestampMs(100),listOf(channel(false),channel(true)))
    }
    private fun descriptor(o: CanonicalObservation)=SpatialSynchronizationEngine().analyzeSequence(CanonicalObservationSequence(VideoRole.SOURCE,DurationMs(101),ObservationSampling(analysisFramesPerSecond=15.0),listOf(o))).frames.single().intrinsicDescriptor
    @Test fun currentWorldGeometryCanExposeAnAngleEvenWhenItsHighConfidenceWristIsOutsideImage() {
        val raw=observation(-.1);assertTrue("angle.left_elbow" in descriptor(raw).values)
        val masked=InImageMp33Evidence.mask(raw);assertFalse("angle.left_elbow" in descriptor(masked).values)
        assertTrue("angle.left_shoulder" in descriptor(masked).values)
        assertEquals(raw.timestamp,masked.timestamp);assertEquals(.95,raw.channels[1].values.last().confidence)
    }
    @Test fun everyViewportEdgeIsCheckedAndExactBoundaryCoordinatesRemainAdmitted() {
        for((x,y) in listOf(-.0001 to .5,1.0001 to .5,.5 to -.0001,.5 to 1.0001)) assertTrue(InImageMp33Evidence.mask(observation(x,y)).channels[1].values.last().mask.none { it })
        for((x,y) in listOf(0.0 to .5,1.0 to .5,.5 to 0.0,.5 to 1.0)) assertEquals(observation(x,y),InImageMp33Evidence.mask(observation(x,y)))
    }
    @Test fun originalRawCoordinatesAndConfidenceAreRetainedOutsideTheMaskedComparisonView() {
        val raw=observation(-.1);val before=raw.copy();val masked=InImageMp33Evidence.mask(raw)
        assertEquals(before,raw);assertEquals(raw.channels[0],masked.channels[0]);assertEquals(.95,masked.channels[1].values.last().confidence)
        assertEquals(listOf(null,null,null),masked.channels[1].values.last().values);assertEquals(ChannelAvailability.PARTIAL,masked.channels[1].availability)
    }
    @Test fun missingOrOtherSubjectImageEvidenceCannotAdmitWorldJoints() {
        val raw=observation()
        val missing=raw.copy(channels=listOf(raw.channels[1]))
        val wrong=raw.copy(channels=listOf(raw.channels[0].copy(subjectId="other-subject"),raw.channels[1]))
        val wrongAxes=raw.copy(channels=listOf(raw.channels[0].copy(componentAxes=listOf("z","x","y")),raw.channels[1]))
        for(o in listOf(missing,wrong,wrongAxes)) {
            val world=InImageMp33Evidence.mask(o).channels.single { it.channelId=="human-pose-world" }
            assertEquals(ChannelAvailability.MISSING,world.availability);assertEquals(0.0,world.confidence);assertTrue(world.values.all { p -> p.mask.none { it } })
        }
    }
}
