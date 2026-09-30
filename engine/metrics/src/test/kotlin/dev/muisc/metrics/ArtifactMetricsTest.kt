package dev.muisc.metrics

import dev.muisc.audio.AudioBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every metric is shown on a real [dev.muisc.transitions.strategies.CrossfadeStrategy] render of two
 * ground-truth synthetic tracks: clean it PASSes, and with exactly one injected defect its verdict changes.
 */
class ArtifactMetricsTest {

    private val case = MetricsFixtures.crossfade()
    private val sr = case.audio.sampleRate

    private fun evaluate(rendered: dev.muisc.transitions.RenderedTransition = case.rendered) =
        ArtifactMetrics.evaluate(rendered, case.input)

    // ---- the clean render -------------------------------------------------------------------------------

    @Test
    fun cleanCrossfadeRenderPassesEveryMetric() {
        val report = evaluate()
        assertEquals(Verdict.PASS, report.worst, report.summary())
        assertEquals(0.0, report.value(ArtifactMetrics.CLICKS))
        assertEquals(0.0, report.value(ArtifactMetrics.CLIPPING))
        assertEquals(0.0, report.value(ArtifactMetrics.NAN_INF))
        assertEquals(0.0, report.value(ArtifactMetrics.SILENCE_GAP_MS))
        assertEquals(0.0, report.value(ArtifactMetrics.LENGTH_ERROR_PCT)!!, 1e-9)
        assertTrue(report.value(ArtifactMetrics.LEVEL_JUMP_DB)!! < ArtifactMetrics.LEVEL_JUMP_WARN_DB)
        assertTrue(report.value(ArtifactMetrics.TRUE_PEAK_DBTP)!! <= -1.0)
        assertTrue(report.value(ArtifactMetrics.LOUDNESS_SMOOTHNESS)!! < ArtifactMetrics.LOUDNESS_SMOOTHNESS_WARN)
        assertTrue(report.value(ArtifactMetrics.DC_OFFSET_DB)!! < ArtifactMetrics.DC_OFFSET_FAIL_DB)
        // The beat alignment metrics need a master grid, which the crossfade (no stretching) does not publish.
        assertNull(report.metric(ArtifactMetrics.BEAT_ALIGNMENT_MS))
        assertEquals(13, report.metrics.size, report.csvHeader())
    }

    @Test
    fun evaluationIsDeterministic() {
        val first = evaluate()
        val second = evaluate()
        assertEquals(first, second)
        assertEquals(first.toCsvRow(), second.toCsvRow())
        assertEquals(first.toJson(), second.toJson())
    }

    // ---- clicks -----------------------------------------------------------------------------------------

    @Test
    fun anInjectedClickFailsAndOnlyThere() {
        val frame = MetricsFixtures.onsetFreeFrame(case, case.audio.frames / 2, 80.0)
        val clicked = MetricsFixtures.mutated(case.rendered) { buf -> for (ch in buf.channels) ch[frame] += 0.5f }
        val report = evaluate(clicked)
        assertEquals(1.0, report.value(ArtifactMetrics.CLICKS), "one click, report: ${report.summary()}")
        assertEquals(Verdict.FAIL, report.verdict(ArtifactMetrics.CLICKS))
        assertEquals(Verdict.FAIL, report.worst)
        // The clean render has none, so the difference really is the injected sample.
        assertEquals(0.0, evaluate().value(ArtifactMetrics.CLICKS))
    }

    @Test
    fun aClickOnASourceOnsetIsNotCounted() {
        val onsets = ArtifactMetrics.sourceOnsetsInOutput(case.rendered, case.input)
        assertTrue(onsets.size > 10, "the synthetic pair has drum onsets in both windows: ${onsets.size}")
        val onset = onsets[onsets.size / 2]
        val clicked = MetricsFixtures.mutated(case.rendered) { buf -> for (ch in buf.channels) ch[onset] += 0.5f }
        assertEquals(0.0, ArtifactMetrics.evaluate(clicked, case.input).value(ArtifactMetrics.CLICKS), "excused by the source onset")
        // ... and it IS a click when the sources are not available to excuse it.
        assertTrue(ArtifactMetrics.clickCount(clicked.audio, IntArray(0)) >= 1)
    }

    // ---- level jumps ------------------------------------------------------------------------------------

