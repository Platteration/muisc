package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.Mode
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.Params
import dev.muisc.transitions.StemNeed
import dev.muisc.transitions.TempoRelation
import dev.muisc.transitions.core.Splice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrumBreakBridgeStrategyTest {
    private val sr = SbFixtures.SR
    private val strategy = DrumBreakBridgeStrategy()
    private val pair by lazy { SbFixtures.pair(SbFixtures.song(120.0, 0, Mode.MAJOR), SbFixtures.song(126.0, 9, Mode.MINOR)) }
    private val shortParams = Params.EMPTY.with("breakBars", 2).with("soloBars", 2).with("drumCrossBars", 1).with("bFillBars", 2)
    private val rendered by lazy { SbFixtures.render(strategy, pair, shortParams, seed = 5L) }

    @Test
    fun applicabilityNeedsAPercussiveTail() {
        assertEquals("drumBreakBridge", strategy.id)
        assertEquals(listOf("breakBars", "soloBars", "drumCrossBars", "bFillBars", "hpfToHz", "hpfQ", "settleBars", "law", "alignToPhrase", "wsolaFrameMs", "wsolaToleranceMs"), strategy.params.map { it.id })
        val app = strategy.applicability(pair.features, pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(app.applicable, app.blockers.toString())
        assertTrue(app.score in 0.4..0.95, "score ${app.score}")
        assertTrue(app.reasons.any { it.contains("percussiveness A 0.70") }, app.reasons.toString())
        // a drum-less A tail (outro-like bars everywhere) is blocked
        val soft = pair.a.analysis.copy(bars = pair.a.analysis.bars.copy(percussiveness = FloatArray(pair.a.analysis.bars.barCount) { 0.1f }))
        val blocked = strategy.applicability(pair.features, soft, pair.b.analysis, pair.prefs)
        assertTrue(!blocked.applicable && blocked.blockers.any { it.contains("percussive") }, blocked.blockers.toString())
        // tempo too far apart
        val far = SbFixtures.pair(SbFixtures.song(120.0, 0, Mode.MAJOR), SbFixtures.song(140.0, 9, Mode.MINOR))
        assertTrue(strategy.applicability(far.features, far.a.analysis, far.b.analysis, far.prefs).blockers.any { it.contains("stretch") })
        // without bar features the gate is open but the score is lower
        val unknown = pair.a.analysis.copy(bars = dev.muisc.analysis.model.BarFeatures())
        val app2 = strategy.applicability(pair.features, unknown, pair.b.analysis, pair.prefs)
        assertTrue(app2.applicable && app2.score < app.score && app2.reasons.any { it.contains("unknown") })
    }

    @Test
    fun planPutsTheHandoverWhereADrumsEndAndBMixesIn() {
        val plan = strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, shortParams, pair.prefs, 0L)
        val g = Splice.GUARD_FRAMES
        val gridA = pair.a.analysis.grid
        // A's drums end at bar 28 (beat 112); 5 bars before that is beat 92, phrase-aligned back to 64
        assertEquals(64, plan.params["geom.aStartBeat"]!!.toInt())
        assertTrue(plan.notes.any { it.contains("phrase start 64") }, plan.notes.toString())
        val tight = strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, shortParams.with("alignToPhrase", false), pair.prefs, 0L)
        assertEquals(92, tight.params["geom.aStartBeat"]!!.toInt())
        assertEquals(gridA.beatFrames[92] - g, tight.aExitFrame)
        assertEquals(8, plan.params["geom.breakBeats"]!!.toInt())
        assertEquals(16, plan.params["geom.handoverBeat"]!!.toInt())
        assertEquals(20, plan.params["geom.handoverEndBeat"]!!.toInt())
        assertEquals(28, plan.params["geom.bodyBeats"]!!.toInt())
        assertEquals(16, plan.params["geom.bStartBeat"]!!.toInt(), "B's mixInBeat lands on the handover")
        assertEquals(StemNeed.BOTH, plan.stemNeed)
        assertEquals(pair.b.analysis.grid.beatFrames[16 + 12] + g, plan.bEntryFrame)
        assertTrue(plan.lanes.map { it.id }.containsAll(listOf("gainA.drums", "gainA.rest", "gainB.drums", "gainB.rest", "hpfA.drumsHz")))
        assertEquals(plan, strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, shortParams, pair.prefs, 0L))
    }

    @Test
    fun renderHonoursContractAndIsDeterministic() {
        val (plan, input, r) = rendered
        SbFixtures.assertContract("drumBreakBridge", plan, input, r)
        val (_, _, r2) = SbFixtures.render(strategy, pair, shortParams, seed = 5L)
        SbFixtures.assertDeterministic("drumBreakBridge", r, r2)
        assertEquals(listOf(DrumBreakBridgeStrategy.MARKER_BREAK, DrumBreakBridgeStrategy.MARKER_SOLO, DrumBreakBridgeStrategy.MARKER_B_DRUMS, DrumBreakBridgeStrategy.MARKER_A_GONE, DrumBreakBridgeStrategy.MARKER_B_FULL), r.markers.map { it.label })
        assertEquals(29, SbFixtures.lane(r, "masterBeat").points.size)
        assertEquals(28, r.report.ratioTrace.size)
    }

    @Test
    fun aMelodicContentLeavesDuringTheSoloAndBsArrivesAfterTheHandover() {
        val (plan, _, r) = rendered
        val beats = SbFixtures.lane(r, "masterBeat")
        fun bar(bar: Int) = Math.round(beats.points[bar * 4].outputSec * sr).toInt() to Math.round(beats.points[bar * 4 + 4].outputSec * sr).toInt()
        val aStartBar = pair.a.analysis.grid.barOfBeat(plan.params["geom.aStartBeat"]!!.toInt())
        // A's pad root (octave 4) is a spectral line in bar 0 and must be at least 10 dB down in the solo bars 2 and 3
        for (bar in 2..3) {
            val (s0, e0) = bar(0); val (s, e) = bar(bar)
            val padHz = SbFixtures.hz(SbFixtures.bassRootMidi(pair.a.song, aStartBar + bar) + 24)
            val padHz0 = SbFixtures.hz(SbFixtures.bassRootMidi(pair.a.song, aStartBar) + 24)
            val full = SbFixtures.peakNear(SbFixtures.spectrum(r.audio, s0, e0), padHz0)
            val solo = SbFixtures.peakNear(SbFixtures.spectrum(r.audio, s, e), padHz)
            assertTrue(solo < 0.1 * full, "bar $bar: pad line ${SbFixtures.db(Math.sqrt(solo))} dB vs full ${SbFixtures.db(Math.sqrt(full))} dB")
        }
        // drums are still there in the solo: the hat band keeps its level within 6 dB of bar 0
        val (s0, e0) = bar(0); val (s3, e3) = bar(3)
        val hats0 = SbFixtures.bandRms(r.audio, s0, e0, 6000.0, 12000.0); val hats3 = SbFixtures.bandRms(r.audio, s3, e3, 6000.0, 12000.0)
        assertTrue(hats3 > hats0 * 0.5, "hats in the solo ${SbFixtures.db(hats3)} dB vs ${SbFixtures.db(hats0)} dB")
        // B's pad root appears once B's rest has faded in (bar 6 = end of the fill) but not in the handover bar 4
        val bStartBar = pair.b.analysis.grid.barOfBeat(plan.params["geom.bStartBeat"]!!.toInt())
        val (s4, e4) = bar(4); val (s6, e6) = bar(6)
        val bPad4 = SbFixtures.peakNear(SbFixtures.spectrum(r.audio, s4, e4), SbFixtures.hz(SbFixtures.bassRootMidi(pair.b.song, bStartBar) + 24))
        val bPad6 = SbFixtures.peakNear(SbFixtures.spectrum(r.audio, s6, e6), SbFixtures.hz(SbFixtures.bassRootMidi(pair.b.song, bStartBar + 2) + 24))
        assertTrue(bPad6 > 4.0 * bPad4, "B's pad: handover bar ${SbFixtures.db(Math.sqrt(bPad4))} dB, filled-in bar ${SbFixtures.db(Math.sqrt(bPad6))} dB")
    }

    @Test
    fun hpfRampThinsTheDrumSoloAndBKicksLockToTheGrid() {
        val (plan, _, r) = rendered
        val beats = SbFixtures.lane(r, "masterBeat")
        val soloEndBeat = plan.params["geom.handoverBeat"]!!.toInt()
        val lastSoloBeat = Math.round(beats.points[soloEndBeat - 1].outputSec * sr).toInt()
        val soloEnd = Math.round(beats.points[soloEndBeat].outputSec * sr).toInt()
        val (_, _, noHpf) = SbFixtures.render(strategy, pair, shortParams.with("hpfToHz", 20.0), seed = 5L)
        val lowWith = SbFixtures.bandRms(r.audio, lastSoloBeat, soloEnd, 0.0, 120.0)
        val lowWithout = SbFixtures.bandRms(noHpf.audio, lastSoloBeat, soloEnd, 0.0, 120.0)
        assertTrue(lowWith < lowWithout * 0.5, "HPF 400 Hz at the end of the solo: ${SbFixtures.db(lowWith)} dB vs ${SbFixtures.db(lowWithout)} dB without")
        val hpf = SbFixtures.lane(r, "hpfA.drumsHz")
        assertEquals(20.0, hpf.points.first().value, 1e-9); assertEquals(400.0, hpf.points.last().value, 1e-6)
        // B's kicks after the handover land on the master beats (settle bar excluded)
        val handoverEnd = plan.params["geom.handoverEndBeat"]!!.toInt()
        val settle = plan.params["geom.settleBeats"]!!.toInt()
        val offsets = SbFixtures.kickOffsetsMs(r, handoverEnd, 28 - settle, pair.b.audio, pair.b.analysis.grid.beatFrames.copyOfRange(16, 48))
        SbFixtures.assertMostWithin("B kicks vs masterBeat", offsets, 12.0)
    }

    @Test
    fun halfTimePairLocksBsBeatsOnEverySecondMasterBeat() {
        // 128 BPM into a 64 BPM song: B is read in double time (ratio 1.0, no stretch)
        val pair2 = SbFixtures.pair(SbFixtures.song(128.0, 0, Mode.MAJOR, bars = 24), SbFixtures.song(64.0, 9, Mode.MINOR, bars = 16, introBars = 2, outroBars = 2))
        assertEquals(TempoRelation.DOUBLE, pair2.features.tempoRelation)
        assertEquals(1.0, pair2.features.tempoRatio, 1e-9)
        val app = strategy.applicability(pair2.features, pair2.a.analysis, pair2.b.analysis, pair2.prefs)
        assertTrue(app.applicable && app.reasons.any { it.contains("double-time") }, "${app.reasons} ${app.blockers}")
        val (plan, input, r) = SbFixtures.render(strategy, pair2, shortParams, seed = 9L)
        SbFixtures.assertContract("drumBreakBridge half-time", plan, input, r)
        assertEquals(16, plan.params["geom.bStartBeat"]!!.toInt(), "B's mixInBeat 8 on the double-time grid")
        assertTrue(plan.notes.any { it.contains("double-time grid") }, plan.notes.toString())
        // B's kicks fall on its own beats = every second master beat from the handover
        val handover = plan.params["geom.handoverBeat"]!!.toInt()
        val lane = SbFixtures.lane(r, "masterBeat")
        val kickBeats = (handover + 4 until 28 step 2).toList()
        val expected = LongArray(kickBeats.size) { Math.round(lane.points[kickBeats[it]].outputSec * sr) }
        val bias = dev.muisc.transitions.core.SongFixtures.median(dev.muisc.transitions.core.SongFixtures.kickOffsets(pair2.b.audio, pair2.b.analysis.grid.beatFrames.copyOfRange(8, 24)))
        val offsets = dev.muisc.transitions.core.SongFixtures.kickOffsets(r.audio, expected).map { (it - bias) / sr * 1000.0 }.toDoubleArray()
        SbFixtures.assertMostWithin("half-time B kicks", offsets, 12.0)
        // the master grid runs at A's tempo throughout (no stretch → no settle)
        assertTrue(SbFixtures.lane(r, "masterBpm").points.all { Math.abs(it.value - 128.0) < 0.01 })
    }
}
