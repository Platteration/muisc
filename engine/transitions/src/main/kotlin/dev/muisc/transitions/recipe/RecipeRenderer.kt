package dev.muisc.transitions.recipe

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.MultibandCrossover
import dev.muisc.dsp.filter.StateVariableFilter
import dev.muisc.dsp.filter.SvfMode
import dev.muisc.dsp.fx.Delay
import dev.muisc.dsp.fx.FdnReverb
import dev.muisc.dsp.stems.Stems
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.core.PhaseLockedDeck
import dev.muisc.transitions.core.RenderReports
import dev.muisc.transitions.core.StretchMode
import dev.muisc.transitions.core.relativeTo
import dev.muisc.transitions.strategies.BeatDomain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Renders a [TransitionRecipe] on a plan made by [RecipeGeometry.plan].
 *
 * ## Per deck, in this order
 * 1. **Stems** (only when the deck has a non-neutral stem lane): the deck's decoded window is separated by
 *    `input.stems` (A: `aTail()`, B: `bHead()`), each stem is scaled by its dB lane and the four are summed back.
 *    This happens on the unstretched window — a window frame's bar is read from the deck's own beat grid (`match` /
 *    `glide`) or from A's bar length (`none`) — so every deck is stretched once, after its stems are mixed.
 * 2. **Alignment**: `match` / `glide` render the deck with a [PhaseLockedDeck] on the plan's master grid (as
 *    `bassSwap` does); `none` copies the window unstretched.
 * 3. **3-band EQ**: a Linkwitz-Riley [MultibandCrossover] at `lowHz` / `highHz`, one gain lane per band. Skipped
 *    for a deck whose three bands are neutral for the whole recipe. When it runs it runs for the whole deck (the
 *    all-pass-compensated sum is flat but not sample-identical, which the guard blends below absorb).
 * 4. **Filters**: a high-pass and a low-pass [StateVariableFilter] (resonance = the deck's `resonance`, clamped to
 *    0.5..6), each skipped when its lane is neutral for the whole recipe. The cutoff follows its lane on a log scale.
 *    The filtered signal is cross-faded with the unfiltered one by an "engage" amount that is 0 at the neutral
 *    cutoff and 1 once the cutoff is an octave away from it, so a parked filter is exactly transparent.
 * 5. **Sends** (pre-fader): the signal after EQ and filters, times the send lane, feeds the deck's echo ([Delay],
 *    wet only; delay = `beats` x the master beat period in effect, re-targeted with the delay's own crossfade when
 *    the tempo moves; `feedback` clamped to 0..0.95; `dampHz` = the loop's low-pass) and reverb ([FdnReverb], wet
 *    only; `decaySec` clamped to 0.1..60 s).
 * 6. **Level** (post-fader).
 *
 * Both decks and all effect returns are summed; the segment then goes through the same
 * [RenderReports.finalize] / [RenderReports.build] path as every built-in strategy.
 *
 * ## Automation
 * Lanes are evaluated every [BLOCK] output frames from the frame's timeline bar (`match` / `glide`: through the
 * master grid, so a bar is a bar at whatever tempo; `none`: A's bar length), converted to their working domain
 * (linear gain for level and dB lanes, ln Hz for cutoffs), smoothed by a [SMOOTH_TAPS]-block moving average over
 * the ~30 ms that FOLLOW each block, and interpolated linearly per sample. So every change is at least a ~30 ms
 * ramp (a `step` included), and the ramp ENDS on the point's bar (up to one block, 1.5 ms, early): a lane has
 * reached each point's value when the timeline reaches the point. Both halves matter on beat-aligned moves. A gain
 * that falls to silence within a few milliseconds *after* a transient cuts the transient short, which is audible
 * (and measurable) as a tick although the waveform has no discontinuity; a cut that is complete when the
 * downbeat's transient arrives removes it cleanly, and a deck that jumps up on a downbeat is at full level when its
 * transient starts. Freeze lanes are not smoothed (see below).
 *
 * ## Freeze
 * While a reverb's freeze lane is >= 0.5 (read every [BLOCK] frames) the reverb is frozen ([FdnReverb.freeze]: the
 * held tail sustains and its input is muted). When the freeze ends, the frozen instance keeps sounding with an
 * exponential fade at the reverb's RT60 (damping is not applied to that fade) and a fresh reverb takes the send
 * from then on: un-freezing an [FdnReverb] in place restarts its damping filters from the state they had when the
 * freeze began, and on a quiet mix that step is audible as clicks (see RecipeRenderEffectsTest).
 *
 * ## Splice contract and the boundary rule
 * The segment starts with [dev.muisc.transitions.core.Splice.GUARD_FRAMES] frames of A verbatim and ends with as
 * many of B verbatim. Deck A is processed from the start of the pre-roll (so every filter is warm) and enters the
 * segment with the same linear [BeatDomain.SEAM_BLEND_FRAMES]-frame blend `BeatDomain.Render.blendHead` uses; deck
 * B leaves it with the `blendTail` blend. Deck B fades in over [FADE_IN_FRAMES] frames at bar 0, deck A fades out
 * over [DECLICK_FRAMES] (10 ms) at the end of the overlap, and so do the send feeds, so a deck's hard edge never
 * reaches the output or an effect's input. Effect returns are released to silence over the last bar before the
 * post-roll (raised cosine), so no tail spills past the segment. Violations of the recipe's boundary rule (A not
 * neutral at bar 0, A still audible at the end of the overlap, B not neutral at the end, a send still open in the
 * last bar) are reported as warnings, never as failures.
 */
