package dev.muisc.transitions.modifiers

import dev.muisc.analysis.features.SpectrumAnalyzer
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.strategies.AmbientBridgeStrategy
import dev.muisc.transitions.strategies.CrossfadeStrategy
import dev.muisc.transitions.strategies.SbFixtures
import dev.muisc.transitions.synthetic.SyntheticTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextureCarryModifierTest {
    private val sr = SbFixtures.SR
    private val base = CrossfadeStrategy()
    private val modifier = TextureCarryModifier()

    /**
     * 120 BPM C major into 126 BPM A minor. The crossfade is configured to run A's ambient outro into B's ambient
     * intro (no drums on either side), so 6–10 kHz is a band where both songs are quiet and the bed is measurable.
     */
    private val raw by lazy { SbFixtures.pair(SbFixtures.song(120.0, 0, Mode.MAJOR), SbFixtures.song(126.0, 9, Mode.MINOR)) }

    /** The same A, but with the analysis' texture spectrum filled in: a flat (broadband "tape hiss") template. */
    private val pair by lazy { withTexture(FloatArray(SpectrumAnalyzer.TEXTURE_BINS) { 1f }) }

    /** A clean outro: a texture spectrum far below the modifier's gate. */
    private val clean by lazy { withTexture(FloatArray(SpectrumAnalyzer.TEXTURE_BINS) { 1e-5f }) }

    private fun withTexture(magnitude: FloatArray): SbFixtures.Pair {
        val p = raw
        val analysis = p.a.analysis.copy(textureMagnitude = magnitude)
        val track = SyntheticTrack(p.a.song, p.a.trackRef.copy(analysis = analysis), p.a.audio)
        return SbFixtures.Pair(track, p.b, p.prefs, p.features, p.loader)
    }

    /** Ambient outro → ambient intro: no beat alignment, B from its trim start. */
    private val fade = Params.EMPTY.with("fadeSec", 6.0).with("alignToBeat", false).with("bStartAtMixIn", false)
    private val bedParams = Params.EMPTY.with("textureDb", -12.0).with("riseBars", 1).with("morphToB", false)

    /** Base render and modified render of the same plan, so the two differ by the bed alone. */
    private class Run(val plan: TransitionPlan, val input: TransitionInput, val dry: RenderedTransition, val wet: RenderedTransition)

    private fun run(p: SbFixtures.Pair = pair, mod: Params = bedParams, seed: Long = 5L): Run {
        val plan0 = base.plan(p.a.analysis, p.b.analysis, p.features, fade, p.prefs, seed)
        val plan = modifier.adjustPlan(plan0, p.a.analysis, p.b.analysis, p.features, mod, p.prefs)
        val input = SbFixtures.input(plan, p)
        val ctx = RenderContext(p.prefs, seed)
        val dry = base.render(input, ctx)
        return Run(plan, input, dry, modifier.apply(dry, input, mod, ctx))
    }

    @Test
    fun applicabilityRisesWithTextureAndWithSpectralDistance() {
        assertEquals("textureCarry", modifier.id)
        assertEquals(listOf("mode", "textureDb", "riseBars", "releaseCurve", "morphToB", "morphMaxDb", "darkenHz", "minReleaseBars"), modifier.params.map { it.id })
        val f = pair.features
        val textured = modifier.applicability(f, pair.a.analysis, pair.b.analysis, base, pair.prefs)
        assertTrue(textured > 0.3, "a hissy outro wants its texture carried over: $textured")
        // a clean outro is gated out entirely: the bed would just be added noise
        assertEquals(0.0, modifier.applicability(f, clean.a.analysis, clean.b.analysis, base, clean.prefs), 1e-9)
        // the less the two spectra have in common, the more the bed is worth (it glues them)
        val similar = modifier.applicability(f.copy(spectralSimilarity = 0.95), pair.a.analysis, pair.b.analysis, base, pair.prefs)
        val distant = modifier.applicability(f.copy(spectralSimilarity = 0.3), pair.a.analysis, pair.b.analysis, base, pair.prefs)
        assertTrue(distant > similar + 0.2, "spectral distance raises it: $distant vs $similar")
        // ambientBridge already generates its own bed
        assertEquals(0.0, modifier.applicability(f, pair.a.analysis, pair.b.analysis, AmbientBridgeStrategy(), pair.prefs), 1e-9)
    }

    @Test
    fun adjustPlanRecordsTheModifierWithoutMovingTheGeometry() {
        val plan0 = base.plan(pair.a.analysis, pair.b.analysis, pair.features, fade, pair.prefs, 5L)
        val plan = modifier.adjustPlan(plan0, pair.a.analysis, pair.b.analysis, pair.features, bedParams, pair.prefs)
        assertEquals(listOf("textureCarry"), plan.modifiers)
        assertEquals(plan0.aWindow, plan.aWindow); assertEquals(plan0.bWindow, plan.bWindow)
        assertEquals(plan0.expectedOutputFrames, plan.expectedOutputFrames)
        assertEquals(plan0.aExitFrame, plan.aExitFrame); assertEquals(plan0.bEntryFrame, plan.bEntryFrame)
        assertTrue(plan.notes.any { it.contains("NOISE bed at -12 dB") }, plan.notes.toString())
        // applying twice does not stack the id
        assertEquals(listOf("textureCarry"), modifier.adjustPlan(plan, pair.a.analysis, pair.b.analysis, pair.features, bedParams, pair.prefs).modifiers)
    }

    @Test
    fun renderHonoursTheSpliceContractAndIsDeterministic() {
        val r = run()
        SbFixtures.assertContract("crossfade + textureCarry", r.plan, r.input, r.wet)
        SbFixtures.assertDeterministic("crossfade + textureCarry", r.wet, run().wet)
        // a different seed draws different noise but keeps the geometry
        val other = run(seed = 6L).wet
        assertEquals(r.wet.audio.frames, other.audio.frames)
        assertTrue(!r.wet.audio[0].contentEquals(other.audio[0]), "the bed is seeded from the render seed")
        assertTrue(r.wet.markers.map { it.label }.containsAll(listOf(TextureCarryModifier.MARKER_IN, TextureCarryModifier.MARKER_HOLD, TextureCarryModifier.MARKER_OUT)))
        assertTrue(r.wet.report.metrics["textureBedGain"]!! > 0.0)
    }

    @Test
    fun theBedIsPresentInTheMiddleAndGoneInBothGuardRegions() {
        val r = run()
        val g = Splice.GUARD_FRAMES
        val frames = r.wet.audio.frames
        // The bed is exactly what the modifier added: everything else is the same render.
        val added = SbFixtures.difference(r.wet.audio, r.dry.audio)
        assertEquals(0.0, SbFixtures.rms(added, 0, g), 1e-7, "the dry A pre-roll is untouched")
        assertEquals(0.0, SbFixtures.rms(added, frames - g, frames), 1e-7, "the dry B post-roll is untouched")
        assertTrue(SbFixtures.rms(added, frames / 2 - sr / 4, frames / 2 + sr / 4) > 1e-3, "the bed is there in the middle")
        // 6–10 kHz is a band where an ambient outro and an ambient intro are both quiet: the bed shows up there.
        val mid0 = frames / 2 - sr / 4; val mid1 = frames / 2 + sr / 4
        val hfWet = SbFixtures.bandRms(r.wet.audio, mid0, mid1, 6000.0, 10000.0)
        val hfDry = SbFixtures.bandRms(r.dry.audio, mid0, mid1, 6000.0, 10000.0)
        assertTrue(hfWet > 10.0 * hfDry, "6–10 kHz in the middle: ${SbFixtures.db(hfWet)} dB with the bed, ${SbFixtures.db(hfDry)} dB without")
        val hfEnd = SbFixtures.bandRms(r.wet.audio, frames - g, frames, 6000.0, 10000.0)
        val hfEndDry = SbFixtures.bandRms(r.dry.audio, frames - g, frames, 6000.0, 10000.0)
        assertEquals(hfEndDry, hfEnd, 1e-9, "6–10 kHz in the last ${g} frames is B's own, the bed is gone")
    }

    @Test
    fun theEnvelopeRisesHoldsAndReleasesToZeroBeforeThePostRoll() {
        val r = run()
        val g = Splice.GUARD_FRAMES.toDouble() / sr
        val frames = r.wet.audio.frames
        val lane = r.wet.plan.lanes.first { it.id == TextureCarryModifier.LANE_BED }
        assertEquals(0.0, lane.points.first().value, 1e-9, "silent at the start of the segment")
        assertEquals(0.0, lane.points.last().value, 1e-9, "silent at the end")
        assertEquals(0.0, lane.points.first { it.outputSec >= g - 1e-9 }.value, 1e-9, "still silent at the end of the dry pre-roll")
        assertTrue(lane.points.any { it.value >= 0.999 }, "the bed reaches full level")
        val peakAt = lane.points.indexOfFirst { it.value >= 0.999 }
        assertTrue(lane.points.drop(peakAt).zipWithNext().all { (p, q) -> q.value <= p.value + 1e-6 }, "never rises again once it has peaked")
        assertTrue(lane.points.last { it.value > 1e-6 }.outputSec < (frames - Splice.GUARD_FRAMES) / sr.toDouble() + 1e-9, "gone before the post-roll")
        // rise length follows riseBars (bars of A's tempo)
        val slow = run(mod = bedParams.with("riseBars", 4)).wet.plan.lanes.first { it.id == TextureCarryModifier.LANE_BED }
        assertTrue(slow.points.first { it.value >= 0.999 }.outputSec > lane.points[peakAt].outputSec, "4 bars rise later than 1")
    }

    @Test
    fun everyBedModeHonoursTheContract() {
        for (mode in listOf("FREEZE", "OTHER")) {
            val r = run(mod = bedParams.with("mode", mode))
            SbFixtures.assertContract("crossfade + textureCarry ($mode)", r.plan, r.input, r.wet)
            val added = SbFixtures.difference(r.wet.audio, r.dry.audio)
            val frames = r.wet.audio.frames
            assertEquals(0.0, SbFixtures.rms(added, 0, Splice.GUARD_FRAMES), 1e-7, "$mode: pre-roll untouched")
            assertEquals(0.0, SbFixtures.rms(added, frames - Splice.GUARD_FRAMES, frames), 1e-7, "$mode: post-roll untouched")
            assertTrue(SbFixtures.rms(added, frames / 2 - sr / 4, frames / 2 + sr / 4) > 1e-4, "$mode: bed audible in the middle")
        }
        // the measured fallback: no textureMagnitude in the analysis at all
        val noTexture: TrackAnalysis = raw.a.analysis
        assertEquals(0, noTexture.textureMagnitude.size, "the synthetic ground truth carries no texture spectrum")
        val measured = run(p = raw)
        SbFixtures.assertContract("crossfade + textureCarry (measured texture)", measured.plan, measured.input, measured.wet)
        assertTrue(measured.plan.notes.any { it.contains("measured from the decoded A window") }, measured.plan.notes.toString())
        val added = SbFixtures.difference(measured.wet.audio, measured.dry.audio)
        assertTrue(SbFixtures.rms(added, added.frames / 2 - sr / 4, added.frames / 2 + sr / 4) > 1e-4, "a bed is synthesised from the window")
    }
}