    @Test
    fun aSustainedLevelStepIsGradedByItsSize() {
        val clean = evaluate().value(ArtifactMetrics.LEVEL_JUMP_DB)!!
        val warn = evaluate(stepped(6.0)).metric(ArtifactMetrics.LEVEL_JUMP_DB)!!
        val fail = evaluate(stepped(10.0)).metric(ArtifactMetrics.LEVEL_JUMP_DB)!!
        assertTrue(warn.value > clean, "a 6 dB step raises the measured jump (${warn.value} vs $clean)")
        assertTrue(warn.verdict.worseThan(Verdict.PASS), "6 dB step: ${warn}")
        assertEquals(Verdict.FAIL, fail.verdict, "10 dB step: $fail")
        assertTrue(fail.value > warn.value)
    }

    /** The render with everything from an onset-free, block-aligned frame on multiplied by [db]. */
    private fun stepped(db: Double): dev.muisc.transitions.RenderedTransition {
        val w = Signals.msFrames(ArtifactMetrics.LEVEL_WINDOW_MS, sr)
        val frame = MetricsFixtures.onsetFreeFrame(case, case.audio.frames / 2, 80.0) / w * w
        val gain = Math.pow(10.0, db / 20.0).toFloat()
        return MetricsFixtures.mutated(case.rendered) { buf ->
            for (ch in buf.channels) for (i in frame until buf.frames) ch[i] *= gain
        }
    }

    // ---- level jumps in a beat-domain render ------------------------------------------------------------

    private val blend by lazy { MetricsFixtures.beatMatchedBlend() }

    /** Output frames of the render's master beats, from the `masterBeat` lane. */
    private fun masterBeatFrames(case: MetricsFixtures.Case): IntArray {
        val points = case.rendered.plan.lanes.first { it.id == ArtifactMetrics.MASTER_BEAT_LANE }.points
        return IntArray(points.size) { Math.round(points[it].outputSec * case.audio.sampleRate).toInt() }
    }

    /**
     * Regression for QUALITY.md known failure 3 (`levelJumpDb` 24-29 dB on every long beat-domain blend). A
     * beat-domain render time-stretches its decks onto the master grid, so the splice contract's constant offsets
     * put the sources' onsets where the render does not play them - here B, stretched 5 % onto A's grid, is off by
     * up to 82 ms through the settle glide - and the kick on every master beat, a 20-40 dB rise out of the gap
     * before it in both sources, was scored as a level jump (20.2 dB on this render; 5.95 dB once the kicks are
     * excused, a rise in B's own material during the hold). Every kick the render plays on a master beat must have
     * a mapped source onset within the click check's own 5 ms guard.
     */
    @Test
    fun beatDomainSourceOnsetsLandOnTheKicksTheRenderPlays() {
        val onsets = ArtifactMetrics.sourceOnsetsInOutput(blend.rendered, blend.input)
        val renderOnsets = Signals.onsetFrames(blend.audio)
        val tight = Signals.msFrames(ArtifactMetrics.ONSET_GUARD_MS, sr)
        val kicks = masterBeatFrames(blend).toList().mapNotNull { beat -> renderOnsets.minByOrNull { abs(it - beat) }?.takeIf { abs(it - beat) <= tight } }
        assertTrue(kicks.size >= 40, "the render plays a kick on (nearly) every one of its ${masterBeatFrames(blend).size} master beats: ${kicks.size}")
        val missed = kicks.filter { !ArtifactMetrics.nearAny(onsets, it, tight) }
        assertEquals(emptyList(), missed.map { "%.3f s".format(it.toDouble() / sr) }, "kicks with no mapped source onset within ${ArtifactMetrics.ONSET_GUARD_MS} ms")
    }

