package ai.senp.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecoderTest {
    @Test
    fun rotationSwapsDimensions() {
        assertEquals(1920 to 1080, FrameGeometry.orientedSize(1080, 1920, 90))
        assertEquals(1920 to 1080, FrameGeometry.orientedSize(1080, 1920, 270))
        assertEquals(1080 to 1920, FrameGeometry.orientedSize(1080, 1920, 180))
    }

    @Test
    fun inverseRotationMapsCornersClockwise() {
        assertEquals(0 to 1, FrameGeometry.inverseRotate(0, 0, 3, 2, 90))
        assertEquals(0 to 0, FrameGeometry.inverseRotate(1, 0, 3, 2, 90))
        assertEquals(2 to 1, FrameGeometry.inverseRotate(0, 2, 3, 2, 90))
        assertEquals(2 to 0, FrameGeometry.inverseRotate(1, 2, 3, 2, 90))

        assertEquals(2 to 1, FrameGeometry.inverseRotate(0, 0, 3, 2, 180))
        assertEquals(0 to 0, FrameGeometry.inverseRotate(2, 1, 3, 2, 180))

        assertEquals(2 to 0, FrameGeometry.inverseRotate(0, 0, 3, 2, 270))
        assertEquals(2 to 1, FrameGeometry.inverseRotate(1, 0, 3, 2, 270))
        assertEquals(0 to 0, FrameGeometry.inverseRotate(0, 2, 3, 2, 270))
        assertEquals(0 to 1, FrameGeometry.inverseRotate(1, 2, 3, 2, 270))
    }

    @Test
    fun capPreservesAspectRatio() {
        assertEquals(640 to 360, FrameGeometry.cappedSize(1920, 1080))
        assertEquals(360 to 640, FrameGeometry.cappedSize(1080, 1920))
        assertEquals(320 to 240, FrameGeometry.cappedSize(320, 240))
    }

    @Test
    fun balancedSamplerUsesTimestampsRatherThanFrameIndices() {
        val sampler = TimestampSampler(15.0)
        val pts = listOf(10_000L, 43_333L, 76_667L, 110_000L, 143_334L, 176_667L)
        assertEquals(listOf(true, false, true, false, true, false), pts.map(sampler::shouldEmit))
    }

    @Test
    fun samplerCatchesUpAcrossLargeTimestampGapWithoutBursting() {
        val sampler = TimestampSampler(15.0)
        assertTrue(sampler.shouldEmit(0L))
        assertTrue(sampler.shouldEmit(500_000L))
        assertFalse(sampler.shouldEmit(510_000L))
        assertTrue(sampler.shouldEmit(533_334L))
    }

    @Test
    fun timestampGuardRejectsDuplicateAndBackwardPresentationTimes() {
        val duplicate = MonotonicTimestampGuard().apply { accept(10L) }
        assertThrows(VideoDecodeException.NonMonotonic::class.java) { duplicate.accept(10L) }

        val backward = MonotonicTimestampGuard().apply { accept(10L) }
        assertThrows(VideoDecodeException.NonMonotonic::class.java) { backward.accept(9L) }
    }

    @Test
    fun decodedFrameDocumentsExplicitCopyForRetention() {
        val pixels = intArrayOf(1, 2, 3, 4)
        val frame = DecodedFrame(0L, 5L, 2, 2, pixels)
        assertSame(pixels, frame.argb8888)
        assertArrayEquals(pixels, frame.copyPixels())
        assertNotSame(pixels, frame.copyPixels())
    }

    @Test
    fun yuvConversionProducesNeutralBlackAndWhite() {
        assertEquals(0xff000000.toInt(), Yuv420FrameTransformer.yuvToArgb(16, 128, 128))
        assertEquals(0xffffffff.toInt(), Yuv420FrameTransformer.yuvToArgb(235, 128, 128))
    }
}