internal object RecipeRenderer {
    /** Frames between two lane evaluations. */
    const val BLOCK = 64

    /** Blocks in the look-ahead moving average applied to every continuous lane (1344 frames, ~30 ms at 44.1 kHz). */
    const val SMOOTH_TAPS = 21

    /** Fade at A's end of overlap (10 ms at 44.1 kHz) and on the send feeds there. */
    const val DECLICK_FRAMES = 441

    /** Fade-in of deck B and of every send feed at the deck's first frame (5 ms at 44.1 kHz). */
    const val FADE_IN_FRAMES = 220

    /** Shortest release of the effect returns before the post-roll. */
    const val MIN_RELEASE_FRAMES = 2048

    private const val CHUNK = 4096

    fun render(recipe: TransitionRecipe, input: TransitionInput, ctx: RenderContext): RenderedTransition {
        val t0 = System.nanoTime()
        val plan = input.plan
        val a = input.aAnalysis
        val b = input.bAnalysis
        val prefs = ctx.prefs
        val sr = ctx.sampleRate
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val r = RecipeResolver.resolve(recipe, plan.params, bpb)
        val t = RecipeGeometry.timeline(plan, r, a, b, input.features, prefs)
        val g = t.g
        val ch = input.aAudio.channelCount
        val warnings = ArrayList<String>(RecipeGeometry.boundaryIssues(r, t))
        val aOff = plan.aExitOffset
        val bOff = plan.bEntryOffset
        require(aOff >= 0 && aOff + g <= input.aAudio.frames) { "A window too short for the pre-roll" }
        require(bOff - g >= 0 && bOff <= input.bAudio.frames) { "B window too short for the post-roll" }
        ctx.progress(0.02)

        // 1. Stems, on the unstretched windows.
        val aSource = if (r.a.usesStems) applyStems(input.aAudio, input.stems.aTail(), r.a.stems!!, sourceBarMapA(t, input)) else input.aAudio
        ctx.progress(0.15)
        val bSource = if (r.b.usesStems) applyStems(input.bAudio, input.stems.bHead(), r.b.stems!!, sourceBarMapB(t, input)) else input.bAudio
        ctx.progress(0.25)

        // 2. Alignment: output-domain decks starting at output frame G.
        val aLen = (t.aEndFrame - g).toInt()
        val bLen = (t.endFrame - g).toInt()
        var deckA: PhaseLockedDeck? = null
        var deckB: PhaseLockedDeck? = null
        val aAligned: Array<FloatArray>
        val bAligned: Array<FloatArray>
        when (t) {
            is RecipeTimeline.Beat -> {
                val layout = t.layout
                val options = PhaseLockedDeck.Options(keyLock = BeatDomain.keyLockOf(plan.params, prefs))
                val aGridRel = layout.a.grid.relativeTo(plan.aWindow.start)
                val bGridRel = layout.bGrid.relativeTo(plan.bWindow.start)
                val aPins = BeatDomain.transientPins(input.aAudio, layout.a.onsetFrames.relativeTo(plan.aWindow.start, plan.aWindow.length.toLong()), true)
                val bPins = BeatDomain.transientPins(input.bAudio, layout.b.onsetFrames.relativeTo(plan.bWindow.start, plan.bWindow.length.toLong()), true)
                val da = PhaseLockedDeck(aSource, aGridRel, layout.aStartBeat.toDouble(), aPins, sampleRate = sr, options = options)
                aAligned = da.render(layout.grid, 0, layout.aBeats).channels
                ctx.progress(0.45)
                val db = PhaseLockedDeck(bSource, bGridRel, layout.bStartBeat.toDouble(), bPins, sampleRate = sr, options = options)
                bAligned = db.render(layout.grid, 0, layout.totalBeats).channels
                deckA = da; deckB = db
            }
            is RecipeTimeline.Linear -> {
                aAligned = copyWindow(aSource, (t.aStartSource - plan.aWindow.start).toInt(), aLen)
                bAligned = copyWindow(bSource, (t.bSourceAtStart - plan.bWindow.start).toInt(), bLen)
            }
        }
        require(aAligned[0].size == aLen && bAligned[0].size == bLen) { "aligned decks ${aAligned[0].size}/${bAligned[0].size} frames, timeline wants $aLen/$bLen" }
        ctx.progress(0.6)

        // 3-6. Deck chains. A is processed from the start of the pre-roll so its filters are warm at bar 0.
        val preRoll = Array(ch) { input.aAudio[it].copyOfRange(aOff, aOff + g) }
        val aWork = Array(ch) { c -> FloatArray(g + aLen).also { System.arraycopy(preRoll[c], 0, it, 0, g); System.arraycopy(aAligned[c], 0, it, g, aLen) } }
        val aOut = processDeck(aWork, g, r.a, r, t, sr)
        val bOut = processDeck(bAligned, 0, r.b, r, t, sr)
        ctx.progress(0.75)

        // Deck edges: A blends in from the dry pre-roll and is declicked at the end of the overlap; B fades in at bar 0
        // and blends back into the dry post-roll. Send feeds get the same edge fades.
        blendHead(aOut.post, input.aAudio, aOff + g)
        fadeOutTail(aOut.post, DECLICK_FRAMES)
        aOut.feeds().forEach { fadeInHead(it, FADE_IN_FRAMES); fadeOutTail(it, DECLICK_FRAMES) }
        fadeInHead(bOut.post, FADE_IN_FRAMES)
        blendTail(bOut.post, input.bAudio, bOff - g)
        bOut.feeds().forEach { fadeInHead(it, FADE_IN_FRAMES) }

        // Assemble: verbatim pre-roll and post-roll, both decks from G.
        val out = AudioBuffer.silence(sr, ch, t.expectedOutputFrames)
        val end = t.endFrame.toInt()
        for (c in 0 until ch) {
            System.arraycopy(input.aAudio[c], aOff, out[c], 0, g)
            System.arraycopy(input.bAudio[c], bOff - g, out[c], end, g)
            addInto(out[c], g, aOut.post[c], aLen)
            addInto(out[c], g, bOut.post[c], bLen)
        }

        // Effects: every return released over the last bar before the post-roll.
        val release = releaseEnvelope(t)
        for ((deck, res) in listOf(r.a to aOut, r.b to bOut)) {
            if (res.echoFeed != null) renderEcho(res.echoFeed, deck.echo!!, t, out, release)
            if (res.reverbFeed != null) renderReverb(res.reverbFeed, deck.reverb!!, t, out, release)
        }
        ctx.progress(0.9)

        val fin = RenderReports.finalize(out, ctx)
        val metrics = LinkedHashMap<String, Double>()
        metrics["timelineBars"] = t.timelineBars
        metrics["overlapBars"] = t.overlapBars
        metrics["boundaryWarnings"] = warnings.size.toDouble()
        if (t is RecipeTimeline.Beat) {
            metrics["masterBeats"] = t.layout.totalBeats.toDouble()
            metrics["masterBpmStart"] = t.layout.grid.bpmStart
            metrics["masterBpmEnd"] = t.layout.grid.bpmEnd
        }
        deckA?.let { d -> metrics["aMaxStretchPercent"] = d.ratioTrace.maxOfOrNull { abs(it - 1.0) * 100.0 } ?: 0.0; metrics["aWsola"] = if (d.modeUsed == StretchMode.WSOLA) 1.0 else 0.0 }
        deckB?.let { d -> metrics["bMaxStretchPercent"] = d.ratioTrace.maxOfOrNull { abs(it - 1.0) * 100.0 } ?: 0.0; metrics["bWsola"] = if (d.modeUsed == StretchMode.WSOLA) 1.0 else 0.0; metrics["bRatioEnd"] = d.ratioTrace.lastOrNull() ?: 1.0 }
        metrics["limited"] = if (fin.limited) 1.0 else 0.0
        metrics["gainReductionDb"] = fin.gainReductionDb
        metrics["truePeakBeforeDbtp"] = fin.truePeakBeforeDbtp
        val trace = deckB?.ratioTrace?.let { tr -> FloatArray(tr.size) { tr[it].toFloat() } } ?: FloatArray(0)
        val report = RenderReports.build(out, (System.nanoTime() - t0) / 1_000_000, trace, metrics, warnings + fin.warnings)
        val lanes = RecipeGeometry.timelineLanes(t, a) + RecipeGeometry.recipeLanes(r, t)
        ctx.progress(1.0)
        return RenderedTransition(plan.copy(lanes = lanes), out, RecipeGeometry.markers(r, t), report)
    }