    /**
     * The mapping fix must not have made the level check blind inside a beat-domain segment.
     *
     * It moves exclusions, it does not add them: the beat map excuses fewer of the render's window boundaries than
     * the constant offsets did (A's and B's kicks now land on the same master beats instead of on two). A sustained
     * gain step injected where B is time-stretched is read at the boundary where it is, over the boundaries within
     * 100 ms of it - the whole render's maximum cannot show a 6 dB step here, because it already holds a 5.95 dB rise
     * of B's own (see above) and the statistic reads a 6 dB step in percussive material as 1-6 dB (see
     * [ArtifactMetrics.levelJumpDb]). Swept every 50 ms over the stretched body, at least as many steps are graded
     * WARN (6 dB) and FAIL (10 dB) with the beat map as with the constant offsets; and at the onset-free frame
     * nearest the middle of the render - the rule the crossfade test uses - a 6 dB step WARNs and a 10 dB step FAILs.
     */
    @Test
    fun aLevelStepInsideABeatDomainBlendIsStillDetected() {
        val audio = blend.audio
        val plan = blend.plan
        val w = Signals.msFrames(ArtifactMetrics.LEVEL_WINDOW_MS, sr)
        val guard = Signals.msFrames(ArtifactMetrics.LEVEL_ONSET_GUARD_MS, sr)
        val onsets = ArtifactMetrics.sourceOnsetsInOutput(blend.rendered, blend.input)
        val offsetMapped = (Signals.onsetFrames(blend.input.aAudio).map { it - plan.aExitOffset } +
            Signals.onsetFrames(blend.input.bAudio).map { it + audio.frames - plan.bEntryOffset })
            .filter { it in 0 until audio.frames }.sorted().toIntArray()
        fun excused(ex: IntArray) = (0 until audio.frames / w).count { ArtifactMetrics.nearAny(ex, it * w, guard) }
        assertTrue(excused(onsets) < excused(offsetMapped), "boundaries excused: beat map ${excused(onsets)}, constant offsets ${excused(offsetMapped)}")

        val beats = masterBeatFrames(blend)
        var warnWithBeatMap = 0; var warnWithOffsets = 0; var failWithBeatMap = 0; var failWithOffsets = 0
        var frame = beats[4] / w * w
        while (frame < beats[52]) {
            if (localStepReading(audio, onsets, frame, 6.0) >= ArtifactMetrics.LEVEL_JUMP_WARN_DB) warnWithBeatMap++
            if (localStepReading(audio, offsetMapped, frame, 6.0) >= ArtifactMetrics.LEVEL_JUMP_WARN_DB) warnWithOffsets++
            if (localStepReading(audio, onsets, frame, 10.0) >= ArtifactMetrics.LEVEL_JUMP_FAIL_DB) failWithBeatMap++
            if (localStepReading(audio, offsetMapped, frame, 10.0) >= ArtifactMetrics.LEVEL_JUMP_FAIL_DB) failWithOffsets++
            frame += 5 * w
        }
        assertTrue(warnWithBeatMap > 0 && warnWithBeatMap >= warnWithOffsets,"6 dB steps graded WARN: beat map $warnWithBeatMap, constant offsets $warnWithOffsets")
        assertTrue(failWithBeatMap >= failWithOffsets, "10 dB steps graded FAIL: beat map $failWithBeatMap, constant offsets $failWithOffsets")

        val mid = MetricsFixtures.onsetFreeFrame(blend, audio.frames / 2, 80.0) / w * w
        val clean = localStepReading(audio, onsets, mid, 0.0)
        val six = localStepReading(audio, onsets, mid, 6.0)
        val ten = localStepReading(audio, onsets, mid, 10.0)
        assertTrue(clean < ArtifactMetrics.LEVEL_JUMP_WARN_DB, "clean render at ${mid.toDouble() / sr} s: $clean dB")
        assertTrue(six >= ArtifactMetrics.LEVEL_JUMP_WARN_DB && six > clean, "a 6 dB step at ${mid.toDouble() / sr} s reads $six dB (clean $clean dB)")
        assertTrue(ten >= ArtifactMetrics.LEVEL_JUMP_FAIL_DB, "a 10 dB step at ${mid.toDouble() / sr} s reads $ten dB")
    }

    /**
     * [ArtifactMetrics.levelJumpDb] over the boundaries within 100 ms of the block-aligned [frame] of [audio], after
     * everything from [frame] on has been multiplied by [db] - read on a 400 ms slice (block-aligned, so the block
     * grid is the full buffer's) rather than on a copy of the whole render.
     */
    private fun localStepReading(audio: AudioBuffer, excluded: IntArray, frame: Int, db: Double): Double {
        val w = Signals.msFrames(ArtifactMetrics.LEVEL_WINDOW_MS, sr)
        val start = frame - 20 * w
        val region = audio.slice(start, frame + 20 * w)
        val gain = Math.pow(10.0, db / 20.0).toFloat()
        for (ch in region.channels) for (i in 20 * w until region.frames) ch[i] *= gain
        val shifted = IntArray(excluded.size) { excluded[it] - start }
        return ArtifactMetrics.levelJumpDb(region, shifted, 10 * w, 30 * w)
    }

