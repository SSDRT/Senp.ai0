package ai.senp.motion

import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.FrameValidityReason
import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.ImageLandmark
import ai.senp.core.contracts.PoseLandmark
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.WorldLandmark
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreMotionProcessorTest {
    private val processor = CoreMotionProcessor()
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @Test
    fun `canonical fixture order and every validity state replay losslessly`() = runBlocking {
        val fixture = requireNotNull(javaClass.getResource("/fixtures/video-pose-contract-v2.json"))
            .readText()
            .let(Json::parseToJsonElement)
            .jsonObject
        val fixtureOrder = fixture.getValue("landmarkOrder").jsonArray.map { it.jsonPrimitive.content }
        val fixtureStates = fixture.getValue("validityStates").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(PoseLandmarkId.entries.map(Enum<*>::name), fixtureOrder)
        assertEquals(FrameValidityStatus.entries.map(Enum<*>::name), fixtureStates)

        val validity = listOf(
            ai.senp.core.contracts.FrameValidity.Valid,
            ai.senp.core.contracts.FrameValidity(
                FrameValidityStatus.REPAIRED,
                0.72,
                setOf(FrameValidityReason.SHORT_GAP_INTERPOLATION),
            ),
            ai.senp.core.contracts.FrameValidity(
                FrameValidityStatus.DEGRADED,
                0.44,
                setOf(FrameValidityReason.BELOW_TRACKING_THRESHOLD),
            ),
            ai.senp.core.contracts.FrameValidity(
                FrameValidityStatus.BLIND,
                0.0,
                setOf(FrameValidityReason.LONG_GAP),
            ),
            ai.senp.core.contracts.FrameValidity(
                FrameValidityStatus.CONTINUITY_BREAK,
                0.61,
                setOf(FrameValidityReason.TRACKING_RESET),
            ),
        )
        val timestamps = listOf(0L, 73L, 211L, 499L, 910L)
        val frames = timestamps.mapIndexed { index, timestamp ->
            canonicalFrame(
                source = SyntheticPose.frame(timestamp, phase = timestamp / 2_000.0),
                diagnosticIndex = index.toLong(),
                validity = validity[index],
                worldPolicy = { landmarkIndex -> landmarkIndex % 2 == 0 },
            )
        }

        val result = processor.process(
            PoseSequence(VideoRole.SOURCE, frames),
            MotionCoreVersions.NORMALIZATION,
            MotionCoreVersions.EXERCISE_PROFILES,
        )
        val series = assertIs<StageResult.Success<*>>(result).value as ai.senp.core.contracts.MotionSeries

        assertEquals(VideoRole.SOURCE, series.role)
        assertEquals(timestamps, series.features.map { it.timestamp.value })
        assertEquals(FrameValidityStatus.entries, series.features.map { it.validity.status })
        assertTrue(series.features[3].values.filterKeys { it.startsWith("angle.image.") && it.endsWith(".degrees") }
            .values.all { it == null })
        assertTrue(series.features[4].values.filterKeys { it.startsWith("angle.image.") && it.endsWith(".degrees") }
            .values.all { it == null })
        assertEquals(0.0, series.features[3].validity.confidence)
        assertEquals(0.0, series.features.first().values["normalization.world.available"])

        val first = json.encodeToString(series)
        val second = json.encodeToString(series)
        assertEquals(first, second)
        assertTrue(first.contains("CONTINUITY_BREAK"))
        assertTrue(first.contains(MotionFeatureSchema.angleDegrees("image", "left_elbow")))
    }

    @Test
    fun `nullable confidence and optional world coordinates map safely with irregular timestamps`() = runBlocking {
        val timestamps = listOf(0L, 87L, 233L, 421L)
        val frames = timestamps.mapIndexed { frameIndex, timestamp ->
            canonicalFrame(
                source = SyntheticPose.frame(timestamp, phase = timestamp / 1_600.0),
                diagnosticIndex = frameIndex.toLong(),
                validity = ai.senp.core.contracts.FrameValidity.Valid,
                worldPolicy = { landmarkIndex ->
                    !(frameIndex == 2 && landmarkIndex == PoseLandmarkId.LEFT_WRIST.index)
                },
                confidencePolicy = { landmarkIndex ->
                    when (landmarkIndex) {
                        PoseLandmarkId.LEFT_SHOULDER.index -> null to 0.92
                        PoseLandmarkId.LEFT_ELBOW.index -> 0.91 to null
                        PoseLandmarkId.LEFT_WRIST.index -> 0.93 to 0.93
                        PoseLandmarkId.LEFT_INDEX.index -> null to null
                        else -> 0.90 to 0.90
                    }
                },
            )
        }

        val result = processor.process(
            PoseSequence(VideoRole.REFERENCE, frames),
            MotionCoreVersions.NORMALIZATION,
            MotionCoreVersions.EXERCISE_PROFILES + "/biceps_curl",
        )
        val series = (assertIs<StageResult.Success<*>>(result).value as ai.senp.core.contracts.MotionSeries)
        val leftElbowImage = MotionFeatureSchema.angleDegrees("image", "left_elbow")
        val leftElbowWorld = MotionFeatureSchema.angleDegrees("world", "left_elbow")
        val leftElbowConfidence = MotionFeatureSchema.angleConfidence("image", "left_elbow")
        val leftElbowVelocity = MotionFeatureSchema.angularVelocity("image", "left_elbow")

        assertTrue(series.features.all { it.values[leftElbowImage] != null })
        assertTrue(series.features.all { (it.values[leftElbowConfidence] ?: 0.0) > 0.0 })
        assertTrue(series.features.all {
            it.values[MotionFeatureSchema.angleDegrees("image", "left_wrist")] == null
        })
        assertNull(series.features[2].values[leftElbowWorld])
        assertNull(series.features.first().values[leftElbowVelocity])
        assertNotNull(series.features.last().values[leftElbowVelocity])
        assertTrue(series.angles.any { it.joint == "left_elbow" && it.confidence > 0.0 })
        assertTrue(series.angles.none { it.joint.startsWith("world.") || it.joint.startsWith("image.") })
    }

    @Test
    fun `unsupported versions and empty sequences return typed motion failures`() = runBlocking {
        val frame = canonicalFrame(
            SyntheticPose.frame(0L),
            0L,
            ai.senp.core.contracts.FrameValidity.Valid,
        )
        val badNormalization = processor.process(
            PoseSequence(VideoRole.SOURCE, listOf(frame)),
            "unknown-normalization",
            MotionCoreVersions.EXERCISE_PROFILES,
        )
        val badProfile = processor.process(
            PoseSequence(VideoRole.SOURCE, listOf(frame)),
            MotionCoreVersions.NORMALIZATION,
            "unknown-profile",
        )
        val empty = processor.process(
            PoseSequence(VideoRole.REFERENCE, emptyList()),
            MotionCoreVersions.NORMALIZATION,
            MotionCoreVersions.EXERCISE_PROFILES,
        )

        assertIs<AnalysisFailure.Motion>(assertIs<StageResult.Failure>(badNormalization).failure)
        assertIs<AnalysisFailure.Motion>(assertIs<StageResult.Failure>(badProfile).failure)
        assertIs<AnalysisFailure.Motion>(assertIs<StageResult.Failure>(empty).failure)
        Unit
    }

    @Test
    fun `explicit continuity break resets elapsed-time smoothing state`() = runBlocking {
        val beforeBreak = SyntheticPose.frame(0L, phase = 0.0)
        val breakFrame = SyntheticPose.frame(100L, phase = 0.25)
        val afterBreak = SyntheticPose.frame(200L, phase = 0.30)
        val breakValidity = ai.senp.core.contracts.FrameValidity(
            FrameValidityStatus.CONTINUITY_BREAK,
            0.7,
            setOf(FrameValidityReason.TRACKING_RESET),
        )
        val withHistory = PoseSequence(
            VideoRole.SOURCE,
            listOf(
                canonicalFrame(beforeBreak, 0L, ai.senp.core.contracts.FrameValidity.Valid),
                canonicalFrame(breakFrame, 1L, breakValidity),
                canonicalFrame(afterBreak, 2L, ai.senp.core.contracts.FrameValidity.Valid),
            ),
        )
        val freshFromBreak = PoseSequence(
            VideoRole.SOURCE,
            listOf(
                canonicalFrame(breakFrame, 0L, ai.senp.core.contracts.FrameValidity.Valid),
                canonicalFrame(afterBreak, 1L, ai.senp.core.contracts.FrameValidity.Valid),
            ),
        )

        suspend fun process(sequence: PoseSequence): ai.senp.core.contracts.MotionSeries {
            val result = processor.process(
                sequence,
                MotionCoreVersions.NORMALIZATION,
                MotionCoreVersions.EXERCISE_PROFILES + "/biceps_curl",
            )
            return assertIs<StageResult.Success<ai.senp.core.contracts.MotionSeries>>(result).value
        }

        val historical = process(withHistory)
        val fresh = process(freshFromBreak)
        val angleKey = MotionFeatureSchema.angleDegrees("image", "left_elbow")
        val freshAngle = assertNotNull(fresh.features.last().values[angleKey])
        val historicalAngle = assertNotNull(historical.features.last().values[angleKey])
        assertEquals(freshAngle, historicalAngle, absoluteTolerance = 1e-12)
        assertEquals(FrameValidityStatus.CONTINUITY_BREAK, historical.features[1].validity.status)
    }

    private fun canonicalFrame(
        source: PoseFrame,
        diagnosticIndex: Long,
        validity: ai.senp.core.contracts.FrameValidity,
        worldPolicy: (Int) -> Boolean = { true },
        confidencePolicy: (Int) -> Pair<Double?, Double?> = { index ->
            source.landmarks[index].visibility to source.landmarks[index].presence
        },
    ): ai.senp.core.contracts.PoseFrame = ai.senp.core.contracts.PoseFrame(
        timestamp = TimestampMs(source.timestampMs),
        diagnosticFrameIndex = diagnosticIndex,
        landmarks = PoseLandmarkId.entries.map { id ->
            val sourceLandmark = source.landmarks[id.index]
            val image = requireNotNull(sourceLandmark.image)
            val world = sourceLandmark.world
            val confidences = confidencePolicy(id.index)
            PoseLandmark(
                id = id,
                image = ImageLandmark(image.x, image.y, image.z),
                world = if (world != null && worldPolicy(id.index)) {
                    WorldLandmark(world.x, world.y, world.z)
                } else {
                    null
                },
                visibility = confidences.first,
                presence = confidences.second,
            )
        },
        validity = validity,
    )
}
