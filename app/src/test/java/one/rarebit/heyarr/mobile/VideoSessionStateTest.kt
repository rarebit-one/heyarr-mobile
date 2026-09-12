package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.playback.VideoSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scrubber's two fractions are pure arithmetic over the tick's ms values.
 * [VideoSession.State.bufferedFraction] drives the lighter "cached ahead" band the
 * PlayerScreen draws behind the played portion (parity with heyarr-desktop's
 * `PlayerState.bufferedFraction`); [VideoSession.State.fraction] drives the thumb.
 */
class VideoSessionStateTest {

    @Test fun bufferedFractionIsCachedAheadOverRuntime() {
        val s = VideoSession.State(positionMs = 30_000, durationMs = 120_000, bufferedMs = 60_000)
        assertEquals(0.25f, s.fraction, 1e-4f)
        assertEquals(0.5f, s.bufferedFraction, 1e-4f)
    }

    @Test fun fractionsAreZeroBeforeDurationIsKnown() {
        // A live/transcode stream reports no runtime until the plan's source duration
        // lands; both fractions stay 0 so the band and thumb sit at the start.
        val s = VideoSession.State(positionMs = 5_000, durationMs = 0, bufferedMs = 5_000)
        assertEquals(0f, s.fraction, 1e-4f)
        assertEquals(0f, s.bufferedFraction, 1e-4f)
    }

    @Test fun bufferedFractionClampsWhenCacheOverrunsTheRuntime() {
        // ExoPlayer can report a bufferedPosition at or past the end; the band never
        // overdraws the rail.
        val s = VideoSession.State(positionMs = 119_000, durationMs = 120_000, bufferedMs = 130_000)
        assertEquals(1f, s.bufferedFraction, 1e-4f)
    }
}