    // ---- peaks, clipping, DC, NaN -----------------------------------------------------------------------

    @Test
    fun clippingAndDcAndNanAreDetected() {
        val clipped = MetricsFixtures.mutated(case.rendered) { buf ->
            for (i in 0 until 64) { buf[0][1000 + i] = 1.4f; buf[1][1000 + i] = -1.4f }
        }
        val clip = evaluate(clipped)
        assertEquals(128.0, clip.value(ArtifactMetrics.CLIPPING))
        assertEquals(Verdict.FAIL, clip.verdict(ArtifactMetrics.CLIPPING))
        assertEquals(Verdict.FAIL, clip.verdict(ArtifactMetrics.TRUE_PEAK_DBTP), "1.4 is well over -0.5 dBTP")

        val dc = MetricsFixtures.mutated(case.rendered) { buf ->
            for (ch in buf.channels) for (i in ch.indices) ch[i] += 0.01f // -40 dBFS offset
        }
        val dcReport = evaluate(dc)
        assertEquals(-40.0, dcReport.value(ArtifactMetrics.DC_OFFSET_DB)!!, 2.0)
        assertEquals(Verdict.FAIL, dcReport.verdict(ArtifactMetrics.DC_OFFSET_DB))
        assertEquals(Verdict.PASS, evaluate().verdict(ArtifactMetrics.DC_OFFSET_DB))

        val nan = MetricsFixtures.mutated(case.rendered) { buf ->
            buf[0][500] = Float.NaN
            buf[1][501] = Float.POSITIVE_INFINITY
        }
        val nanReport = ArtifactMetrics.nanInf(nan.audio)
        assertEquals(2.0, nanReport.value)
        assertEquals(Verdict.FAIL, nanReport.verdict)
        assertEquals(0.0, ArtifactMetrics.nanInf(case.audio).value)
    }

    // ---- silence ----------------------------------------------------------------------------------------

    @Test
    fun aSilentHoleWarns() {
        val gapFrames = sr / 10 // 100 ms
        val holed = MetricsFixtures.mutated(case.rendered) { buf ->
            val from = buf.frames / 2
            for (ch in buf.channels) java.util.Arrays.fill(ch, from, from + gapFrames, 0f)
        }
        val metric = ArtifactMetrics.silenceGap(holed.audio)
        assertEquals(100.0, metric.value, 10.0, "the hole is measured to the 5 ms block grid")
        assertEquals(Verdict.WARN, metric.verdict)
        assertEquals(Verdict.PASS, ArtifactMetrics.silenceGap(case.audio).verdict)
        // A hole shorter than the threshold is not reported.
        val small = MetricsFixtures.mutated(case.rendered) { buf ->
            val from = buf.frames / 2
            for (ch in buf.channels) java.util.Arrays.fill(ch, from, from + sr / 100, 0f)
        }
        assertEquals(Verdict.PASS, ArtifactMetrics.silenceGap(small.audio).verdict)
    }

    // ---- seam identity ----------------------------------------------------------------------------------

    @Test
    fun seamIdentityPassesOnACrossfadeAndFailsWhenTheHeadIsZeroed() {
        val clean = evaluate()
        assertEquals(0.0, clean.value(ArtifactMetrics.SEAM_IDENTITY), "the guard regions are the sources, sample for sample")
        assertEquals(1.0, clean.value(ArtifactMetrics.SEAM_CORRELATION)!!, 1e-9)
        assertEquals(Verdict.PASS, clean.verdict(ArtifactMetrics.SEAM_IDENTITY))

        val zeroed = MetricsFixtures.mutated(case.rendered) { buf ->
            for (ch in buf.channels) java.util.Arrays.fill(ch, 0, 100, 0f)
        }
        val broken = evaluate(zeroed)
        assertTrue(broken.value(ArtifactMetrics.SEAM_IDENTITY)!! > ArtifactMetrics.SEAM_DIFF_FAIL, broken.summary())
        assertEquals(Verdict.FAIL, broken.verdict(ArtifactMetrics.SEAM_IDENTITY))
        assertEquals(Verdict.FAIL, broken.verdict(ArtifactMetrics.SEAM_CORRELATION))

        // The same defect at the end of the segment breaks the B side of the contract.
        val tailZeroed = MetricsFixtures.mutated(case.rendered) { buf ->
            for (ch in buf.channels) java.util.Arrays.fill(ch, buf.frames - 100, buf.frames, 0f)
        }
        assertEquals(Verdict.FAIL, evaluate(tailZeroed).verdict(ArtifactMetrics.SEAM_IDENTITY))
    }

