package ai.senp.motion

import kotlin.test.*

class RotationReferenceMappingTest {
    @Test fun inverseQuarterTurnsUseOriginalNonSquareImageAndMetricAxes() {
        val p = listOf(.2,.7,-.3,.83,.91,2.0,-3.0,4.0)
        val a = RotationReferenceMapping.mapped(p,90,800,400)
        val b = RotationReferenceMapping.mapped(p,270,800,400)
        assertEquals(listOf(.7,.8,-.15,.83,.91,-3.0,-2.0,4.0),a)
        assertEquals(.3,b[0],1e-12);assertEquals(.2,b[1],0.0)
        assertEquals(listOf(-.15,.83,.91,3.0,2.0,4.0),b.drop(2))
        assertEquals(listOf(.2,.7,-.3,.83,.91,2.0,-3.0,4.0),p)
    }
    @Test fun inverseRotationNeverClampsRawOutsideImagePointsOrChangesConfidence() {
        val p = listOf(-.2,1.4,-3.0,.3,.4,7.0,8.0,9.0)
        val mapped = RotationReferenceMapping.mapped(p,90,640,480)
        assertEquals(1.4,mapped[0],0.0);assertEquals(1.2,mapped[1],0.0)
        assertEquals(p.subList(3,5),mapped.subList(3,5));assertEquals(9.0,mapped[7],0.0)
    }
    @Test fun malformedDimensionsPointsAndUnsupportedRollFailClosed() {
        val p = List(8) { .5 }
        for (turn in listOf(0,45,180)) assertFailsWith<IllegalArgumentException> { RotationReferenceMapping.mapped(p,turn,640,480) }
        assertFailsWith<IllegalArgumentException> { RotationReferenceMapping.mapped(p,90,0,480) }
        assertFailsWith<IllegalArgumentException> { RotationReferenceMapping.mapped(p.drop(1),90,640,480) }
        assertFailsWith<IllegalArgumentException> { RotationReferenceMapping.mapped(p.mapIndexed { i,v -> if (i==2) Double.NaN else v },90,640,480) }
    }
    @Test fun subjectBindingRequiresCurrentConfidentTorsoAndOriginalImageAgreement() {
        val p = MutableList(33) { listOf(.5,.5,0.0,.9,.9,0.0,0.0,0.0) }
        for (i in listOf(11,12)) p[i] = p[i].mapIndexed { j,v -> if (j==1) .3 else v }
        for (i in listOf(23,24)) p[i] = p[i].mapIndexed { j,v -> if (j==1) .7 else v }
        assertTrue(RotationReferenceMapping.sameSubject(p,p))
        assertFalse(RotationReferenceMapping.sameSubject(p,p.map { point -> point.mapIndexed { j,v -> if (j==0) v+.3 else v } }))
        assertFalse(RotationReferenceMapping.sameSubject(p,p.map { point -> point.mapIndexed { j,v -> if (j==3) .64 else v } }))
        assertFalse(RotationReferenceMapping.sameSubject(emptyList(),p))
    }
}