    // ------------------------------------------------------------------------------------------ stems

    /** Window frame -> timeline bar for deck A's unstretched window. */
    private fun sourceBarMapA(t: RecipeTimeline, input: TransitionInput): (Long) -> Double = when (t) {
        is RecipeTimeline.Beat -> {
            val grid = t.layout.a.grid.relativeTo(input.plan.aWindow.start)
            val start = t.layout.aStartBeat.toDouble()
            val bpb = t.beatsPerBar
            { f -> (grid.beatAtFrame(f) - start) / bpb }
        }
        is RecipeTimeline.Linear -> {
            val origin = (t.aStartSource - input.plan.aWindow.start).toDouble()
            val bar = t.barFrames
            { f -> (f - origin) / bar }
        }
    }

    /** Window frame -> timeline bar for deck B's unstretched window. */
    private fun sourceBarMapB(t: RecipeTimeline, input: TransitionInput): (Long) -> Double = when (t) {
        is RecipeTimeline.Beat -> {
            val grid = t.layout.bGrid.relativeTo(input.plan.bWindow.start)
            val start = t.layout.bStartBeat.toDouble()
            val bpb = t.beatsPerBar
            { f -> (grid.beatAtFrame(f) - start) / bpb }
        }
        is RecipeTimeline.Linear -> {
            val origin = (t.bSourceAtStart - input.plan.bWindow.start).toDouble()
            val bar = t.barFrames
            { f -> (f - origin) / bar }
        }
    }