    // ---- tail containment, length, stereo, loudness -----------------------------------------------------

    @Test
    fun tailContainmentDetectsMaterialLeakingPastTheSeam() {
        assertEquals(Verdict.PASS, evaluate().verdict(ArtifactMetrics.TAIL_CONTAINED_DB))
        val leaking = MetricsFixtures.mutated(case.rendered) { buf ->
            val n = sr / 10
            for (ch in buf.channels) for (i in buf.frames - n until buf.frames) {
                ch[i] += 0.05f * Math.sin(0.05 * i).toFloat()
            }
        }
        val metric = ArtifactMetrics.tailContained(leaking, case.input)
        assertTrue(metric.value > ArtifactMetrics.TAIL_CONTAINED_FAIL_DB, "residual $metric")
        assertEquals(Verdict.FAIL, metric.verdict)
        // Without sources there is nothing to compare against: PASS, not a false alarm.
        assertEquals(Verdict.PASS, ArtifactMetrics.tailContained(leaking, null).verdict)
    }

    @Test
    fun lengthErrorWarnsBeyondOnePercent() {
        assertEquals(Verdict.PASS, ArtifactMetrics.lengthError(case.rendered).verdict)
        val short = MetricsFixtures.withAudio(case.rendered, case.audio.slice(0, (case.audio.frames * 0.9).toInt()))
        val metric = ArtifactMetrics.lengthError(short)
        assertEquals(10.0, metric.value, 0.01)
        assertEquals(Verdict.WARN, metric.verdict)
        val tiny = MetricsFixtures.withAudio(case.rendered, case.audio.slice(0, case.audio.frames - 100))
        assertEquals(Verdict.PASS, ArtifactMetrics.lengthError(tiny).verdict, "100 frames of 272k is well under 1 %")
    }

    @Test
    fun stereoCorrelationWarnsWhenTheSidesAreOutOfPhase() {
        val metric = assertNotNull(ArtifactMetrics.stereoCorrelation(case.audio))
        assertTrue(metric.value > 0.0, "the synthetic pair is largely correlated: $metric")
        assertEquals(Verdict.PASS, metric.verdict)
        val flipped = MetricsFixtures.mutated(case.rendered) { buf ->
            for (i in buf[1].indices) buf[1][i] = -buf[1][i]
        }
        val out = assertNotNull(ArtifactMetrics.stereoCorrelation(flipped.audio))
        assertTrue(out.value < ArtifactMetrics.STEREO_CORRELATION_WARN, "polarity flip: $out")
        assertEquals(Verdict.WARN, out.verdict)
        assertNull(ArtifactMetrics.stereoCorrelation(AudioBuffer.mono(sr, case.audio.mono())), "mono has no stereo image")
    }

    @Test
    fun loudnessSmoothnessWarnsOnADip() {
        assertEquals(Verdict.PASS, ArtifactMetrics.loudnessSmoothness(case.audio).verdict)
        val dipped = MetricsFixtures.mutated(case.rendered) { buf ->
            val from = buf.frames / 2
            for (ch in buf.channels) for (i in from until minOf(buf.frames, from + sr / 2)) ch[i] *= 0.1f
        }
        val metric = ArtifactMetrics.loudnessSmoothness(dipped.audio)
        assertTrue(metric.value > ArtifactMetrics.LOUDNESS_SMOOTHNESS_WARN, "20 dB dip: $metric")
        assertEquals(Verdict.WARN, metric.verdict)
    }

    // ---- the other entry points -------------------------------------------------------------------------

