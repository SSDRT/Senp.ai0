package ai.senp.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkoutPrecisionCoachTest {
    private val data = WorkoutPrecisionTestData
    private val elbow = "angle.left_elbow"
    private fun feed(coach: WorkoutPrecisionCoach, ms: Long, value: Double = 110.0) =
        coach.evaluate(data.frame(ms, changes = mapOf(elbow to value)), data.estimate(ms))

    @Test fun exactThresholdAndBriefSpikesNeverTriggerButPersistentExceedanceDoes() {
        val coach = WorkoutPrecisionCoach(data.profile())
        for (ms in 0L..400L step 80) assertTrue(feed(coach, ms, 102.0).triggers.isEmpty())
        assertTrue(feed(coach, 480).triggers.isEmpty())
        assertTrue(feed(coach, 560, 90.0).triggers.isEmpty())
        for (ms in listOf(640L, 720L, 800L)) assertTrue(feed(coach, ms).triggers.isEmpty())
        val result = feed(coach, 880)
        assertEquals(1, result.triggers.size)
        val measurement = result.triggers.single().measurement
        assertEquals(18.0, measurement.signedOutsideRange)
        assertEquals(10.0, measurement.enterThreshold)
        assertEquals(240L, measurement.persistenceMs)
    }

    @Test fun releaseHysteresisIsSymmetricAndRepeatCooldownDoesNotSpam() {
        for (outside in listOf(110.0, 70.0)) {
            val coach = WorkoutPrecisionCoach(data.profile())
            (0L..240L step 80).forEach { feed(coach, it, outside) }
            assertTrue(feed(coach, 320, outside).triggers.isEmpty())
            assertEquals(1, feed(coach, 400, 90.0).activeSuggestions.size)
            assertEquals(1, feed(coach, 480, 90.0).activeSuggestions.size)
            assertTrue(feed(coach, 560, 90.0).activeSuggestions.isEmpty())
            (640L..880L step 80).forEach { assertTrue(feed(coach, it, outside).triggers.isEmpty()) }
        }
    }

    @Test fun weakOrMissingIndividualJointsCannotBorrowOverallConfidence() {
        val coach = WorkoutPrecisionCoach(data.profile())
        for (ms in 0L..400L step 80) {
            val frame = data.frame(ms, changes = mapOf(elbow to 110.0))
            val weakened = frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(featureConfidences = frame.intrinsicDescriptor.featureConfidences + (elbow to 0.1)))
            val result = coach.evaluate(weakened, data.estimate(ms))
            assertEquals(WorkoutFeatureStatus.WEAK_OBSERVATION, result.decisions.first { it.feature == elbow }.status)
            assertTrue(result.activeSuggestions.isEmpty())
        }
        coach.reset()
        val frame = data.frame(0).let { it.copy(intrinsicDescriptor = it.intrinsicDescriptor.copy(featureConfidences = emptyMap())) }
        assertTrue(coach.evaluate(frame, data.estimate(0)).activeSuggestions.isEmpty())
    }

    @Test fun gapsTrackingLossSubjectsAndCoordinateChangesDiscardOldEvidence() {
        val coach = WorkoutPrecisionCoach(data.profile())
        feed(coach, 0); feed(coach, 160)
        assertTrue(feed(coach, 1000).triggers.isEmpty())
        val lost = coach.evaluate(data.frame(1080), data.estimate(1080).copy(status = ActionTrackingStatus.LOST))
        assertTrue(lost.activeSuggestions.isEmpty())
        assertEquals(WorkoutAbstention.ACTION_NOT_CONFIRMED, lost.abstention)
        assertTrue(feed(coach, 1160).triggers.isEmpty())
        val subject = data.frame(1240, changes = mapOf(elbow to 110.0)).copy(selectedSubjectId = "another-subject")
        assertTrue(coach.evaluate(subject, data.estimate(1240)).triggers.isEmpty())
        val projected = data.frame(1320).copy(evidenceKind = SpatialEvidenceKind.IMAGE_2D)
        assertEquals(WorkoutAbstention.INCOMPATIBLE_SPACE, coach.evaluate(projected, data.estimate(1320)).abstention)
    }

    @Test fun mirroredRulesRetainReferenceKeysButNameTheObservedAnatomicalSide() {
        val coach = WorkoutPrecisionCoach(data.profile())
        var last: WorkoutPrecisionResult? = null
        for (ms in 0L..240L step 80) last = coach.evaluate(
            data.frame(ms, changes = mapOf("angle.right_elbow" to 110.0)), data.estimate(ms, mirror = ActionMirrorMode.MIRRORED))
        assertEquals(elbow, requireNotNull(last).triggers.single().feature)
        assertEquals("angle.right_elbow", requireNotNull(last).triggers.single().measurement.observedFeature)
        assertTrue(requireNotNull(last).triggers.single().label.contains("right elbow"))
    }

    @Test fun allFeaturesAreAccountedForAndBodyProportionsNeverBecomeCorrectionCues() {
        val profile = data.profile().let { p -> p.copy(states = p.states.map { state -> state.copy(features = state.features + data.feature("equipment.load")) }) }
        val result = WorkoutPrecisionCoach(profile).evaluate(data.frame(0, profile), data.estimate(0))
        assertEquals(profile.states[0].features.size, result.decisions.size)
        assertEquals(WorkoutFeatureStatus.UNSUPPORTED, result.decisions.first { it.feature == "equipment.load" }.status)
        assertTrue(result.decisions.filter { it.feature.startsWith("ratio.") }.all { it.status == WorkoutFeatureStatus.CONTEXT_ONLY })
        assertEquals(20, result.decisions.count { it.status == WorkoutFeatureStatus.MONITORED })
    }

    @Test fun spacingTriggersRequireComparableBodyProportions() {
        val changes = data.distances.associateWith { 1.1 }
        val coach = WorkoutPrecisionCoach(data.profile())
        var last = coach.evaluate(data.frame(0, changes = changes), data.estimate(0))
        for (ms in listOf(80L, 160L, 240L)) last = coach.evaluate(data.frame(ms, changes = changes), data.estimate(ms))
        assertEquals(8, last.triggers.size)
        coach.reset()
        val mismatched = data.proportions.associateWith { 1.8 } + changes + mapOf(elbow to 110.0)
        for (ms in 0L..240L step 80) last = coach.evaluate(data.frame(ms, changes = mismatched), data.estimate(ms))
        assertEquals(elbow, last.triggers.single().feature)
        assertTrue(last.decisions.filter { it.feature.startsWith("distance.") }.all { it.status == WorkoutFeatureStatus.INCOMPATIBLE_PROPORTIONS })
    }

    @Test fun configuredThresholdsAndReferenceNoiseAreAuditable() {
        val coach = WorkoutPrecisionCoach(data.profile(), WorkoutPrecisionConfig(featureOverrides = mapOf(elbow to WorkoutTolerance(20.0, 12.0))))
        for (ms in 0L..400L step 80) assertTrue(feed(coach, ms).triggers.isEmpty())
        assertEquals(20.0, feed(coach, 480).decisions.first { it.feature == elbow }.enterThreshold)
        val noisy = data.profile().let { p -> p.copy(states = p.states.map { state -> state.copy(features = state.features.map { if (it.name == elbow) it.copy(reference = it.reference.copy(mad = 8.0)) else it }) }) }
        val result = WorkoutPrecisionCoach(noisy).evaluate(data.frame(0, noisy), data.estimate(0))
        assertEquals(23.7216, result.decisions.first { it.feature == elbow }.enterThreshold!!, 1e-5)
    }

    @Test fun staticReferencesEnterWithoutInventedRepetitionsAndLiveHistoryIsBounded() {
        val profile = (ReferenceActionCompiler().compile(data.sequence()) as ReferenceActionCompilation.Success).profile
        assertTrue(profile.staticHold)
        assertEquals(1, profile.states.size)
        val recognizer = ActionStateRecognizer(profile, ActionStateRecognizerConfig(maximumRetainedEstimates = 0))
        var estimate = recognizer.accept(data.frame(0))
        assertEquals(ActionTrackingStatus.POSSIBLE_ENTRY, estimate.status)
        for (ms in 80L..800L step 80) estimate = recognizer.accept(data.frame(ms))
        assertEquals(ActionTrackingStatus.TRACKING, estimate.status)
        val result = recognizer.finish()
        assertEquals(0, result.completedRepetitions)
        assertTrue(result.estimates.isEmpty())
        assertTrue(result.trackedFraction > 0.5)
        assertEquals(ActionTrackingStatus.POSSIBLE_ENTRY, recognizer.accept(data.frame(3000)).status)
        val explicitMovement = (ReferenceActionCompiler(ReferenceActionCompilerConfig(referenceMode = WorkoutReferenceMode.MOVEMENT)).compile(data.sequence()) as ReferenceActionCompilation.Success).profile
        assertFalse(explicitMovement.staticHold)
    }

    @Test fun timestampsMismatchedEstimatesAndInvalidPoliciesAreRejected() {
        val coach = WorkoutPrecisionCoach(data.profile())
        feed(coach, 10)
        assertFailsWith<IllegalArgumentException> { feed(coach, 10) }
        assertFailsWith<IllegalArgumentException> { coach.evaluate(data.frame(20), data.estimate(21)) }
        assertFailsWith<IllegalArgumentException> { WorkoutTolerance(1.0, 2.0) }
        assertFailsWith<IllegalArgumentException> { WorkoutPrecisionConfig(confirmationMs = -1) }
        assertFailsWith<IllegalArgumentException> { WorkoutPrecisionCoach(data.profile(), WorkoutPrecisionConfig(featureOverrides = mapOf("angle.typo" to WorkoutTolerance(10.0, 5.0)))) }
    }

    @Test fun phaseSpecificThresholdsOverrideWorkoutDefaultsAndWeakReferencesAbstain() {
        val config = WorkoutPrecisionConfig(stateFeatureOverrides = mapOf("state_0" to mapOf(elbow to WorkoutTolerance(20.0, 12.0))))
        val coach = WorkoutPrecisionCoach(data.profile(), config)
        assertEquals(20.0, feed(coach, 0).decisions.first { it.feature == elbow }.enterThreshold)
        val next = coach.evaluate(data.frame(80, changes = mapOf(elbow to 110.0)), data.estimate(80, state = 1))
        assertEquals(10.0, next.decisions.first { it.feature == elbow }.enterThreshold)
        val weak = WorkoutPrecisionCoach(data.profile().copy(confidence = 0.3)).evaluate(data.frame(0), data.estimate(0))
        assertEquals(WorkoutAbstention.WEAK_REFERENCE, weak.abstention)
    }

    @Test fun finiteCompletionDoesNotKeepPerfectConfidenceAfterPersonDisappears() {
        val original = data.profile()
        val finite = original.copy(cyclic = false, cycleDurationMs = null, transitions = original.transitions.filterNot { it.cyclicWrap },
            states = original.states.mapIndexed { index, state -> state.copy(features = state.features.map { feature ->
                if (feature.name.startsWith("angle.")) feature.copy(reference = feature.reference.copy(median = 45.0 + index * 45.0, lower = 43.0 + index * 45.0, upper = 47.0 + index * 45.0)) else feature
            }) })
        val recognizer = ActionStateRecognizer(finite, ActionStateRecognizerConfig(minimumEntryConfirmationMs = 0, minimumFiniteCompletionHoldMs = 0))
        var estimate = data.estimate(0)
        for (i in 0..30) {
            val state = (i / 10).coerceAtMost(2)
            estimate = recognizer.accept(data.frame(i * 80L, changes = data.angles.associateWith { 45.0 + state * 45.0 }))
        }
        assertEquals(ActionTrackingStatus.COMPLETED, estimate.status)
        val missing = data.frame(2600).let { it.copy(intrinsicDescriptor = SpatialIntrinsicDescriptor(emptyMap(), 0.0)) }
        val lost = recognizer.accept(missing)
        assertEquals(ActionTrackingStatus.LOST, lost.status)
        assertEquals(0.0, lost.confidence)
        assertEquals(1, recognizer.finish().completedRepetitions)
    }

    @Test fun differingBodyProportionsDoNotChangeRecognizedMovementWhenUsedAsContext() {
        val sequence = data.sequence(static = false)
        val profile = (ReferenceActionCompiler(ReferenceActionCompilerConfig(proportionsAreContextOnly = true)).compile(sequence) as ReferenceActionCompilation.Success).profile
        val baseline = ActionStateRecognizer(profile).recognize(sequence)
        val differentBody = sequence.copy(frames = sequence.frames.map { frame ->
            frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(values = frame.intrinsicDescriptor.values.mapValues { (name, value) ->
                if (name.startsWith("ratio.")) value * 1.8 else value
            }))
        })
        val changed = ActionStateRecognizer(profile).recognize(differentBody)
        assertTrue(baseline.trackedFraction > 0.5)
        assertEquals(baseline.estimates.map { it.stateIndex }, changed.estimates.map { it.stateIndex })
        assertEquals(baseline.completedRepetitions, changed.completedRepetitions)
    }

    @Test fun aNewPhaseRequiresFreshEvidenceAgainstItsOwnTarget() {
        val coach = WorkoutPrecisionCoach(data.profile())
        for (time in listOf(0L, 80L, 160L)) {
            assertTrue(coach.evaluate(data.frame(time, changes = mapOf(elbow to 110.0)), data.estimate(time)).triggers.isEmpty())
        }
        for (time in listOf(240L, 320L, 400L)) {
            val result = coach.evaluate(data.frame(time, changes = mapOf(elbow to 110.0)), data.estimate(time, state = 1))
            assertTrue(result.triggers.isEmpty())
        }
        val confirmed = coach.evaluate(data.frame(480L, changes = mapOf(elbow to 110.0)), data.estimate(480L, state = 1))
        assertEquals(listOf(elbow), confirmed.triggers.map { it.feature })
    }
}