    /** `sum(stem_k * gain_k)` over the four stems; a neutral lane leaves its stem as is. */
    fun applyStems(window: AudioBuffer, stems: Stems, lanes: ResolvedStems, barAt: (Long) -> Double): AudioBuffer {
        require(stems.frames == window.frames && stems.channelCount == window.channelCount) { "stems (${stems.frames} frames) do not match the window (${window.frames})" }
        val n = window.frames
        val ch = window.channelCount
        val out = Array(ch) { FloatArray(n) }
        val gains = FloatArray(CHUNK)
        for ((stem, lane) in listOf(stems.drums to lanes.drums, stems.bass to lanes.bass, stems.vocals to lanes.vocals, stems.other to lanes.other)) {
            if (lane.isNeutral) {
                for (c in 0 until ch) { val y = out[c]; val x = stem[c]; for (i in 0 until n) y[i] += x[i] }
                continue
            }
            val bl = BlockLane.sample(lane, n, barAt, Domain.GAIN_DB)
            var pos = 0
            while (pos < n) {
                val m = min(CHUNK, n - pos)
                bl.fill(gains, pos, m)
                for (c in 0 until ch) { val y = out[c]; val x = stem[c]; for (i in 0 until m) y[pos + i] += x[pos + i] * gains[i] }
                pos += m
            }
        }
        return AudioBuffer(window.sampleRate, out)
    }

    // ------------------------------------------------------------------------------------------ deck chain

    /** A processed deck: the post-fader signal (from bar 0) and the pre-fader send feeds (null when unused). */
    class DeckOut(val post: Array<FloatArray>, val echoFeed: Array<FloatArray>?, val reverbFeed: Array<FloatArray>?) {
        fun feeds(): List<Array<FloatArray>> = listOfNotNull(echoFeed, reverbFeed)
    }

