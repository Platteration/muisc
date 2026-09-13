package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.Mode
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.Params
import dev.muisc.transitions.StemNeed
import dev.muisc.transitions.core.Splice
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StemSwapStrategyTest {
    private val sr = SbFixtures.SR
    private val strategy = StemSwapStrategy()
    /** 120 BPM C major → 126 BPM D minor (5 % stretch, Camelot 8B → 7A): D minor's bass roots differ from C major's bar by bar. */
    private val pair by lazy { SbFixtures.pair(SbFixtures.song(120.0, 0, Mode.MAJOR), SbFixtures.song(126.0, 2, Mode.MINOR)) }
    private val shortParams = Params.EMPTY.with("overlapBars", 8).with("drumsSwapBar", 2).with("bassSwapBar", 4).with("vocalCrossBars", 3)
    private val rendered by lazy { SbFixtures.render(strategy, pair, shortParams, seed = 3L) }

    @Test
    fun applicabilityGatesOnTempoGridAndRoom() {
        assertEquals("stemSwap", strategy.id)
        assertEquals(listOf("overlapBars", "drumsSwapBar", "bassSwapBar", "vocalCrossBars", "swapBeats", "duckDb", "duckThreshold", "settleBars", "law", "cueBAtDrumSwap", "wsolaFrameMs", "wsolaToleranceMs"), strategy.params.map { it.id })
        val app = strategy.applicability(pair.features, pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(app.applicable, "blockers: ${app.blockers}")
        assertTrue(app.score in 0.4..0.95, "score ${app.score}")
        assertTrue(app.reasons.any { it.contains("5.0 % stretch") }, app.reasons.toString())
        assertTrue(app.reasons.any { it.contains("8B→7A") }, app.reasons.toString())
        assertTrue(app.reasons.any { it.contains("pseudo-stems") })
        // 120 → 140 BPM: 16.7 % stretch is beyond the 8 % limit.
        val far = SbFixtures.pair(SbFixtures.song(120.0, 0, Mode.MAJOR), SbFixtures.song(140.0, 9, Mode.MINOR))
        val blocked = strategy.applicability(far.features, far.a.analysis, far.b.analysis, far.prefs)
        assertTrue(!blocked.applicable && blocked.blockers.any { it.contains("16.7 % stretch") }, blocked.blockers.toString())
        // a shaky grid on A blocks it too
        val shaky = pair.a.analysis.copy(grid = pair.a.analysis.grid.copy(confidence = 0.3f))
        val f2 = DefaultPairAnalyzer().features(shaky, pair.b.analysis, pair.prefs)
        val blocked2 = strategy.applicability(f2, shaky, pair.b.analysis, pair.prefs)
        assertTrue(blocked2.blockers.any { it.contains("beat-matchable") }, blocked2.blockers.toString())
        // no room: B's mix-in cue on its last bar
        val late = pair.b.analysis.copy(cues = pair.b.analysis.cues.copy(mixInBeat = 124))
        val f3 = DefaultPairAnalyzer().features(pair.a.analysis, late, pair.prefs)
        assertTrue(strategy.applicability(f3, pair.a.analysis, late, pair.prefs).blockers.any { it.contains("room") })
    }

    @Test
    fun planIsPhraseAlignedAndClampedToRoom() {
        val plan = strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY, pair.prefs, 0L)
        val g = Splice.GUARD_FRAMES
        val gridA = pair.a.analysis.grid
        // A: the overlap starts on the phrase start at or before the mix-out cue (beat 80 → phrase start 64)
        assertEquals(64, plan.params["geom.aStartBeat"]!!.toInt())
        assertEquals(gridA.beatFrames[64] - g, plan.aExitFrame)
        assertEquals(plan.aExitFrame - StemSwapStrategy.LEAD_FRAMES, plan.aWindow.start)
        // default 24 bars shortened to A's 16 bars of room
        assertEquals(64, plan.params["geom.bodyBeats"]!!.toInt())
        assertTrue(plan.notes.any { it.contains("shortened from 24 to 16 bars") }, plan.notes.toString())
        // B's mix-in (beat 16) lands on the drum-swap bar (bar 4 = master beat 16)
        assertEquals(16, plan.params["geom.bStartBeat"]!!.toInt())
        assertEquals(16, plan.params["geom.bEnterBeat"]!!.toInt())
        assertEquals(48, plan.params["geom.bassSwapBeat"]!!.toInt())
        assertEquals(32, plan.params["geom.crossStartBeat"]!!.toInt())
        assertEquals(4, plan.params["geom.settleBeats"]!!.toInt())
        assertEquals(StemNeed.BOTH, plan.stemNeed)
        // B's post-roll ends G frames after B beat 16 + 48
        assertEquals(pair.b.analysis.grid.beatFrames[64] + g, plan.bEntryFrame)
        assertTrue(plan.bWindow.end >= plan.bEntryFrame && plan.aExitFrame in plan.aWindow)
        val master = StemSwapStrategy.ownMaster(sr, gridA, 64, 64, 4, 120.0 * pair.features.tempoRatio, 4)
        assertEquals((2 * g + master.totalFrames).toInt(), plan.expectedOutputFrames)
        // the settle: the last beat has exactly B's matched period, the first beats A's
        assertEquals(126.0, master.bpmEnd, 1e-9)
        assertEquals(120.0, master.bpmStart, 1e-6)
        assertEquals(7, plan.lanes.size)
        assertTrue(plan.lanes.any { it.id == "gainA.drums" } && plan.lanes.any { it.id == "duckA.vocals" })
        // same inputs → same plan
        assertEquals(plan, strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY, pair.prefs, 0L))
    }

    @Test
    fun renderHonoursContractAndIsDeterministic() {
        val (plan, input, r) = rendered
        SbFixtures.assertContract("stemSwap", plan, input, r)
        val (_, _, r2) = SbFixtures.render(strategy, pair, shortParams, seed = 3L)
        SbFixtures.assertDeterministic("stemSwap", r, r2)
        assertEquals(listOf(StemSwapStrategy.MARKER_OVERLAP, StemSwapStrategy.MARKER_DRUMS, StemSwapStrategy.MARKER_BASS, StemSwapStrategy.MARKER_REST, StemSwapStrategy.MARKER_B_FULL), r.markers.map { it.label })
        val beats = SbFixtures.lane(r, "masterBeat")
        assertEquals(33, beats.points.size, "one point per master beat plus the end")
        assertEquals(Splice.GUARD_FRAMES / sr.toDouble(), beats.points[0].outputSec, 1e-9)
        assertEquals(32, r.report.ratioTrace.size)
        assertTrue(SbFixtures.lane(r, "masterBpm").points.last().value > 125.0, "settle reaches B's tempo")
    }

    @Test
    fun bKicksLandOnTheMasterBeatsAfterTheDrumSwap() {
        val (plan, _, r) = rendered
        val drums = plan.params["geom.drumsSwapBeat"]!!.toInt() + plan.params["geom.swapBeats"]!!.toInt()
        val settle = plan.params["geom.settleBeats"]!!.toInt()
        val bGrid = pair.b.analysis.grid
        val offsets = SbFixtures.kickOffsetsMs(r, drums, 32 - settle, pair.b.audio, bGrid.beatFrames.copyOfRange(16, 48))
        SbFixtures.assertMostWithin("B kicks vs masterBeat lane", offsets, 12.0)
    }

    @Test
    fun bassLineSwitchesFromAToBAtTheBassSwap() {
        val (plan, _, r) = rendered
        val g = Splice.GUARD_FRAMES
        // Control render: everything identical except that the bass swap is moved past the measured bars, so A keeps
        // the low end throughout. Comparing the SAME bar between the two renders isolates the bass handover from the
        // two songs' own harmonic content (both are tonal, so "A's root is loud in the mix" alone proves nothing).
        val (planLate, _, rLate) = SbFixtures.render(strategy, pair, shortParams.with("bassSwapBar", 7), seed = 3L)
        assertEquals(r.audio.frames, rLate.audio.frames, "only the swap position differs")
        assertEquals(plan.params["geom.bodyBeats"], planLate.params["geom.bodyBeats"])
        val beats = SbFixtures.lane(r, "masterBeat")
        fun barRange(bar: Int) = Math.round(beats.points[bar * 4].outputSec * sr).toInt() to Math.round(beats.points[bar * 4 + 4].outputSec * sr).toInt()
        val aStartBar = pair.a.analysis.grid.barOfBeat(plan.params["geom.aStartBeat"]!!.toInt())
        val bStartBar = pair.b.analysis.grid.barOfBeat(plan.params["geom.bStartBeat"]!!.toInt())
        val bEnterBar = plan.params["geom.bEnterBeat"]!!.toInt() / 4
        // Everything but the bass stem is identical between the two renders, so their difference IS the bass
        // handover: (B's bass − A's bass). That is the only way to see the bass lines at all — the synthetic kick
        // sweeps 155 → 45 Hz and buries both bass fundamentals and their low harmonics in the mix itself.
        val diff = SbFixtures.difference(r.audio, rLate.audio)
        // 1. Before the earlier swap the two renders are the same signal.
        val (s0, e3) = barRange(0).first to barRange(3).second
        assertTrue(SbFixtures.rms(diff, s0, e3) < 0.01 * SbFixtures.rms(r.audio, s0, e3), "nothing changes before the bass swap (${SbFixtures.rms(diff, s0, e3)})")
        // 2. Between the two swap positions the bass really changed hands, and only in the low band.
        val (s5, e6) = barRange(5).first to barRange(6).second
        val handover = SbFixtures.rms(diff, s5, e6)
        assertTrue(handover > 0.1 * SbFixtures.rms(r.audio, s5, e6), "bass handover is audible (${SbFixtures.db(handover)} dB vs mix ${SbFixtures.db(SbFixtures.rms(r.audio, s5, e6))} dB)")
        val specDiff = SbFixtures.spectrum(diff, s5, e6)
        val lowPower = SbFixtures.bandPower(specDiff, 20.0, 250.0)
        val highPower = SbFixtures.bandPower(specDiff, 250.0, 8000.0)
        assertTrue(lowPower > 10.0 * highPower, "the swap moved the LOW band only (low ${SbFixtures.db(Math.sqrt(lowPower))} dB vs high ${SbFixtures.db(Math.sqrt(highPower))} dB)")
        // 3. What left and what arrived are the two songs' own bass roots (fundamental or 2nd harmonic).
        val aRoots = (5..6).map { SbFixtures.hz(SbFixtures.bassRootMidi(pair.a.song, aStartBar + it)) }
        val bRoots = (5..6).map { SbFixtures.hz(SbFixtures.bassRootMidi(pair.b.song, bStartBar + it - bEnterBar)) }
        assertTrue(aRoots.toSet().intersect(bRoots.toSet()).isEmpty(), "the two songs play different bass notes here ($aRoots vs $bRoots)")
        val dominant = SbFixtures.dominantHz(specDiff, 25.0, 250.0)
        val expected = (aRoots + bRoots).flatMap { listOf(it, 2 * it) }
        assertTrue(expected.any { abs(dominant - it) < 0.04 * it }, "difference peaks at $dominant Hz, expected one of $expected")
        // Low band overall stays: the swap hands the low end over, it does not empty it.
        val lowSwapped = SbFixtures.bandRms(r.audio, s5, e6, 0.0, 200.0)
        val lowControl = SbFixtures.bandRms(rLate.audio, s5, e6, 0.0, 200.0)
        assertTrue(lowSwapped > 0.5 * lowControl, "low band after the swap ${SbFixtures.db(lowSwapped)} dB vs ${SbFixtures.db(lowControl)} dB")
        // A's drums are gone after the drum swap: the hat band (6–12 kHz) between the swaps is B's only, and it is still there
        val (s1, e1) = barRange(3)
        assertTrue(SbFixtures.bandRms(r.audio, s1, e1, 6000.0, 12000.0) > 1e-3, "B's hats present after the drum swap")
        assertTrue(r.audio.slice(g + 100, g + 200).rms() > 0.01f)
    }

    @Test
    fun vocalDuckFollowsBsVocalActivity() {
        val singing = pair.b.analysis.copy(bars = pair.b.analysis.bars.copy(vocalActivity = FloatArray(pair.b.analysis.bars.barCount) { 1f }))
        val plan = strategy.plan(pair.a.analysis, singing, pair.features, shortParams, pair.prefs, 0L)
        val duck = plan.lanes.first { it.id == StemSwapStrategy.LANE_DUCK }
        assertEquals(1.0, duck.points.first().value, 1e-9, "the pre-roll is never ducked")
        val ducked = duck.points.filter { it.value < 0.99 }
        assertTrue(ducked.isNotEmpty() && ducked.all { abs(it.value - 0.3548) < 1e-3 }, "−9 dB duck points: ${duck.points}")
        val silent = strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, shortParams, pair.prefs, 0L)
        assertTrue(silent.lanes.first { it.id == StemSwapStrategy.LANE_DUCK }.points.all { it.value == 1.0 }, "no vocals in B → no duck")
    }
}
