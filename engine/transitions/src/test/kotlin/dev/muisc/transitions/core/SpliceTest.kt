package dev.muisc.transitions.core

import dev.muisc.analysis.model.Mode
import dev.muisc.audio.AudioBuffer
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderReport
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionPlan
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpliceTest {
    @Test
    fun microCrossfadeAndJoin() {
        val sig = FloatArray(200) { kotlin.math.sin(it * 0.3).toFloat() }
        val lin = Splice.microCrossfade(sig, sig.copyOfRange(136, 200), 64, FadeLaw.LINEAR)
        assertEquals(64, lin.size)
        for (i in 0 until 64) assertEquals(sig[136 + i], lin[i], 1e-6f, "linear blend of identical material is a no-op")
        val ep = Splice.microCrossfade(sig, sig.copyOfRange(136, 200), 64)
        var bump = 0f
        for (i in 0 until 64) if (abs(sig[136 + i]) > 0.5f) bump = maxOf(bump, abs(ep[i] / sig[136 + i]))
        assertTrue(bump > 1.2f && bump < 1.5f, "equal-power on identical material bumps by up to +3 dB (got x$bump)")
        val a = AudioBuffer.stereo(100, FloatArray(300) { 1f }, FloatArray(300) { 1f })
        val b = AudioBuffer.stereo(100, FloatArray(200) { 0f }, FloatArray(200) { 0f })
        val j = Splice.join(a, b, 64, FadeLaw.LINEAR)
        assertEquals(300 + 200 - 64, j.frames)
        assertEquals(1f, j[0][235]); assertEquals(0f, j[1][300]); assertTrue(j[0][236] < 1f && j[0][236] > 0.9f)
        assertEquals(1f - 32f / 64f, j[0][236 + 31], 1e-6f)
    }

    @Test
    fun mixAppliesLanesInOutputFrames() {
        val a = AudioBuffer.stereo(100, FloatArray(100) { 1f }, FloatArray(100) { 2f })
        val b = AudioBuffer.stereo(100, FloatArray(60) { 10f }, FloatArray(60) { 20f })
        val la = Lane("a").add(0, 1.0).add(40, 1.0).add(80, 0.0)
        val lb = Lane("b").add(40, 0.0).add(80, 1.0)
        val out = Splice.mix(a, la, b, lb, bOffsetFrames = 40, outFrames = 120)
        assertEquals(120, out.frames)
        assertEquals(1f, out[0][10]); assertEquals(2f, out[1][10])
        assertEquals(1f, out[0][40], 1e-6f)
        assertEquals(0.5f + 5f, out[0][60], 1e-5f)
        assertEquals(0f + 20f, out[1][99], 1e-5f, "A is gone (holds 0 after its last point), B at unity")
        assertEquals(0f, out[0][100], "neither source has samples here")
        val dst = AudioBuffer.silence(100, 2, 50)
        Splice.addInPlace(dst, a, dstOffset = -10, gain = null, srcOffset = 0, frames = 30)
        assertEquals(1f, dst[0][0]); assertEquals(1f, dst[0][19]); assertEquals(0f, dst[0][20])
    }

    private fun rendered(audio: AudioBuffer, plan: TransitionPlan) =
        RenderedTransition(plan, audio, emptyList(), RenderReport(0, audio.peak(), 0f, 0f))

    @Test
    fun spliceCheckAcceptsExactCopiesAndRejectsDeviations() {
        val a = SongFixtures.song("A", 120.0, 0, Mode.MAJOR, bars = 8, introBars = 0, outroBars = 0)
        val b = SongFixtures.song("B", 126.0, 9, Mode.MINOR, bars = 8, introBars = 0, outroBars = 0)
        val g = Splice.GUARD_FRAMES
        // A window carries 1000 frames of context before aExitFrame plus 5000 frames from it on; B window 5000 frames up to bEntryFrame.
        val aWin = FrameRange(10_000L, 10_000L + 1000 + 5000)
        val bWin = FrameRange(20_000L, 20_000L + 5000)
        val plan = TransitionPlan("x", Params.EMPTY, aExitFrame = aWin.start + 1000, bEntryFrame = bWin.end, aWindow = aWin, bWindow = bWin, expectedOutputFrames = 5000 + 5000)
        val prefs = SongFixtures.prefs(a, b)
        val input = SongFixtures.input(plan, a, b, prefs)
        val audio = input.aAudio.slice(1000, input.aAudio.frames).concat(input.bAudio)
        assertEquals(plan.expectedOutputFrames, audio.frames)
        assertEquals(1000, plan.aExitOffset); assertEquals(5000, plan.bEntryOffset)
        assertEquals(emptyList(), SpliceCheck.verify(rendered(audio, plan), input))
        // a 2e-3 deviation inside the prefix is flagged, one just outside is not
        val bad = audio.copy(); bad[1][100] += 0.002f
        val msgs = SpliceCheck.verify(rendered(bad, plan), input)
        assertEquals(1, msgs.size); assertTrue(msgs[0].startsWith("prefix"), msgs[0])
        val ok = audio.copy(); ok[0][1000] += 0.5f
        assertEquals(emptyList(), SpliceCheck.verify(rendered(ok, plan), input, prefix = 1000, suffix = 4096))
        val badTail = audio.copy(); badTail[0][audio.frames - 1] = 0.9f
        assertTrue(SpliceCheck.verify(rendered(badTail, plan), input).any { it.startsWith("suffix") })
        // length off by more than 1 % and NaN are flagged
        val short = audio.slice(0, audio.frames - 200)
        val m2 = SpliceCheck.verify(rendered(short, plan), input)
        assertTrue(m2.any { it.startsWith("length") } && m2.any { it.startsWith("suffix") }, m2.toString())
        val nan = audio.copy(); nan[0][3000] = Float.NaN
        assertTrue(SpliceCheck.verify(rendered(nan, plan), input).any { it.contains("non-finite") })
        // a plan whose B window cannot hold the suffix is reported rather than crashing
        val tiny = TransitionPlan("x", Params.EMPTY, aExitFrame = aWin.start + 1000, bEntryFrame = 20_100L, aWindow = aWin, bWindow = FrameRange(20_000L, 20_100L), expectedOutputFrames = 5100)
        val tinyInput = SongFixtures.input(tiny, a, b, prefs)
        val tinyAudio = tinyInput.aAudio.slice(1000, input.aAudio.frames).concat(tinyInput.bAudio)
        assertEquals(g, Splice.GUARD_FRAMES)
        assertTrue(SpliceCheck.verify(rendered(tinyAudio, tiny), tinyInput).any { it.startsWith("bWindow starts") })
    }
}
