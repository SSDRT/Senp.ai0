package ai.senp.motion.normalize

import ai.senp.motion.angles.AngleEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Expected values are the output of the reference implementation
 * (`backend/app/services/normalize_service.py`) on the same geometry, taking its root-relative
 * and torso-scale steps only. Bone-length enforcement is deliberately not ported.
 */
class NormalizerTest {

    private val tolerance = 1e-5f
    private val landmarks = 33

    private fun pose(vararg points: Pair<Int, Triple<Float, Float, Float>>): FloatArray {
        val xyz = FloatArray(landmarks * 3)
        points.forEach { (index, p) ->
            xyz[index * 3] = p.first
            xyz[index * 3 + 1] = p.second
            xyz[index * 3 + 2] = p.third
        }
        return xyz
    }

    /** Torso spanning 0.5, pelvis at (0, 0.5, 0). */
    private fun referencePose() = pose(
        Normalizer.LEFT_HIP to Triple(0.1f, 0.5f, 0.0f),
        Normalizer.RIGHT_HIP to Triple(-0.1f, 0.5f, 0.0f),
        Normalizer.LEFT_SHOULDER to Triple(0.15f, 1.0f, 0.05f),
        Normalizer.RIGHT_SHOULDER to Triple(-0.15f, 1.0f, -0.05f),
        25 to Triple(0.12f, 0.1f, 0.02f), // left knee
        27 to Triple(0.08f, -0.4f, 0.10f), // left ankle
    )

    private fun FloatArray.at(index: Int) =
        Triple(this[index * 3], this[index * 3 + 1], this[index * 3 + 2])

    private fun assertPoint(expected: Triple<Float, Float, Float>, actual: Triple<Float, Float, Float>) {
        assertEquals(expected.first, actual.first, tolerance)
        assertEquals(expected.second, actual.second, tolerance)
        assertEquals(expected.third, actual.third, tolerance)
    }

    @Test
    fun `matches reference root relative and torso scaling`() {
        assertEquals(0.5f, assertNotNull(Normalizer.torsoLength(referencePose())), tolerance)
        val out = assertNotNull(Normalizer.normalizeFrame(referencePose()))

        assertPoint(Triple(0.24f, -0.8f, 0.04f), out.at(25))
        assertPoint(Triple(0.16f, -1.8f, 0.2f), out.at(27))
        assertPoint(Triple(0.2f, 0.0f, 0.0f), out.at(Normalizer.LEFT_HIP))
        assertPoint(Triple(0.3f, 1.0f, 0.1f), out.at(Normalizer.LEFT_SHOULDER))
    }

    @Test
    fun `output is invariant under translation and uniform scale`() {
        val original = assertNotNull(Normalizer.normalizeFrame(referencePose()))

        val moved = referencePose().copyOf()
        for (i in moved.indices) {
            val offset = when (i % 3) { 0 -> 3f; 1 -> -2f; else -> 5f }
            moved[i] = moved[i] * 7f + offset
        }
        val after = assertNotNull(Normalizer.normalizeFrame(moved))

        for (i in original.indices) assertEquals(original[i], after[i], 1e-4f)
    }

    @Test
    fun `a frame missing an anchor cannot be normalized`() {
        val noHip = referencePose().also { it[Normalizer.LEFT_HIP * 3] = Float.NaN }
        assertNull(Normalizer.torsoLength(noHip))
        assertNull(Normalizer.normalizeFrame(noHip))

        val noShoulder = referencePose().also { it[Normalizer.RIGHT_SHOULDER * 3 + 1] = Float.NaN }
        assertNull(Normalizer.normalizeFrame(noShoulder))
    }

    @Test
    fun `a missing non anchor landmark stays missing`() {
        val xyz = referencePose().also { it[25 * 3] = Float.NaN }
        val out = assertNotNull(Normalizer.normalizeFrame(xyz))
        assertTrue(out[25 * 3].isNaN(), "a missing knee must not be invented by normalization")
        assertPoint(Triple(0.16f, -1.8f, 0.2f), out.at(27))
    }

    @Test
    fun `a collapsed torso does not blow up the frame`() {
        val flat = pose(
            Normalizer.LEFT_HIP to Triple(0.1f, 0.5f, 0f),
            Normalizer.RIGHT_HIP to Triple(-0.1f, 0.5f, 0f),
            Normalizer.LEFT_SHOULDER to Triple(0.1f, 0.5f, 0f),
            Normalizer.RIGHT_SHOULDER to Triple(-0.1f, 0.5f, 0f),
        )
        val torso = assertNotNull(Normalizer.torsoLength(flat))
        assertTrue(torso > 0f, "torso length must stay positive, was $torso")
        assertTrue(Normalizer.normalizeFrame(flat)!!.all { it.isFinite() })
    }

    @Test
    fun `a clip shares one scale so leaning does not rescale the subject`() {
        val upright = referencePose()
        val leaning = referencePose().also { // torso appears shorter
            it[Normalizer.LEFT_SHOULDER * 3 + 1] = 0.8f
            it[Normalizer.RIGHT_SHOULDER * 3 + 1] = 0.8f
        }
        val frames = arrayOf(upright, leaning, upright)

        val shared = Normalizer.normalizeClip(frames)
        val perFrame = Normalizer.normalizeClip(frames, perFrameScale = true)

        // The knee does not move between the two poses, so with a shared scale it must not
        // move in the output either.
        assertPoint(assertNotNull(shared[0]).at(25), assertNotNull(shared[1]).at(25))
        assertTrue(
            assertNotNull(perFrame[1]).at(25).second != assertNotNull(perFrame[0]).at(25).second,
            "a per-frame scale should let the apparent lean rescale the knee",
        )
    }

    @Test
    fun `unusable frames come back as null so indices still line up`() {
        val frames = arrayOf(referencePose(), FloatArray(landmarks * 3) { Float.NaN }, referencePose())
        val out = Normalizer.normalizeClip(frames)
        assertEquals(3, out.size)
        assertNotNull(out[0])
        assertNull(out[1])
        assertNotNull(out[2])
    }

    @Test
    fun `normalization leaves angles untouched`() {
        // Why AngleEngine reads world landmarks and never runs through here.
        val raw = referencePose()
        val normalized = assertNotNull(Normalizer.normalizeFrame(raw))

        val before = assertNotNull(AngleEngine.angleDeg(raw, 23, 25, 27))
        val after = assertNotNull(AngleEngine.angleDeg(normalized, 23, 25, 27))
        assertEquals(before, after, 1e-3f)
    }

    @Test
    fun `empty clip is handled`() {
        assertEquals(0, Normalizer.normalizeClip(emptyArray()).size)
        assertNull(Normalizer.clipTorsoLength(emptyArray()))
    }
}
