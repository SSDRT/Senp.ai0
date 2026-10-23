package ai.senp.motion

import ai.senp.core.contracts.*
import kotlin.test.*

class QualifiedRepCalibrationRecoveryTest {
    private val data = QualifiedRepTestData
    private fun sequence(frames: List<SpatialObservationFrame>) = SpatialSequenceAnalysis(VideoRole.REFERENCE,
        DurationMs(frames.last().timestamp.value+1),ObservationSampling(analysisFramesPerSecond = 30.0),frames,1.0)

    @Test fun envelopeEdgesNeedSupportOnTwoDifferentCyclesAndDoNotDependOnDriverDirection() {
        val endpoints = (0..4).map { 68.0+it to 168.0+it }
        val forward = requireNotNull(observedRepDriverEnvelope(endpoints,70.0,170.0,QualifiedRepConfig()))
        assertEquals(69.0,forward.lower);assertEquals(171.0,forward.upper)
        assertEquals(forward,observedRepDriverEnvelope(endpoints,170.0,70.0,QualifiedRepConfig()))
        assertEquals(5,forward.observedCycles)
    }
    @Test fun aSingleExtremeCycleCannotWidenEitherRangeEdge() {
        assertNull(observedRepDriverEnvelope(List(4) { 70.0 to 170.0 }+listOf(30.0 to 179.0),170.0,70.0,QualifiedRepConfig()))
    }
    @Test fun noisyShortAndMalformedEndpointEvidenceCannotProduceAnEnvelope() {
        for (endpoints in listOf(listOf(69.0 to 171.0,68.0 to 172.0),
            listOf(20.0 to 100.0,40.0 to 120.0,60.0 to 140.0,80.0 to 160.0,100.0 to 180.0),
            List(3) { Double.NaN to 170.0 },List(3) { 171.0 to 69.0 })) {
            assertNull(observedRepDriverEnvelope(endpoints,170.0,70.0,QualifiedRepConfig()))
        }
        assertFailsWith<IllegalArgumentException> { RepDriverEnvelope(-1.0,181.0,3,1.0,1.0) }
    }
    @Test fun alreadyQualifiedReferencesRetainTheirOriginalCurveAndDoNotAcquireAnEnvelope() {
        val source = sequence((0 until 4).flatMap { rep -> data.stream(offset = rep*2100L).map { (t,p) -> data.frame(t,p) } })
        val old = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(source,"biceps_curl",data.identity,
            QualifiedRepConfig(useObservedDriverEnvelope = false))).reference
        val current = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(source,"biceps_curl",data.identity)).reference
        assertNull(current.driverEnvelope);assertEquals(old.profile,current.profile);assertEquals(old.calibration,current.calibration)
    }
    @Test fun repeatedEndpointVariationCanRecoverCalibrationWithoutAcceptingShallowOrClearlyFaultyMotion() {
        val frames = (0..6).flatMap { rep -> data.stream(30,scale = .75,offset = rep*1600L).map { (t,p) ->
            val local = t-rep*1600L
            val driver = if (local == 700L || local == 766L) 57.5 else 170-100*p+(rep-3)
            data.frame(t,p,changes = mapOf("angle.left_elbow" to driver))
        } }
        val source = sequence(frames)
        val old = assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(source,"biceps_curl",data.identity,
            QualifiedRepConfig(useObservedDriverEnvelope = false)))
        assertEquals(RepReferenceFailure.LOW_SELF_RECONSTRUCTION,old.reason)
        val reference = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(source,"biceps_curl",data.identity)).reference
        assertNotNull(reference.driverEnvelope)
        val calibration = requireNotNull(reference.calibration)
        assertTrue(calibration.baselineAcceptedSelfCycles < calibration.acceptedSelfCycles)
        assertTrue(calibration.selfAcceptanceFraction >= .8)
        for (kind in listOf("clean","shallow","fault")) {
            val engine = reference.newEngine()
            val outputs = data.stream(amplitude = if (kind == "shallow") .45 else 1.0).map { (t,p) ->
                val changes = if (kind == "fault" && t in 700..1100) mapOf("angle.left_elbow" to 0.0) else emptyMap()
                engine.evaluate(data.frame(t,p,changes = changes),data.identity.poseModelSha256)
            }
            assertEquals(if (kind == "clean") 1 else 0,outputs.last().counts.qualified,kind)
            if (kind != "clean") assertEquals(1,outputs.last().counts.rejected,kind)
        }
    }
    @Test fun anEnvelopeCannotSupplyMissingCyclesOrCriticalFeatures() {
        for (missing in listOf(false,true)) {
            val source = sequence((0 until if (missing) 4 else 2).flatMap { rep -> data.stream(offset = rep*2100L).map { (t,p) ->
                data.frame(t,p).let { f -> if (!missing) f else f.copy(intrinsicDescriptor = f.intrinsicDescriptor.copy(
                    featureConfidences = f.intrinsicDescriptor.featureConfidences+("angle.left_shoulder" to .1))) }
            } })
            assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(source,"biceps_curl",data.identity))
        }
    }
    @Test fun envelopeValuesInvalidateReferenceIdentity() {
        val base = data.reference()
        fun withEnvelope(lower: Double) = QualifiedRepReference(base.profile,base.fingerprint,base.config,base.spec,base.driverFeature,
            base.requiredFeatures,base.readyAngle,base.farAngle,base.nodes,base.sourceTargets,base.sourceTolerances,
            driverEnvelope = RepDriverEnvelope(lower,172.0,5,1.0,1.0))
        assertNotEquals(base.referenceKey,withEnvelope(68.0).referenceKey)
        assertNotEquals(withEnvelope(68.0).referenceKey,withEnvelope(69.0).referenceKey)
    }
    @Test fun experimentalPairwiseFitResistsAnIsolatedValueOutlierAndDoesNotInferASlopeFromStationaryProgress() {
        val samples = (0..10).map { it/20.0 to (20+it*2.0) }+listOf(.45 to 170.0)
        assertEquals(40.0,medianPairwiseRepSlope(samples),1e-8)
        assertEquals(0.0,medianPairwiseRepSlope(List(10) { .5 to it.toDouble() }))
    }
    @Test fun boundedRuntimeFitHandlesALongContaminatedReferenceWithoutQuadraticPairGrowth() {
        val samples = (0 until 100_000).map { index ->
            val p = index/100_000.0
            p to (20+40*p+if (index%17==0) 80.0 else 0.0)
        }
        assertEquals(40.0,boundedPairwiseRepSlope(samples),1e-8)
        assertEquals(0.0,boundedPairwiseRepSlope(emptyList()))
        assertEquals(0.0,boundedPairwiseRepSlope(List(100_000) { .5 to 20.0 }))
    }
}