    @Test
    fun installChecksAreTheCheapSubset() {
        val report = ArtifactMetrics.installChecks(case.rendered, case.input)
        assertEquals(
            listOf(
                ArtifactMetrics.CLICKS, ArtifactMetrics.LEVEL_JUMP_DB, ArtifactMetrics.TRUE_PEAK_DBTP,
                ArtifactMetrics.NAN_INF, ArtifactMetrics.SILENCE_GAP_MS,
                ArtifactMetrics.SEAM_IDENTITY, ArtifactMetrics.SEAM_CORRELATION,
            ),
            report.metrics.map { it.id },
        )
        assertEquals(Verdict.PASS, report.worst)
        assertTrue(report.installable)
        val zeroed = MetricsFixtures.mutated(case.rendered) { buf ->
            for (ch in buf.channels) java.util.Arrays.fill(ch, 0, 500, 0f)
        }
        assertTrue(!ArtifactMetrics.installChecks(zeroed, case.input).installable, "a broken seam blocks the install")
    }

    @Test
    fun programOutputIsCheckedAroundItsSeams() {
        // The player's output is a program of bodies and rendered segments; here one clean segment with a seam.
        val program = case.audio
        val seam = MetricsFixtures.onsetFreeFrame(case, program.frames / 2, 80.0).toLong()
        val clean = ArtifactMetrics.evaluateProgramOutput(program, listOf(seam))
        assertEquals(0.0, clean.value(ArtifactMetrics.CLICKS), clean.summary())
        assertEquals(1.0, clean.value(ArtifactMetrics.SEAMS))
        assertEquals(Verdict.PASS, clean.worst, clean.summary())

        val spliced = program.copy()
        for (ch in spliced.channels) ch[seam.toInt()] += 0.6f
        val dirty = ArtifactMetrics.evaluateProgramOutput(spliced, listOf(seam))
        assertTrue(dirty.value(ArtifactMetrics.CLICKS)!! >= 1.0, dirty.summary())
        assertEquals(Verdict.FAIL, dirty.worst)

        // A defect far from any seam is not looked at (the player checks seams, not the whole program).
        val elsewhere = program.copy()
        val far = MetricsFixtures.onsetFreeFrame(case, program.frames / 4, 80.0)
        assertTrue(abs(far - seam.toInt()) > sr / 5)
        for (ch in elsewhere.channels) ch[far] += 0.6f
        assertEquals(0.0, ArtifactMetrics.evaluateProgramOutput(elsewhere, listOf(seam)).value(ArtifactMetrics.CLICKS))
        assertTrue(ArtifactMetrics.evaluateProgramOutput(elsewhere, listOf(seam, far.toLong())).value(ArtifactMetrics.CLICKS)!! >= 1.0)
    }

    /**
     * Regression for "clicks in the assembled program": the four-track shuffle mix reported `clicks = 2` on a
     * program whose every sample is continuous. Both clicks were the metric's own doing.
     *
     *  - The check used to slice the program at `seam ± 50 ms` and high-pass the slice from a zero filter state,
     *    so the first millisecond of every region was a step out of silence into whatever the program happened
     *    to be playing - 0.2 of first difference on material sitting at 0.35, which is a click by every
     *    criterion the detector has. Here: a steady 220 Hz tone, continuous by construction, inspected at a
     *    seam in the middle of it. The tone is in cosine phase and 220 Hz divides the 50 ms window exactly, so
     *    the frame the old slice began at sat on the tone's peak - the worst case, and 0.44 of first difference
     *    out of a zeroed filter.
     *  - The mix listed one seam frame twice (two segments met at the same output frame, with a zero-length body
     *    between them), and the same region was inspected - and the same defect counted - twice.
     */
    @Test
    fun programSeamsAreInspectedWithContextAndOnlyOnce() {
        val frames = sr * 2
        val tone = FloatArray(frames) { (0.8 * kotlin.math.cos(2.0 * Math.PI * 220.0 * it / sr)).toFloat() }
        val program = AudioBuffer.stereo(sr, tone, tone.copyOf())
        val seam = frames / 2L

        val clean = ArtifactMetrics.evaluateProgramOutput(program, listOf(seam))
        assertEquals(0.0, clean.value(ArtifactMetrics.CLICKS), "a continuous signal has no clicks: ${clean.summary()}")

        // A real discontinuity at the seam is still found, and counted once however often the seam is listed.
        val spliced = program.copy()
        for (ch in spliced.channels) ch[seam.toInt()] += 0.6f
        val once = ArtifactMetrics.evaluateProgramOutput(spliced, listOf(seam))
        assertEquals(1.0, once.value(ArtifactMetrics.CLICKS), once.summary())
        val twice = ArtifactMetrics.evaluateProgramOutput(spliced, listOf(seam, seam))
        assertEquals(1.0, twice.value(ArtifactMetrics.CLICKS), "a seam listed twice is one seam: ${twice.summary()}")
        assertEquals(1.0, twice.value(ArtifactMetrics.SEAMS))
    }

