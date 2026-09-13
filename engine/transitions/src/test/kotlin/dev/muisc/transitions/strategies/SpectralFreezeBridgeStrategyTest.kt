package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.Mode
import dev.muisc.dsp.texture.ThirdOctaveBands
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.Params
import dev.muisc.transitions.core.Splice
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectralFreezeBridgeStrategyTest {
    private val sr = SbFixtures.SR
    private val strategy = SpectralFreezeBridgeStrategy()
    /** A ends with a 4-bar ambient outro (pad + bass, no drums): a sustained chord to freeze. */
    private val pair by lazy { SbFixtures.pair(SbFixtures.song(120.0, 0, Mode.MAJOR), SbFixtures.song(126.0, 9, Mode.MINOR)) }
    private val rendered by lazy { SbFixtures.render(strategy, pair, Params.EMPTY, seed = 11L) }

    @Test
    fun applicabilityWantsASustainedEnding() {
        assertEquals("spectralFreezeBridge", strategy.id)
        assertEquals(listOf("holdBeats", "releaseBars", "darkenToHz", "freezeDb", "bEnterBeat", "bFadeBars", "freezeSourceMs", "freezeFadeMs", "freezeBeforeEndSec", "releaseLaw"), strategy.params.map { it.id })
        val app = strategy.applicability(pair.features, pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(app.applicable, app.blockers.toString())
        assertTrue(app.score > 0.6, "ambient outro into ambient intro: ${app.score}")
        assertTrue(app.reasons.any { it.contains("harmonic share 0.90") && it.contains("0.0 onsets/s") }, app.reasons.toString())
        // A hard stop on a full drum bar is percussive → blocked
        val hard = SbFixtures.pair(SbFixtures.song(120.0, 0, Mode.MAJOR, outroBars = 0), SbFixtures.song(126.0, 9, Mode.MINOR))
        val blocked = strategy.applicability(hard.features, hard.a.analysis, hard.b.analysis, hard.prefs)
        assertTrue(!blocked.applicable && blocked.blockers.any { it.contains("percussive") }, blocked.blockers.toString())
        // tempo is irrelevant: 120 → 140 is fine
        val far = SbFixtures.pair(SbFixtures.song(120.0, 0, Mode.MAJOR), SbFixtures.song(140.0, 9, Mode.MINOR))
        assertTrue(strategy.applicability(far.features, far.a.analysis, far.b.analysis, far.prefs).applicable)
        // a beat outro scores low but is not blocked when bar features are missing
        val beaty = pair.a.analysis.copy(outro = dev.muisc.analysis.model.OutroType.BEAT_OUTRO, bars = dev.muisc.analysis.model.BarFeatures(), onsetFrames = LongArray(0))
        val f2 = DefaultPairAnalyzer().features(beaty, pair.b.analysis, pair.prefs)
        val low = strategy.applicability(f2, beaty, pair.b.analysis, pair.prefs)
        assertTrue(low.applicable && low.score < app.score - 0.2, "${low.score} vs ${app.score}")
    }

    @Test
    fun planGeometryFollowsBsTempo() {
        val plan = strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY, pair.prefs, 0L)
        val g = Splice.GUARD_FRAMES
        val fade = Math.round(0.2 * sr)
        val period = 60.0 * sr / 126.0
        assertEquals(pair.a.analysis.trimEndFrame, plan.aWindow.end, "A is frozen at its trimmed end")
        assertEquals(pair.a.analysis.trimEndFrame - g - fade, plan.aExitFrame)
        assertEquals(0, plan.aExitOffset)
        val bMixIn = pair.b.analysis.grid.beatFrames[16]
        assertEquals(bMixIn, plan.bWindow.start, "B's window starts at its mix-in beat")
        val tF = g + fade
        val tB = tF + Math.round(4 * period)
        val tEnd = maxOf(tF + Math.round(8 * period) + Math.round(8 * period), tB + Math.round(4 * period))
        assertEquals((tEnd + g).toInt(), plan.expectedOutputFrames)
        assertEquals(bMixIn + (tEnd - tB) + g, plan.bEntryFrame)
        assertEquals(plan.bEntryFrame, plan.bWindow.end)
        assertTrue(plan.lanes.map { it.id }.containsAll(listOf("gainA", "gainFreeze", "gainB", "freezeLpfHz")))
        assertTrue(plan.notes.first().contains("held 8 beats at 126.0 BPM"), plan.notes.toString())
        assertEquals(plan, strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY, pair.prefs, 0L))
        // the hold is timed in B's beats: doubling holdBeats adds exactly 8 B beats
        val longer = strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY.with("holdBeats", 16), pair.prefs, 0L)
        assertEquals(Math.round(8 * period).toInt(), longer.expectedOutputFrames - plan.expectedOutputFrames)
    }

    @Test
    fun renderHonoursContractAndIsDeterministic() {
        val (plan, input, r) = rendered
        SbFixtures.assertContract("spectralFreezeBridge", plan, input, r)
        val (_, _, r2) = SbFixtures.render(strategy, pair, Params.EMPTY, seed = 11L)
        SbFixtures.assertDeterministic("spectralFreezeBridge", r, r2)
        // a different seed changes the random phases but not the geometry
        val (_, _, r3) = SbFixtures.render(strategy, pair, Params.EMPTY, seed = 12L)
        assertEquals(r.audio.frames, r3.audio.frames)
        assertTrue(!r.audio[0].contentEquals(r3.audio[0]))
        assertEquals(listOf(SpectralFreezeBridgeStrategy.MARKER_FREEZE, SpectralFreezeBridgeStrategy.MARKER_B_ENTERS, SpectralFreezeBridgeStrategy.MARKER_RELEASE, SpectralFreezeBridgeStrategy.MARKER_FREEZE_GONE), r.markers.map { it.label })
        assertEquals((Splice.GUARD_FRAMES + Math.round(0.2 * sr)), r.markers[0].frame)
    }

    @Test
    fun frozenChordHasTheSourcesSpectrumAndLevel() {
        // no darkening so the hold can be compared with the source excerpt spectrally
        val (plan, input, r) = SbFixtures.render(strategy, pair, Params.EMPTY.with("darkenToHz", 20000.0).with("bEnterBeat", 8).with("freezeDb", -6.0), seed = 11L)
        val tF = r.markers.first { it.label == SpectralFreezeBridgeStrategy.MARKER_FREEZE }.frame.toInt()
        val tB = r.markers.first { it.label == SpectralFreezeBridgeStrategy.MARKER_B_ENTERS }.frame.toInt()
        val excerptStart = plan.aExitOffset + Splice.GUARD_FRAMES + Math.round(0.2 * sr).toInt() - Math.round(0.4 * sr).toInt()
        val excerpt = input.aAudio.slice(excerptStart, excerptStart + Math.round(0.4 * sr).toInt())
        val hold = r.audio.slice(tF + sr / 10, tB - sr / 10)     // freeze alone: after the fade, before B enters
        // level: the freeze reproduces the excerpt's power, scaled by freezeDb (−6 dB), within ±2.5 dB
        val levelDb = SbFixtures.db(hold.rms().toDouble()) - SbFixtures.db(excerpt.rms().toDouble())
        assertTrue(abs(levelDb + 6.0) < 2.5, "freeze level ${"%.1f".format(levelDb)} dB relative to the excerpt (expected −6)")
        // spectrum: the hold's third-octave LTAS is A's, not B's. (The absolute similarity sits around 0.84 rather
        // than 1: the random-phase resynthesis averages the excerpt's magnitudes over 186 ms frames, which smooths
        // the near-silent top and bottom bands the cosine is most sensitive to.)
        val holdLtas = ThirdOctaveBands.measureDb(hold)
        val sim = DefaultPairAnalyzer.ltasCosineSimilarity(holdLtas, ThirdOctaveBands.measureDb(excerpt))
        val simB = DefaultPairAnalyzer.ltasCosineSimilarity(holdLtas, ThirdOctaveBands.measureDb(input.bAudio.slice(0, 2 * sr)))
        assertTrue(sim > 0.8, "spectral similarity to A's last chord $sim")
        assertTrue(sim > simB + 0.1, "the freeze is A's spectrum ($sim), not B's ($simB)")
        // and it is stationary: the first and second halves of the hold are within 2 dB of each other
        val mid = (hold.frames / 2)
        val h1 = SbFixtures.db(hold.slice(0, mid).rms().toDouble()); val h2 = SbFixtures.db(hold.slice(mid, hold.frames).rms().toDouble())
        assertTrue(abs(h1 - h2) < 2.0, "hold halves $h1 / $h2 dB")
        // A's live signal is gone during the hold: the region has no A kicks / onsets, only the pad (no 6–12 kHz hats either)
        assertTrue(SbFixtures.bandRms(r.audio, tF + sr / 10, tB - sr / 10, 6000.0, 12000.0) < 1e-3)
    }

    @Test
    fun darkeningLaneLowPassesTheFreezeOverTime() {
        // darken to 300 Hz over the hold: the pad's upper partials (500–2000 Hz) must be far weaker, relative to the
        // band the filter passes, at the end of the hold than at its start. B is cued to enter only at the end of
        // the hold so the measured windows contain the freeze alone.
        val (_, _, r) = SbFixtures.render(strategy, pair, Params.EMPTY.with("darkenToHz", 300.0).with("bEnterBeat", 16).with("holdBeats", 16), seed = 11L)
        val tF = r.markers.first { it.label == SpectralFreezeBridgeStrategy.MARKER_FREEZE }.frame.toInt()
        val tB = r.markers.first { it.label == SpectralFreezeBridgeStrategy.MARKER_B_ENTERS }.frame.toInt()
        val win = sr
        // FFT band powers (no 4th-order-biquad leakage from the loud sub band into the measured band).
        val specEarly = SbFixtures.spectrum(r.audio, tF, tF + win)
        val specLate = SbFixtures.spectrum(r.audio, tB - win, tB)
        val early = SbFixtures.bandPower(specEarly, 1000.0, 4000.0) / SbFixtures.bandPower(specEarly, 60.0, 250.0)
        val late = SbFixtures.bandPower(specLate, 1000.0, 4000.0) / SbFixtures.bandPower(specLate, 60.0, 250.0)
        assertTrue(late < early * 0.05, "upper/lower power ratio early ${SbFixtures.db(Math.sqrt(early))} dB, late ${SbFixtures.db(Math.sqrt(late))} dB")
        val lpf = SbFixtures.lane(r, "freezeLpfHz")
        assertEquals(20000.0, lpf.points.first().value, 1e-6); assertEquals(300.0, lpf.points.last().value, 1e-6)
        assertTrue(lpf.points.zipWithNext().all { (p, q) -> q.value <= p.value + 1e-9 }, "cutoff never rises")
        // B enters exactly where the plan says and is at full level at the end of its fade
        val plan = r.plan
        val period = 60.0 * sr / 126.0
        assertEquals(tF + Math.round(16 * period), tB.toLong())
        assertTrue(SbFixtures.rms(r.audio, r.audio.frames - Splice.GUARD_FRAMES - sr, r.audio.frames - Splice.GUARD_FRAMES) > 0.02)
        assertTrue(plan.expectedOutputFrames == r.audio.frames)
    }
}
