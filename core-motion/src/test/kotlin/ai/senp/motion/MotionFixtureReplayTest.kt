package ai.senp.motion

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class MotionFixtureReplayTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `native MP33 fixture replays every motion-core stage`() {
        val root = resourceJson("fixtures/${MotionFixtureGenerator.NATIVE_FIXTURE_FILE}")
        assertEquals(MotionCoreVersions.FIXTURE_SCHEMA, root.int("fixture_schema_version"))
        assertEquals("mp33-squat-motion-core-v1", root.string("fixture_id"))
        assertEquals("mediapipe_pose_33", root.string("skeleton"))
        assertEquals("milliseconds", root.string("time_unit"))
        assertEquals(MotionCoreVersions.PIPELINE, root.obj("versions").string("pipeline"))
        assertEquals(MotionCoreVersions.MP33_CONTRACT, root.obj("versions").string("landmark_contract"))
        assertEquals(MotionCoreVersions.EXERCISE_PROFILES, root.obj("versions").string("exercise_profiles"))
        assertEquals(
            "a54a8453907a6cd1ece61ad7565020a98118c032",
            root.obj("provenance").string("behavior_reference_commit"),
        )

        val input = root.array("input_frames").map(::parseFrame)
        assertTrue(input.zipWithNext().all { (a, b) -> b.timestampMs > a.timestampMs })
        assertTrue(input.all { it.landmarks.size == LandmarkId.COUNT })

        val actual = MotionEngine(MotionFixtureGenerator.fixtureConfig).analyze(input, ExerciseProfiles.squat)
        val expected = root.array("expected_frames")
        val tolerance = root.double("tolerance")
        assertEquals(expected.size, actual.size)

        expected.indices.forEach { index ->
            val expectedFrame = expected[index].jsonObject
            val actualFrame = actual[index]
            assertEquals(expectedFrame.long("timestamp_ms"), actualFrame.frame.timestampMs)
            compareLandmarks(expectedFrame.array("tracked_landmarks"), actualFrame.frame.landmarks, tolerance)
            compareQuality(expectedFrame.obj("quality"), actualFrame.quality, tolerance)
            assertEquals(expectedFrame.landmarkSet("repaired_landmarks"), actualFrame.repairedLandmarks)
            assertEquals(expectedFrame.landmarkSet("continuity_break_landmarks"), actualFrame.continuityBreakLandmarks)

            val expectedGuardrails = expectedFrame.obj("guardrails")
            assertEquals(expectedGuardrails.boolean("left_right_swap_applied"), actualFrame.guardrails.leftRightSwapApplied)
            assertEquals(expectedGuardrails.boolean("impossible_proportions"), actualFrame.guardrails.impossibleProportions)

            val expectedNormalization = expectedFrame.obj("normalized_image")
            val actualNormalization = PoseNormalizer(MotionFixtureGenerator.fixtureConfig.minScale).normalizeImage(actualFrame.frame)
            assertEquals(expectedNormalization.string("status"), actualNormalization.status.name)
            compareNullableVec(expectedNormalization["root"], actualNormalization.root, tolerance)
            compareNullableDouble(expectedNormalization["scale"], actualNormalization.scale, tolerance)
            compareLandmarks(expectedNormalization.array("landmarks"), actualNormalization.frame.landmarks, tolerance)

            val expectedWorldNormalization = expectedFrame.obj("normalized_world")
            val actualWorldNormalization = PoseNormalizer(MotionFixtureGenerator.fixtureConfig.minScale)
                .normalizeWorld(actualFrame.frame, orientToBodyAxes = true)
            assertEquals(expectedWorldNormalization.string("status"), actualWorldNormalization.status.name)
            compareNullableVec(expectedWorldNormalization["root"], actualWorldNormalization.root, tolerance)
            compareNullableDouble(expectedWorldNormalization["scale"], actualWorldNormalization.scale, tolerance)
            compareLandmarks(
                expectedWorldNormalization.array("landmarks"),
                actualWorldNormalization.frame.landmarks,
                tolerance,
            )

            compareNullableNumberMap(
                expectedFrame.obj("angles_image_deg"),
                MotionFeatures.angles(
                    actualFrame.frame,
                    actualFrame.quality.validity,
                    coordinateSpace = CoordinateSpace.IMAGE,
                ),
                tolerance,
            )
            compareNullableNumberMap(
                expectedFrame.obj("angles_world_deg"),
                MotionFeatures.angles(
                    actualFrame.frame,
                    actualFrame.quality.validity,
                    coordinateSpace = CoordinateSpace.WORLD,
                ),
                tolerance,
            )
            compareTorso(expectedFrame["torso_image"], MotionFeatures.torso(actualFrame.frame), tolerance)
        }
    }

    @Test
    fun `native squat fixture covers repair blind recovery and preferred-only loss`() {
        val frames = MotionFixtureGenerator.nativeSquatInput()
        val result = MotionEngine(MotionFixtureGenerator.fixtureConfig).analyze(frames, ExerciseProfiles.squat)

        val repaired = result.filter { it.frame.timestampMs in 1600L..1667L }
        assertTrue(repaired.isNotEmpty())
        assertTrue(repaired.all { LandmarkId.LEFT_KNEE in it.repairedLandmarks })
        assertTrue(repaired.all { it.quality.validity == FrameValidity.REPAIRED })

        val blind = result.filter { it.frame.timestampMs in 2800L..3600L }
        assertTrue(blind.isNotEmpty())
        assertTrue(blind.all { it.quality.validity == FrameValidity.BLIND })
        assertTrue(blind.all { MotionFeatures.angles(it.frame, it.quality.validity).values.all { angle -> angle == null } })

        val recovery = result.first { it.frame.timestampMs > 3600L }
        assertEquals(FrameValidity.CONTINUITY_BREAK, recovery.quality.validity)
        assertTrue(ExerciseProfiles.squat.required.all { it in recovery.continuityBreakLandmarks })

        val preferredLoss = result.filter { it.frame.timestampMs in 4600L..4733L }
        assertTrue(preferredLoss.isNotEmpty())
        assertTrue(preferredLoss.none { it.quality.validity == FrameValidity.BLIND })
        assertTrue(preferredLoss.all { it.quality.requiredCoverage > 0.99 })
        assertTrue(preferredLoss.all { it.quality.preferredQuality < 0.75 })

        val validKneeAngles = result.mapNotNull {
            MotionFeatures.angles(it.frame, it.quality.validity)["left_knee"]
        }
        assertTrue(validKneeAngles.max() - validKneeAngles.min() > 25.0)
    }

    @Test
    fun `legacy COCO17 fixture is provenance-locked and compatible without fake landmarks`() {
        val manifest = resourceJson("fixtures/legacy_coco17_motion_core_a54a845.manifest.json")
        assertEquals(MotionCoreVersions.FIXTURE_SCHEMA, manifest.int("fixture_schema_version"))
        assertEquals("a54a8453907a6cd1ece61ad7565020a98118c032", manifest.string("source_commit"))
        assertEquals(MotionCoreVersions.COCO17_ADAPTER, manifest.string("adapter_version"))

        val sourcePath = "fixtures/${manifest.string("source_file")}"
        val sourceBytes = resourceBytes(sourcePath)
        val sourceSha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(sourceBytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        assertEquals(manifest.int("source_size_bytes"), sourceBytes.size)
        assertEquals(manifest.string("source_sha256"), sourceSha256)
        val legacy = json.parseToJsonElement(sourceBytes.decodeToString()).jsonObject
        val input = legacy.obj("input")
        val xyFrames = input.array("keypoints_xy")
        val confidenceFrames = input.array("keypoints_conf")
        val frames = xyFrames.indices.map { frameIndex ->
            val image = xyFrames[frameIndex].jsonArray.map { point ->
                point.jsonArray.let { Vec3(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double, 0.0) }
            }
            val confidence = confidenceFrames[frameIndex].jsonArray.map { it.jsonPrimitive.double }
            Coco17Adapter.fromArrays(
                timestampMs = (frameIndex * 1000.0 / 15.0).roundToLong(),
                image = image,
                confidence = confidence,
            )
        }
        assertTrue(frames.all { it.landmarks.size == LandmarkId.COUNT })
        assertTrue(frames.all { it[LandmarkId.LEFT_EYE_INNER].image == null })
        assertTrue(frames.all { it[LandmarkId.LEFT_PINKY].world == null })
        assertEquals(
            confidenceFrames[0].jsonArray[Coco17LandmarkId.LEFT_WRIST.index].jsonPrimitive.double,
            frames[0][LandmarkId.LEFT_WRIST].visibility,
            1e-12,
        )

        val frameIntervalMs = 1000.0 / 15.0
        val halfLifeMs = (-ln(2.0) * frameIntervalMs / ln(1.0 - 0.7)).roundToLong()
        val config = MotionConfig(
            minVisibility = 0.5,
            minPresence = 0.5,
            maxRepairGapMs = 270L,
            continuityBreakGapMs = 450L,
            emaHalfLifeMs = halfLifeMs,
            blindEnterDurationMs = 460L,
            recoverDurationMs = 330L,
        )
        val tracked = TrackProcessor(config).processDetailed(frames)
        val smoothExpected = legacy.obj("smooth").array("keypoints_xy")
        for (frameIndex in 0..9) {
            for (cocoId in Coco17LandmarkId.entries) {
                val expectedPoint = smoothExpected[frameIndex].jsonArray[cocoId.index].jsonArray
                val actualPoint = tracked[frameIndex].frame[cocoId.mediaPipeId].image!!
                assertEquals(expectedPoint[0].jsonPrimitive.double, actualPoint.x, 1.6)
                assertEquals(expectedPoint[1].jsonPrimitive.double, actualPoint.y, 1.6)
            }
        }
        assertTrue((10..12).all { LandmarkId.LEFT_WRIST in tracked[it].repairedLandmarks })
        assertTrue((20..25).all { LandmarkId.RIGHT_WRIST !in tracked[it].repairedLandmarks })
        assertTrue((20..25).all { tracked[it].frame[LandmarkId.RIGHT_WRIST].image == null })
        assertTrue((20..25).all { tracked[it].frame[LandmarkId.RIGHT_WRIST].visibility == 0.0 })
        assertTrue(LandmarkId.RIGHT_WRIST in tracked[26].continuityBreakLandmarks)

        val required = Coco17LandmarkId.entries.mapTo(linkedSetOf()) { it.mediaPipeId }
        val profile = ExerciseProfile("legacy_coco17", required, minimumRequiredCoverage = 0.75)
        val quality = QualityGate(config).evaluateTracked(tracked, profile)
        val blindIndices = quality.indices.filter { quality[it].validity == FrameValidity.BLIND }
        assertEquals((32..43).toList(), blindIndices)

        val legacyAngleFrames = legacy.obj("angles").array("frames")
        assertTrue(blindIndices.all { frameIndex ->
            legacyAngleFrames[frameIndex].jsonObject.values.all { value -> value is JsonNull }
        })
        val healthyLeftElbows = legacyAngleFrames
            .filterIndexed { index, _ -> index !in blindIndices }
            .mapNotNull { it.jsonObject["left_elbow"]?.jsonPrimitive?.doubleOrNull }
        assertTrue(healthyLeftElbows.max() - healthyLeftElbows.min() > 50.0)

        val edgeCases = legacy.obj("angle_edge_cases")
        val edgeXyz = edgeCases.array("keypoints_xyz")
        val edgeConfidence = edgeCases.array("keypoints_conf")
        val edgeAngles = edgeXyz.indices.map { index ->
            val points = edgeXyz[index].jsonArray.map { point ->
                point.jsonArray.let { Vec3(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double, it[2].jsonPrimitive.double) }
            }
            val confidence = edgeConfidence[index].jsonArray.map { it.jsonPrimitive.double }
            val frame = Coco17Adapter.fromArrays(index.toLong(), points, confidence, points)
            MotionFeatures.angleFor(
                frame,
                MotionFeatures.angleDefinitions.getValue("left_elbow"),
                coordinateSpace = CoordinateSpace.WORLD,
            )
        }
        assertEquals(90.0, edgeAngles[0]!!, 1e-9)
        assertEquals(180.0, edgeAngles[1]!!, 1e-9)
        assertNull(edgeAngles[2])
    }

    @Test
    fun `image angles are planar while world angles retain metric depth`() {
        val base = SyntheticPose.frame(0L)
        val changedDepth = base.copy(landmarks = base.landmarks.mapIndexed { index, landmark ->
            val id = LandmarkId.entries[index]
            if (id in setOf(LandmarkId.LEFT_SHOULDER, LandmarkId.LEFT_ELBOW, LandmarkId.LEFT_WRIST)) {
                landmark.copy(image = landmark.image!!.copy(z = 20.0 * (index + 1)))
            } else {
                landmark
            }
        })
        val definition = MotionFeatures.angleDefinitions.getValue("left_elbow")
        val planarBase = MotionFeatures.angleFor(base, definition, coordinateSpace = CoordinateSpace.IMAGE)
        val planarChanged = MotionFeatures.angleFor(changedDepth, definition, coordinateSpace = CoordinateSpace.IMAGE)
        assertEquals(planarBase!!, planarChanged!!, 1e-12)

        val worldChanged = changedDepth.copy(landmarks = changedDepth.landmarks.mapIndexed { index, landmark ->
            val id = LandmarkId.entries[index]
            if (id in setOf(LandmarkId.LEFT_SHOULDER, LandmarkId.LEFT_ELBOW, LandmarkId.LEFT_WRIST)) {
                landmark.copy(world = landmark.world!!.copy(z = 0.3 * index))
            } else landmark
        })
        val worldBaseAngle = MotionFeatures.angleFor(base, definition, coordinateSpace = CoordinateSpace.WORLD)
        val worldChangedAngle = MotionFeatures.angleFor(worldChanged, definition, coordinateSpace = CoordinateSpace.WORLD)
        assertTrue(abs(worldBaseAngle!! - worldChangedAngle!!) > 1.0)
    }

    @Test
    fun `normalization returns typed failures for missing and degenerate squat anchors`() {
        val normalizer = PoseNormalizer()
        val base = SyntheticPose.squatFrame(0L, 0.5)
        val missing = base.copy(landmarks = base.landmarks.toMutableList().also {
            it[LandmarkId.LEFT_HIP.index] = it[LandmarkId.LEFT_HIP.index].copy(image = null)
        })
        assertEquals(NormalizationStatus.MISSING_ANCHORS, normalizer.normalizeImage(missing).status)

        val collapsed = base.copy(landmarks = base.landmarks.toMutableList().also { landmarks ->
            val hipCenter = (landmarks[LandmarkId.LEFT_HIP.index].image!! + landmarks[LandmarkId.RIGHT_HIP.index].image!!) / 2.0
            landmarks[LandmarkId.LEFT_SHOULDER.index] = landmarks[LandmarkId.LEFT_SHOULDER.index].copy(image = hipCenter)
            landmarks[LandmarkId.RIGHT_SHOULDER.index] = landmarks[LandmarkId.RIGHT_SHOULDER.index].copy(image = hipCenter)
        })
        assertEquals(NormalizationStatus.DEGENERATE_SCALE, normalizer.normalizeImage(collapsed).status)
    }

    private fun parseFrame(element: JsonElement): PoseFrame {
        val frame = element.jsonObject
        return PoseFrame(
            timestampMs = frame.long("timestamp_ms"),
            landmarks = frame.array("landmarks").map { landmarkElement ->
                val landmark = landmarkElement.jsonObject
                Landmark(
                    image = parseVec(landmark["image"]),
                    world = parseVec(landmark["world"]),
                    visibility = landmark.double("visibility"),
                    presence = landmark.double("presence"),
                    repaired = landmark.boolean("repaired"),
                )
            },
        )
    }

    private fun compareQuality(expected: JsonObject, actual: QualityResult, tolerance: Double) {
        assertEquals(expected.string("validity"), actual.validity.name)
        assertEquals(expected["selected_side"]?.jsonPrimitive?.contentOrNull, actual.selectedSide?.name)
        assertEquals(expected.double("score"), actual.score, tolerance)
        assertEquals(expected.double("required_coverage"), actual.requiredCoverage, tolerance)
        assertEquals(expected.double("required_visibility"), actual.requiredVisibility, tolerance)
        assertEquals(expected.double("required_presence"), actual.requiredPresence, tolerance)
        assertEquals(expected.double("preferred_quality"), actual.preferredQuality, tolerance)
        assertEquals(expected.double("repaired_fraction"), actual.repairedFraction, tolerance)
        assertEquals(expected.double("clipping"), actual.clipping, tolerance)
        assertEquals(expected.double("instability"), actual.instability, tolerance)
    }

    private fun compareLandmarks(expected: JsonArray, actual: List<Landmark>, tolerance: Double) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            val expectedLandmark = expected[index].jsonObject
            val actualLandmark = actual[index]
            compareNullableVec(expectedLandmark["image"], actualLandmark.image, tolerance)
            compareNullableVec(expectedLandmark["world"], actualLandmark.world, tolerance)
            assertEquals(expectedLandmark.double("visibility"), actualLandmark.visibility, tolerance)
            assertEquals(expectedLandmark.double("presence"), actualLandmark.presence, tolerance)
            assertEquals(expectedLandmark.boolean("repaired"), actualLandmark.repaired)
        }
    }

    private fun compareNullableNumberMap(expected: JsonObject, actual: Map<String, Double?>, tolerance: Double) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, value) -> compareNullableDouble(value, actual[name], tolerance) }
    }

    private fun compareTorso(expected: JsonElement?, actual: TorsoFeatures?, tolerance: Double) {
        if (expected == null || expected is JsonNull) {
            assertNull(actual)
            return
        }
        val value = assertNotNull(actual)
        val objectValue = expected.jsonObject
        compareNullableVec(objectValue["pelvis_center"], value.pelvisCenter, tolerance)
        compareNullableVec(objectValue["shoulder_center"], value.shoulderCenter, tolerance)
        compareNullableVec(objectValue["torso_vector"], value.torsoVector, tolerance)
        assertEquals(objectValue.double("torso_length"), value.torsoLength, tolerance)
        assertEquals(objectValue.double("lean_from_vertical_deg"), value.leanFromVerticalDeg, tolerance)
        assertEquals(objectValue.double("shoulder_tilt_deg"), value.shoulderTiltDeg, tolerance)
        assertEquals(objectValue.double("hip_tilt_deg"), value.hipTiltDeg, tolerance)
    }

    private fun compareNullableVec(expected: JsonElement?, actual: Vec3?, tolerance: Double) {
        if (expected == null || expected is JsonNull) {
            assertNull(actual)
            return
        }
        val vector = assertNotNull(actual)
        val array = expected.jsonArray
        assertEquals(array[0].jsonPrimitive.double, vector.x, tolerance)
        assertEquals(array[1].jsonPrimitive.double, vector.y, tolerance)
        assertEquals(array[2].jsonPrimitive.double, vector.z, tolerance)
    }

    private fun compareNullableDouble(expected: JsonElement?, actual: Double?, tolerance: Double) {
        if (expected == null || expected is JsonNull) {
            assertNull(actual)
        } else {
            assertEquals(expected.jsonPrimitive.doubleOrNull!!, assertNotNull(actual), tolerance)
        }
    }

    private fun parseVec(element: JsonElement?): Vec3? {
        if (element == null || element is JsonNull) return null
        val values = element.jsonArray
        return Vec3(values[0].jsonPrimitive.double, values[1].jsonPrimitive.double, values[2].jsonPrimitive.double)
    }

    private fun resourceBytes(path: String): ByteArray {
        val sourceResource = java.io.File("src/test/resources", path)
        return if (sourceResource.isFile) {
            sourceResource.readBytes()
        } else {
            requireNotNull(javaClass.classLoader.getResource(path)) { "missing resource $path" }.readBytes()
        }
    }

    private fun resourceJson(path: String): JsonObject =
        json.parseToJsonElement(resourceBytes(path).decodeToString()).jsonObject

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
    private fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.double(name: String): Double = getValue(name).jsonPrimitive.double
    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long
    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
    private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean
    private fun JsonObject.landmarkSet(name: String): Set<LandmarkId> =
        array(name).mapTo(linkedSetOf()) { LandmarkId.valueOf(it.jsonPrimitive.content) }
}