    /**
     * Runs one deck's chain over [x] (processed in place). `x[lead]` is output frame G (bar 0); the first [lead]
     * frames are a warm-up (A's dry pre-roll) that is processed and then dropped.
     */
    fun processDeck(x: Array<FloatArray>, lead: Int, deck: ResolvedDeck, r: ResolvedRecipe, t: RecipeTimeline, sr: Int): DeckOut {
        val n = x[0].size
        val origin = t.g.toLong() - lead
        val barAt: (Long) -> Double = { f -> t.barAtOutFrame(origin + f) }
        if (deck.usesEq) equalise(x, deck, r.lowHz, r.highHz, barAt, sr)
        if (!deck.hpf.isNeutral) filter(x, SvfMode.HIGH_PASS, deck.hpf, deck.resonance, barAt, sr)
        if (!deck.lpf.isNeutral) filter(x, SvfMode.LOW_PASS, deck.lpf, deck.resonance, barAt, sr)
        val echoFeed = if (deck.usesEcho) feed(x, lead, BlockLane.sample(deck.echo!!.send, n, barAt, Domain.LINEAR)) else null
        val reverbFeed = if (deck.usesReverb) {
            val rv = deck.reverb!!
            feed(x, lead, BlockLane.sample(rv.send, n, barAt, Domain.LINEAR))
        } else null
        if (!deck.level.isNeutral) {
            val level = BlockLane.sample(deck.level, n, barAt, Domain.LEVEL)
            val gains = FloatArray(CHUNK)
            var pos = 0
            while (pos < n) {
                val m = min(CHUNK, n - pos)
                level.fill(gains, pos, m)
                for (c in x.indices) { val y = x[c]; for (i in 0 until m) y[pos + i] *= gains[i] }
                pos += m
            }
        }
        val post = if (lead == 0) x else Array(x.size) { x[it].copyOfRange(lead, n) }
        return DeckOut(post, echoFeed, reverbFeed)
    }

    /** 3-band LR4 EQ, one gain lane per band, bands summed back (in place). */
    private fun equalise(x: Array<FloatArray>, deck: ResolvedDeck, lowHz: Double, highHz: Double, barAt: (Long) -> Double, sr: Int) {
        val ch = x.size
        val n = x[0].size
        val nyq = sr * 0.45
        val lo = lowHz.coerceIn(20.0, nyq * 0.5)
        val hi = highHz.coerceIn(lo * 1.01, nyq)
        val xo = MultibandCrossover(sr, ch, doubleArrayOf(lo, hi), CHUNK)
        val lanes = listOf(deck.low, deck.mid, deck.high).map { BlockLane.sample(it, n, barAt, Domain.GAIN_DB) }
        val inC = Array(ch) { FloatArray(CHUNK) }
        val bands = Array(3) { Array(ch) { FloatArray(CHUNK) } }
        val gains = Array(3) { FloatArray(CHUNK) }
        var pos = 0
        while (pos < n) {
            val m = min(CHUNK, n - pos)
            for (c in 0 until ch) System.arraycopy(x[c], pos, inC[c], 0, m)
            xo.process(inC, bands, m)
            for (k in 0 until 3) lanes[k].fill(gains[k], pos, m)
            val g0 = gains[0]; val g1 = gains[1]; val g2 = gains[2]
            for (c in 0 until ch) {
                val y = x[c]; val b0 = bands[0][c]; val b1 = bands[1][c]; val b2 = bands[2][c]
                for (i in 0 until m) y[pos + i] = b0[i] * g0[i] + b1[i] * g1[i] + b2[i] * g2[i]
            }
            pos += m
        }
    }

    /**
     * Resonant SVF following a cutoff lane (log scale), cross-faded with the unfiltered signal by its engage amount
     * (0 at the neutral cutoff, 1 an octave or more away from it), in place.
     */
    private fun filter(x: Array<FloatArray>, mode: SvfMode, lane: ResolvedLane, resonance: Double, barAt: (Long) -> Double, sr: Int) {
        val ch = x.size
        val n = x[0].size
        val cut = BlockLane.sample(lane, n, barAt, Domain.LOG_HZ)
        val neutralLn = ln(lane.kind.neutral)
        // Exactly 0 at the neutral cutoff (the mean of identical block values can be an ulp off), so a parked filter
        // is bypassed sample-exactly.
        val engage = cut.map { v -> (abs(v - neutralLn) / LN2).let { if (it < 1e-9) 0.0 else it.coerceAtMost(1.0) } }
        val q = resonance.coerceIn(MIN_Q, MAX_Q)
        val svf = StateVariableFilter(sr, ch, exp(cut.valueAtBlock(0)), q)
        svf.mode = mode
        val dry = Array(ch) { FloatArray(BLOCK) }
        val wet = Array(ch) { FloatArray(BLOCK) }
        val e = FloatArray(BLOCK)
        var pos = 0
        var k = 0
        while (pos < n) {
            val m = min(BLOCK, n - pos)
            svf.setCutoff(exp(cut.valueAtBlock(k + 1)))
            for (c in 0 until ch) System.arraycopy(x[c], pos, dry[c], 0, m)
            svf.process(dry, wet, m)
            engage.fill(e, pos, m)
            for (c in 0 until ch) {
                val y = x[c]; val d = dry[c]; val w = wet[c]
                for (i in 0 until m) y[pos + i] = d[i] + e[i] * (w[i] - d[i])
            }
            pos += m
            k++
        }
    }

