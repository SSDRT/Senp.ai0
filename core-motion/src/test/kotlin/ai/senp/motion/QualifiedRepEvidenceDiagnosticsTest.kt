package ai.senp.motion

import kotlin.test.*

class QualifiedRepEvidenceDiagnosticsTest {
    private val data = QualifiedRepTestData

    @Test fun passiveEvidenceHasIdenticalOutputsForAllPoliciesSidesMirrorsAndCadences() {
        for (id in QualifiedRepCompiler.supportedExerciseIds) for (side in RepObservedSide.entries)
            for (mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for (fps in listOf(10,15,30,60))
                for (amplitude in listOf(1.0,.45)) {
                    val reference = data.reference(id,QualifiedRepConfig(side = side,mirrorMode = mirror))
                    val ordinary = reference.newEngine();val diagnostics = mutableListOf<QualifiedRepFrameEvidence>()
                    val measured = QualifiedRepEngine(reference) { diagnostics += it }
                    for ((t,p) in data.stream(fps,amplitude)) {
                        val frame = data.frame(t,p,id)
                        assertEquals(ordinary.evaluate(frame,data.identity.poseModelSha256),measured.evaluate(frame,data.identity.poseModelSha256))
                    }
                    assertEquals(ordinary.finish(2100),measured.finish(2100))
                    assertTrue(diagnostics.isNotEmpty())
                    assertTrue(diagnostics.all { it.referenceKey == reference.referenceKey && it.features.size == reference.requiredFeatures.size })
                }
    }

    @Test fun lowConfidenceValueIsRetainedAsRawButNeverReclassifiedAsObserved() {
        val reference = data.reference(); val evidence = mutableListOf<QualifiedRepFrameEvidence>()
        val measured = QualifiedRepEngine(reference) { evidence += it };val ordinary = reference.newEngine()
        for ((t,p) in data.stream()) {
            val raw = data.frame(t,p)
            val frame = if (t in 600..900) raw.copy(intrinsicDescriptor = raw.intrinsicDescriptor.copy(
                values = raw.intrinsicDescriptor.values + ("angle.left_shoulder" to 75.0),
                featureConfidences = raw.intrinsicDescriptor.featureConfidences + ("angle.left_shoulder" to .2))) else raw
            assertEquals(ordinary.evaluate(frame,data.identity.poseModelSha256),measured.evaluate(frame,data.identity.poseModelSha256))
        }
        val rows = evidence.filter { it.timestampMs in 600..900 }.map { it.features.single { f -> f.referenceFeature == "angle.left_shoulder" } }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.rawDegrees == 75.0 && it.confidence == .2 && it.observedDegrees == null && it.deviationDegrees == null })
    }

    @Test fun tracedBandMatchesTheActualMappedFeatureAndPersistentFault() {
        val reference = data.reference(config = QualifiedRepConfig(mirrorMode = ActionMirrorMode.MIRRORED))
        val evidence = mutableListOf<QualifiedRepFrameEvidence>();val faults = mutableListOf<RepFault>()
        val measured = QualifiedRepEngine(reference) { evidence += it }
        for ((t,p) in data.stream()) {
            val frame = data.frame(t,p,changes = if (t in 600..1000) mapOf("angle.right_shoulder" to 80.0) else emptyMap())
            faults += measured.evaluate(frame,data.identity.poseModelSha256).newFaults
        }
        val fault = faults.single()
        val row = evidence.single { it.timestampMs == fault.confirmedMs }
        val feature = row.features.single { it.observedFeature == fault.feature }
        assertEquals("angle.left_shoulder",feature.referenceFeature)
        assertEquals("angle.right_shoulder",feature.observedFeature)
        assertEquals(fault.deviationDegrees,feature.deviationDegrees)
        assertTrue(requireNotNull(feature.deviationDegrees) > feature.rejectAllowanceDegrees)
        val band = reference.evidenceBand(feature.referenceFeature,row.driverProgress,row.comparisonUsesReturnBranch)
        assertEquals(band.lower,feature.lowerDegrees);assertEquals(band.upper,feature.upperDegrees)
        assertEquals(band.acceptable,feature.acceptableAllowanceDegrees)
    }

    @Test fun diagnosticsCannotSupplyMissingDriversOrBypassProvenanceAndHavePrefixCausality() {
        fun replay(limit: Long): Pair<List<QualifiedRepOutput>,List<QualifiedRepFrameEvidence>> {
            val evidence = mutableListOf<QualifiedRepFrameEvidence>()
            val measured = QualifiedRepEngine(data.reference()) { evidence += it }
            val outputs = data.stream().filter { it.first <= limit }.map { (t,p) ->
                val frame = data.frame(t,p)
                measured.evaluate(frame,if (t in 800..900) "wrong" else data.identity.poseModelSha256)
            }
            return outputs to evidence
        }
        val (full,evidence) = replay(2100)
        val (prefix,prefixEvidence) = replay(1000)
        assertEquals(full.take(prefix.size),prefix)
        assertEquals(evidence.filter { it.timestampMs <= 1000 },prefixEvidence)
        assertTrue(evidence.none { it.timestampMs in 800..900 })
        assertEquals(0,full.last().counts.qualified)
    }
}
