package com.situ.aichat.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function tests for [ImageScaler.computeInSampleSize] (the only device-independent piece of the
 * image pipeline). Assertions reason from the algorithm's contract — largest power-of-two factor that
 * keeps `max(w,h)/2 >= maxEdge` — so they catch off-by-one / wrong-loop-bound porting bugs.
 */
class ImageScalerTest {

    @Test fun `image at or below cap stays sample 1`() {
        assertEquals(1, ImageScaler.computeInSampleSize(1024, 768, 1024))
        assertEquals(1, ImageScaler.computeInSampleSize(500, 400, 512))
        assertEquals(1, ImageScaler.computeInSampleSize(1, 1, 1024))
    }

    @Test fun `just above the halving threshold still sample 1`() {
        // max/2 = 1023 < 1024 → loop never runs.
        assertEquals(1, ImageScaler.computeInSampleSize(2047, 100, 1024))
    }

    @Test fun `exactly double the cap halves once`() {
        // max/2 = 1024 >= 1024 → one halving, then 512 < 1024 stops.
        assertEquals(2, ImageScaler.computeInSampleSize(2048, 1536, 1024))
    }

    @Test fun `large image samples down by powers of two`() {
        // 4096: /2=2048>=1024 (s2), /2=1024>=1024 (s4), /2=512<1024 stop.
        assertEquals(4, ImageScaler.computeInSampleSize(4096, 4096, 1024))
        // 8192 → 2,4,8
        assertEquals(8, ImageScaler.computeInSampleSize(8192, 6000, 1024))
    }

    @Test fun `avatar cap 512 behaves the same with its own edge`() {
        assertEquals(1, ImageScaler.computeInSampleSize(512, 512, 512))
        assertEquals(2, ImageScaler.computeInSampleSize(1024, 1024, 512))
        assertEquals(4, ImageScaler.computeInSampleSize(2048, 100, 512))
    }
}