    /** Pre-fader send feed from bar 0: `x * send`. */
    private fun feed(x: Array<FloatArray>, lead: Int, send: BlockLane): Array<FloatArray> {
        val n = x[0].size - lead
        val out = Array(x.size) { FloatArray(n) }
        val s = FloatArray(CHUNK)
        var pos = 0
        while (pos < n) {
            val m = min(CHUNK, n - pos)
            send.fill(s, lead + pos, m)
            for (c in x.indices) { val y = out[c]; val src = x[c]; for (i in 0 until m) y[pos + i] = src[lead + pos + i] * s[i] }
            pos += m
        }
        return out
    }

    /** Whether the reverb is frozen in each block (lane value at the block start >= 0.5). */
    private fun frozenBlocks(freeze: ResolvedLane, n: Int, barAt: (Long) -> Double): BooleanArray =
        BooleanArray(n / BLOCK + 2) { k -> freeze.valueAt(barAt(k.toLong() * BLOCK)) >= 0.5 }

    // ------------------------------------------------------------------------------------------ edges

    /** Linear blend from dry A (continuing the pre-roll at `dry[off]`) into the first [BeatDomain.SEAM_BLEND_FRAMES] of [x]. */
    private fun blendHead(x: Array<FloatArray>, dry: AudioBuffer, off: Int) {
        val n = minOf(BeatDomain.SEAM_BLEND_FRAMES, x[0].size, dry.frames - off)
        for (c in x.indices) {
            val d = dry[c]; val y = x[c]
            for (i in 0 until n) { val w = (i + 1).toFloat() / (n + 1); y[i] = d[off + i] * (1f - w) + y[i] * w }
        }
    }

    /** Linear blend from the last [BeatDomain.SEAM_BLEND_FRAMES] of [x] into dry B (ending at `dry[end]`, where the post-roll starts). */
    private fun blendTail(x: Array<FloatArray>, dry: AudioBuffer, end: Int) {
        val n = minOf(BeatDomain.SEAM_BLEND_FRAMES, x[0].size, end)
        val x0 = x[0].size - n
        for (c in x.indices) {
            val d = dry[c]; val y = x[c]
            for (i in 0 until n) { val w = (i + 1).toFloat() / (n + 1); y[x0 + i] = y[x0 + i] * (1f - w) + d[end - n + i] * w }
        }
    }

    private fun fadeInHead(x: Array<FloatArray>, frames: Int) {
        val n = min(frames, x[0].size)
        for (c in x.indices) { val y = x[c]; for (i in 0 until n) y[i] *= i.toFloat() / n }
    }

    private fun fadeOutTail(x: Array<FloatArray>, frames: Int) {
        val len = x[0].size
        val n = min(frames, len)
        for (c in x.indices) { val y = x[c]; for (i in 0 until n) y[len - n + i] *= (n - 1 - i).toFloat() / n }
    }

    // ------------------------------------------------------------------------------------------ effects

    /** Gain applied to every effect return, per timeline frame: 1, then a raised-cosine release to 0 over the last bar. */
    private fun releaseEnvelope(t: RecipeTimeline): FloatArray {
        val total = (t.endFrame - t.g).toInt()
        val lastBar = Math.round(t.outFrameOfBar(t.timelineBars - 1.0)) - t.g
        val start = lastBar.toInt().coerceIn(0, max(0, total - MIN_RELEASE_FRAMES))
        val len = total - start
        return FloatArray(total) { i -> if (i < start) 1f else (0.5 * (1.0 + cos(PI * (i - start + 1) / len))).toFloat() }
    }

