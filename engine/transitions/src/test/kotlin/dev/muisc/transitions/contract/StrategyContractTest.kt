package dev.muisc.transitions.contract

import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.dsp.stems.PseudoStemSeparator
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.LazyStemProvider
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.core.SpliceCheck
import dev.muisc.transitions.synthetic.SyntheticTrack
import dev.muisc.transitions.synthetic.SyntheticTrackLoader
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strategy contract (DESIGN.md §9), run for every strategy in [DefaultStrategyRegistry.default] over a pair
 * matrix of four 16-bar synthetic tracks (12 ordered pairs). A strategy merged into the registry is covered
 * automatically. For every pair the strategy declares itself applicable to, with default params:
 * the plan is valid, the render's length is within ±1 % of `expectedOutputFrames`, [SpliceCheck] is clean, the
 * [ArtifactDetector] finds no click, the peak is ≤ 0 dBFS, no sample is NaN/Inf, and rendering twice is
 * bit-identical.
 *
 * `-Dmuisc.contract.pairs=<n>` limits the matrix to its first n pairs; `-Dmuisc.contract.pairs=t120C>t126Am,…`
 * selects pairs by name; `-Dmuisc.contract.strategies=crossfade,bassSwap` limits the strategies.
 */
class StrategyContractTest {
    private val registry = DefaultStrategyRegistry.default()
    private val loader = SyntheticTrackLoader()
    private val pairAnalyzer = DefaultPairAnalyzer()

    private val songs: Map<String, SyntheticSong> = linkedMapOf(
        "t120C" to SyntheticSong(bpm = 120.0, tonic = 0, mode = Mode.MAJOR, bars = 16, introBars = 4, outroBars = 4),
        "t126Am" to SyntheticSong(bpm = 126.0, tonic = 9, mode = Mode.MINOR, bars = 16, introBars = 4, outroBars = 4),
        "t63G" to SyntheticSong(bpm = 63.0, tonic = 7, mode = Mode.MAJOR, bars = 16, introBars = 4, outroBars = 4, outroFade = true),
        "t140Fs" to SyntheticSong(bpm = 140.0, tonic = 6, mode = Mode.MAJOR, bars = 16, introBars = 0, outroBars = 4),
    )

    private val tracks: Map<String, SyntheticTrack> by lazy { songs.mapValues { (id, song) -> loader.register(song, id) } }

    /** Both decks attenuated at least 3 dB under the quietest track, so dry material sits below 0 dBFS. */
    private val prefs: TransitionPrefs by lazy {
        TransitionPrefs(targetLufs = tracks.values.minOf { it.analysis.loudness.integratedLufs }.toDouble() - 3.0)
    }

    private fun selectedPairs(): List<Pair<SyntheticTrack, SyntheticTrack>> {
        val all = ArrayList<Pair<SyntheticTrack, SyntheticTrack>>()
        for (a in tracks.values) for (b in tracks.values) if (a !== b) all += a to b
        val prop = System.getProperty("muisc.contract.pairs")?.trim().orEmpty()
        if (prop.isEmpty()) return all
        prop.toIntOrNull()?.let { return all.take(it) }
        val names = prop.split(',').map { it.trim() }.toSet()
        return all.filter { (a, b) -> "${a.id}>${b.id}" in names }
    }

    private fun selectedStrategies(): List<TransitionStrategy> {
        val prop = System.getProperty("muisc.contract.strategies")?.trim().orEmpty()
        if (prop.isEmpty()) return registry.strategies
        val ids = prop.split(',').map { it.trim() }.toSet()
        return registry.strategies.filter { it.id in ids }
    }

    @TestFactory
    fun contract(): List<DynamicNode> {
        val pairs = selectedPairs()
        val separator = PseudoStemSeparator()
        return selectedStrategies().map { strategy ->
            val tests = pairs.map { (a, b) ->
                DynamicTest.dynamicTest("${a.id} > ${b.id}") { check(strategy, a, b, separator) }
            }
            DynamicContainer.dynamicContainer(strategy.id, tests)
        }
    }