    /**
     * A source's entry point is an onset even though nothing precedes it: the detector has no history at frame 0
     * and used to skip its first blocks, which hid the one onset every render is built around - the incoming
     * track's downbeat - and let the level check report it as an artifact.
     */
    @Test
    fun aBufferThatStartsLoudStartsWithAnOnset() {
        val frames = sr / 2
        val tone = FloatArray(frames) { (0.5 * kotlin.math.sin(2.0 * Math.PI * 220.0 * it / sr)).toFloat() }
        val onsets = Signals.onsetFrames(AudioBuffer.stereo(sr, tone, tone.copyOf()))
        assertTrue(onsets.isNotEmpty() && onsets[0] == 0, "frame 0 is an onset: ${onsets.take(4)}")
        // Silence does not start with one.
        val quiet = FloatArray(frames)
        assertTrue(Signals.onsetFrames(AudioBuffer.stereo(sr, quiet, quiet.copyOf())).isEmpty())
    }

    @Test
    fun metricsWithoutSourcesSkipTheSourceOnlyChecks() {
        val report = ArtifactMetrics.evaluate(case.rendered, null)
        assertNull(report.metric(ArtifactMetrics.SEAM_IDENTITY))
        assertNull(report.metric(ArtifactMetrics.SEAM_CORRELATION))
        assertEquals(Double.NEGATIVE_INFINITY, report.value(ArtifactMetrics.TAIL_CONTAINED_DB))
        assertEquals(Verdict.PASS, report.verdict(ArtifactMetrics.TAIL_CONTAINED_DB))
        assertEquals(0, ArtifactMetrics.sourceOnsetsInOutput(case.rendered, null).size)
        // Without sources nothing excuses the drum attacks, so the level check is strictly more pessimistic.
        assertTrue(report.value(ArtifactMetrics.LEVEL_JUMP_DB)!! >= evaluate().value(ArtifactMetrics.LEVEL_JUMP_DB)!!)
    }

    @Test
    fun nearAnyFindsTheClosestExcludedFrame() {
        val sorted = intArrayOf(100, 5000, 5100, 90000)
        assertTrue(ArtifactMetrics.nearAny(sorted, 5050, 60))
        assertTrue(!ArtifactMetrics.nearAny(sorted, 5050, 40))
        assertTrue(ArtifactMetrics.nearAny(sorted, 100, 0))
        assertTrue(!ArtifactMetrics.nearAny(IntArray(0), 100, 1000))
        assertTrue(!ArtifactMetrics.nearAny(sorted, 50000, 1000))
    }

    @Test
    fun sourceOnsetsMapThroughTheSpliceContract() {
        val onsets = ArtifactMetrics.sourceOnsetsInOutput(case.rendered, case.input)
        assertTrue(onsets.isNotEmpty())
        assertTrue(onsets.all { it in 0 until case.audio.frames })
        for (i in 1 until onsets.size) assertTrue(onsets[i] >= onsets[i - 1], "sorted")
        // Output frame 0 is A's aExitFrame: an A onset there lands at 0.
        val aOnsets = Signals.onsetFrames(case.input.aAudio)
        val expected = aOnsets.filter { it - case.plan.aExitOffset in 0 until case.audio.frames }
            .map { it - case.plan.aExitOffset }
        assertTrue(expected.all { it in onsets.toList() }, "every A onset inside the segment is mapped")
        assertTrue(expected.isNotEmpty())
        assertTrue(abs(case.plan.aExitOffset) == 0, "the crossfade segment starts at aWindow.start")
    }
}