    private fun renderEcho(feed: Array<FloatArray>, e: ResolvedEcho, t: RecipeTimeline, out: AudioBuffer, release: FloatArray) {
        val sr = out.sampleRate
        val ch = out.channelCount
        val g = t.g
        val total = release.size
        val beats = e.beats.coerceIn(MIN_ECHO_BEATS, MAX_ECHO_BEATS)
        var maxPeriod = 0.0
        var probe = 0
        while (probe < total) { maxPeriod = max(maxPeriod, t.beatPeriodAt(g.toLong() + probe)); probe += CHUNK }
        val delay = Delay(sr, ch, maxDelaySeconds = beats * maxPeriod / sr + 0.05)
        delay.mix = 1.0
        delay.feedback = e.feedback.coerceIn(0.0, MAX_FEEDBACK)
        delay.setLowPass(e.dampHz.coerceIn(20.0, sr * 0.49))
        delay.setDelayFrames(beats * t.beatPeriodAt(g.toLong()), immediate = true)
        val ret = e.returnLevel.coerceIn(0.0, 2.0).toFloat()
        val inC = Array(ch) { FloatArray(CHUNK) }
        val outC = Array(ch) { FloatArray(CHUNK) }
        val fed = feed[0].size
        var pos = 0
        while (pos < total) {
            val m = min(CHUNK, total - pos)
            val target = beats * t.beatPeriodAt(g.toLong() + pos)
            if (abs(target - delay.delayFrames) >= 1.0) delay.setDelayFrames(target)
            for (c in 0 until ch) {
                val src = feed[c]; val d = inC[c]
                for (i in 0 until m) d[i] = if (pos + i < fed) src[pos + i] else 0f
            }
            delay.process(inC, outC, m)
            for (c in 0 until ch) { val y = out[c]; val w = outC[c]; for (i in 0 until m) y[g + pos + i] += w[i] * ret * release[pos + i] }
            pos += m
        }
    }

    private fun newReverb(v: ResolvedReverb, sr: Int, ch: Int): FdnReverb = FdnReverb(sr, ch).apply {
        mix = 1.0
        decaySeconds = v.decaySec.coerceIn(MIN_DECAY_SEC, MAX_DECAY_SEC)
        dampingHz = v.dampHz.coerceIn(20.0, sr.toDouble())
    }

    private class Releasing(val reverb: FdnReverb, var gain: Double)