    private fun check(strategy: TransitionStrategy, a: SyntheticTrack, b: SyntheticTrack, separator: PseudoStemSeparator) {
        val features = pairAnalyzer.features(a.analysis, b.analysis, prefs)
        val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
        if (!app.applicable) {
            println("${strategy.id}: not applicable to ${a.id} > ${b.id} (${app.blockers}) — contract not exercised")
            return
        }
        // ---- plan (default params) ----
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.defaults(strategy.params), prefs, SEED)
        assertEquals(strategy.id, plan.strategyId)
        assertTrue(plan.expectedOutputFrames > 0, "expectedOutputFrames ${plan.expectedOutputFrames}")
        assertTrue(plan.aWindow.length > 0 && plan.bWindow.length > 0, "empty window: ${plan.aWindow} / ${plan.bWindow}")
        assertTrue(plan.aExitFrame in plan.aWindow && plan.bWindow.end >= plan.bEntryFrame)
        assertTrue(plan.aExitOffset + SpliceCheck.DEFAULT_GUARD <= plan.aWindow.length, "aWindow must cover the ${SpliceCheck.DEFAULT_GUARD}-frame pre-roll after aExitFrame")
        assertTrue(plan.bEntryOffset >= SpliceCheck.DEFAULT_GUARD, "bWindow must cover the ${SpliceCheck.DEFAULT_GUARD}-frame post-roll before bEntryFrame")
        val maxSec = 120.0
        assertTrue(plan.expectedOutputFrames <= maxSec * prefs.sampleRate, "segment longer than $maxSec s")
        // Deterministic plan.
        assertEquals(plan, strategy.plan(a.analysis, b.analysis, features, Params.defaults(strategy.params), prefs, SEED), "plan() is deterministic")

        // ---- input as the renderer hands it over ----
        val aAudio = loader.load(a.trackRef, plan.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }
        val bAudio = loader.load(b.trackRef, plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(b.analysis, prefs)) }
        val input = TransitionInput(plan, a.trackRef, b.trackRef, features, aAudio, bAudio, LazyStemProvider(aAudio, bAudio, separator, plan.stemNeed))

        // ---- render ----
        val rendered = strategy.render(input, RenderContext(prefs, SEED))
        verify(rendered, input)

        // ---- determinism ----
        val again = strategy.render(input, RenderContext(prefs, SEED))
        assertEquals(rendered.audio.frames, again.audio.frames)
        for (c in 0 until rendered.audio.channelCount) assertTrue(rendered.audio[c].contentEquals(again.audio[c]), "render is bit-identical (channel $c)")
    }

    private fun verify(rendered: RenderedTransition, input: TransitionInput) {
        val plan = input.plan
        val audio = rendered.audio
        assertEquals(input.aAudio.channelCount, audio.channelCount)
        assertEquals(prefs.sampleRate, audio.sampleRate)
        val tolerance = SpliceCheck.DEFAULT_LENGTH_TOLERANCE * plan.expectedOutputFrames
        assertTrue(abs(audio.frames - plan.expectedOutputFrames) <= tolerance, "length ${audio.frames} vs expected ${plan.expectedOutputFrames} (±1 %)")
        for (c in 0 until audio.channelCount) for (v in audio[c]) assertTrue(v.isFinite(), "non-finite sample")
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input), "splice contract")
        val peak = audio.peak()
        assertTrue(peak <= 1.0f, "peak $peak > 0 dBFS")
        val report = ArtifactDetector(audio.sampleRate).analyze(audio)
        assertTrue(report.clicks.isEmpty(), "clicks: ${report.clicks}")
        assertTrue(report.clipRuns.isEmpty(), "clipping: ${report.clipRuns}")
        assertTrue(rendered.report.warnings.none { it.startsWith("click") }, rendered.report.warnings.toString())
        assertTrue(rendered.report.peak <= 1.0f)
    }

    private companion object { const val SEED = 1234L }
}