    private fun renderReverb(feed: Array<FloatArray>, v: ResolvedReverb, t: RecipeTimeline, out: AudioBuffer, release: FloatArray) {
        val sr = out.sampleRate
        val ch = out.channelCount
        val g = t.g
        val total = release.size
        val ret = v.returnLevel.coerceIn(0.0, 2.0).toFloat()
        val barAt: (Long) -> Double = { f -> t.barAtOutFrame(g + f) }
        val frozen = if (v.freeze.isNeutral) null else frozenBlocks(v.freeze, total, barAt)
        // Per-sample decay of a released frozen tail: -60 dB over the reverb's RT60.
        val decayPerSample = 10.0.pow(-3.0 / (v.decaySec.coerceIn(MIN_DECAY_SEC, MAX_DECAY_SEC) * sr))
        var active = newReverb(v, sr, ch)
        val releasing = ArrayList<Releasing>()
        val inB = Array(ch) { FloatArray(BLOCK) }
        val outB = Array(ch) { FloatArray(BLOCK) }
        val zeros = Array(ch) { FloatArray(BLOCK) }
        val tmp = Array(ch) { FloatArray(BLOCK) }
        val fed = feed[0].size
        var pos = 0
        var k = 0
        while (pos < total) {
            val m = min(BLOCK, total - pos)
            if (frozen != null) {
                val fz = frozen[k]
                if (fz && !active.freeze) active.freeze = true
                if (!fz && active.freeze) { releasing += Releasing(active, 1.0); active = newReverb(v, sr, ch) }
            }
            for (c in 0 until ch) { val src = feed[c]; val d = inB[c]; for (i in 0 until m) d[i] = if (pos + i < fed) src[pos + i] else 0f }
            active.process(inB, outB, m)
            val iter = releasing.iterator()
            while (iter.hasNext()) {
                val rel = iter.next()
                rel.reverb.process(zeros, tmp, m)
                for (i in 0 until m) {
                    val gn = rel.gain.toFloat()
                    for (c in 0 until ch) outB[c][i] += tmp[c][i] * gn
                    rel.gain *= decayPerSample
                }
                if (rel.gain < RELEASED_FLOOR) iter.remove()
            }
            for (c in 0 until ch) { val y = out[c]; val w = outB[c]; for (i in 0 until m) y[g + pos + i] += w[i] * ret * release[pos + i] }
            pos += m
            k++
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** `frames` frames of [src] from index [from] (zeros outside the buffer). */
    private fun copyWindow(src: AudioBuffer, from: Int, frames: Int): Array<FloatArray> = Array(src.channelCount) { c ->
        val out = FloatArray(frames)
        val lo = max(0, -from)
        val hi = min(frames, src.frames - from)
        if (hi > lo) System.arraycopy(src[c], from + lo, out, lo, hi - lo)
        out
    }

    private fun addInto(dst: FloatArray, offset: Int, src: FloatArray, frames: Int) {
        val n = min(frames, dst.size - offset)
        for (i in 0 until n) dst[offset + i] += src[i]
    }

    private val LN2 = ln(2.0)
    const val MIN_Q = 0.5
    const val MAX_Q = 6.0
    const val MAX_FEEDBACK = 0.95
    const val MIN_ECHO_BEATS = 1.0 / 64
    const val MAX_ECHO_BEATS = 8.0
    const val MIN_DECAY_SEC = 0.1
    const val MAX_DECAY_SEC = 60.0
    /** A released frozen tail is dropped below −100 dB. */
    private const val RELEASED_FLOOR = 1e-5
}

/** Working domain a lane is interpolated in. */
internal enum class Domain {
    /** Linear gain 0..2. */
    LEVEL,
    /** dB converted to linear gain (−120 dB = exactly 0). */
    GAIN_DB,
    /** ln(Hz), 20 Hz..20 kHz. */
    LOG_HZ,
    /** Plain value 0..1 (sends). */
    LINEAR;

    fun transform(v: Double): Double = when (this) {
        LEVEL -> v.coerceIn(0.0, 2.0)
        GAIN_DB -> if (v <= RecipeResolver.OFF_DB) 0.0 else 10.0.pow(v.coerceAtMost(12.0) / 20.0)
        LOG_HZ -> ln(v.coerceIn(20.0, 20000.0))
        LINEAR -> v.coerceIn(0.0, 1.0)
    }
}

/**
 * A lane sampled at block boundaries (every [RecipeRenderer.BLOCK] frames of some frame axis), interpolated linearly
 * per sample by [fill]. `v[k]` is the value at local frame `k * BLOCK`.
 */
internal class BlockLane(private val v: DoubleArray) {
    fun valueAtBlock(k: Int): Double = v[k.coerceIn(0, v.size - 1)]

    /** Fills `out[0 until n]` with the lane at local frames `from until from + n`. */
    fun fill(out: FloatArray, from: Int, n: Int) {
        val b = RecipeRenderer.BLOCK
        var i = 0
        while (i < n) {
            val f = from + i
            val k = f / b
            val r = f - k * b
            val cnt = min(n - i, b - r)
            val v0 = valueAtBlock(k)
            val slope = (valueAtBlock(k + 1) - v0) / b
            for (j in 0 until cnt) out[i + j] = (v0 + slope * (r + j)).toFloat()
            i += cnt
        }
    }

    /** A new lane with [f] applied to every block value. */
    fun map(f: (Double) -> Double): BlockLane = BlockLane(DoubleArray(v.size) { f(v[it]) })

    companion object {
        /**
         * Samples [lane] at the block boundaries of `[0, frames]` ([barAt] maps a local frame to a timeline bar),
         * transforms into [domain] and smooths: the value at boundary `k` is the mean of the lane at the
         * [RecipeRenderer.SMOOTH_TAPS] boundaries AFTER it (`k + 1 .. k + SMOOTH_TAPS`), a look-ahead average, so a
         * step is complete at the last boundary before its bar and the sample-rate interpolation never leaks past it.
         */
        fun sample(lane: ResolvedLane, frames: Int, barAt: (Long) -> Double, domain: Domain): BlockLane {
            val b = RecipeRenderer.BLOCK
            val nb = frames / b + 2
            val taps = RecipeRenderer.SMOOTH_TAPS
            val raw = DoubleArray(nb + taps) { j -> domain.transform(lane.valueAt(barAt(j.toLong() * b))) }
            return BlockLane(DoubleArray(nb) { k -> var s = 0.0; for (j in k + 1..k + taps) s += raw[j]; s / taps })
        }
    }
}
