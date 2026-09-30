package dev.muisc.cli

import dev.muisc.analysis.DefaultTrackAnalyzer
import dev.muisc.analysis.Fingerprint
import dev.muisc.analysis.TrackAnalyzer
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.SyntheticSong
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import dev.muisc.analysis.model.Mode as KeyMode
import dev.muisc.audio.synth.Mode as SynthMode

/**
 * The analysis accuracy bench behind `muisc bench analysis`: synthetic songs with known ground truth, run through
 * the real [DefaultTrackAnalyzer], scored the way beat-tracking and key-detection evaluations usually are.
 *
 * Each case is a [SyntheticSong] (kick/hat/snare/bass/pad, I–V–vi–IV or i–VI–III–VII) with a tempo from 60 to 180
 * BPM, a tonic and mode cycling through all 24 keys, 0–8 bars of pad-only intro, 0–4 bars of outro (sometimes
 * faded), sometimes leading and trailing silence, and a detune of up to ±40 cents. The detune is made by rendering
 * the song at `44100 / d` Hz and reading the samples as 44.1 kHz, which raises every frequency AND the tempo by
 * `d = 2^(cents/1200)`; the ground truth is scaled by the same factor (tempo × d, times ÷ d). |cents| < 50 keeps
 * the tonic, so the key truth does not change.
 *
 * What is measured per case (definitions, so the numbers can be compared across runs):
 *  - **tempo**: `|est − true| / true ≤ 1 %`; an **octave error** is an estimate within 3 % of 2× or ½× the truth.
 *  - **beat F-measure** (±70 ms): the estimated beats are the grid as the engine uses it — the tracked beats,
 *    extended before the first and after the last by the grid's own extrapolation ([dev.muisc.analysis.model.BeatGrid.frameOfBeat]) —
 *    over the span of the true beats (± 70 ms). They are matched to the true beats (every beat of the song, pad-only
 *    intro included) one to one within 70 ms; `P = matched / estimated`, `R = matched / true`, `F = 2PR / (P + R)`.
 *  - **downbeat phase**: each true downbeat with a matched estimated beat counts as correct when the grid marks
 *    that beat as a downbeat; the phase is right when ≥ 90 % of the matched downbeats are correct.
 *  - **key**: exact, relative (the other mode on the same Camelot number) or wrong.
 *  - **trim error**: `|trimStart − true leading silence|` in ms.
 *  - **tuning error**: `|tuningCents − true detune|` (reported, not part of "failed").
 *
 * A case is listed as a failure when the tempo is off by more than 1 %, F < 0.9, the downbeat phase is wrong,
 * the key is not exact, or the trim error exceeds [TRIM_TOLERANCE_MS].
 */
object AnalysisBench {

    const val SAMPLE_RATE = 44100
    const val BEAT_TOLERANCE_SEC = 0.070
    const val TEMPO_TOLERANCE = 0.01
    const val F_MEASURE_OK = 0.9
    const val DOWNBEAT_OK = 0.9
    const val TRIM_TOLERANCE_MS = 50.0

    /** One generated song and its ground truth (all times in seconds of the 44.1 kHz file). */
    class Case(
        val index: Int,
        val song: SyntheticSong,
        /** Detune actually applied, in cents (after rounding the render rate to whole Hz). */
        val detuneCents: Double,
        /** Rate the song was rendered at before being read as [SAMPLE_RATE]. */
        val renderRate: Int,
    ) {
        /** Speed factor of the detune: frequencies and tempo × d, times ÷ d. */
        val speed: Double get() = SAMPLE_RATE.toDouble() / renderRate
        val trueBpm: Double get() = song.bpm * speed
        val trueKey: MusicalKey get() = MusicalKey(song.tonic, if (song.mode == SynthMode.MAJOR) KeyMode.MAJOR else KeyMode.MINOR)
        val trueBeats: DoubleArray get() = song.beatTimes().map { it / speed }.toDoubleArray()
        val trueDownbeats: DoubleArray get() = song.downbeatTimes().map { it / speed }.toDoubleArray()
        val trueLeadingSilenceSec: Double get() = song.leadingSilenceSec / speed

        val name: String get() = buildString {
            append('#').append(index).append(' ')
            append(Fmt.num(song.bpm, 1)).append(" BPM ")
            append(SynthCommand.NOTES[song.tonic].replace('s', '#')).append(if (song.mode == SynthMode.MINOR) "m" else "")
            append(" i").append(song.introBars).append('o').append(song.outroBars).append(if (song.outroFade) "f" else "")
            if (song.leadingSilenceSec > 0) append(" lead ").append(Fmt.num(song.leadingSilenceSec, 1)).append('s')
            if (abs(detuneCents) >= 0.5) append(" ").append(if (detuneCents > 0) "+" else "").append(detuneCents.roundToInt()).append('c')
        }

        /** The 44.1 kHz audio the analyzer sees. */
        fun render(): AudioBuffer {
            val rendered = song.copy(sampleRate = renderRate).render()
            return AudioBuffer(SAMPLE_RATE, rendered.channels)
        }
    }

    /**
     * [count] cases from [seed]. Tonic and mode cycle through the 24 keys in order (so 24 cases cover every key
     * once); tempo, intro/outro, fade, silence and detune are drawn from [seed]. Case 0 is always undetuned with no
     * silence, so the smallest bench still has one plain song.
     */
    fun generate(count: Int, seed: Long): List<Case> {
        require(count >= 1) { "count must be at least 1" }
        val rnd = Random(seed)
        return List(count) { i ->
            val tonic = (i * 7) % 12 // circle of fifths, so consecutive cases are not neighbours on the keyboard
            val mode = if ((i / 12) % 2 == 0) (if (i % 2 == 0) SynthMode.MAJOR else SynthMode.MINOR) else (if (i % 2 == 0) SynthMode.MINOR else SynthMode.MAJOR)
            val bpm = Math.round((60.0 + rnd.nextDouble() * 120.0) * 10.0) / 10.0
            val barSec = 4 * 60.0 / bpm
            val bars = (40.0 / barSec).roundToInt().coerceIn(12, 32)
            val intro = listOf(0, 2, 4, 8)[rnd.nextInt(4)]
            val outro = listOf(0, 2, 4)[rnd.nextInt(3)]
            val fade = outro > 0 && rnd.nextBoolean()
            val plain = i == 0
            val lead = if (plain) 0.0 else listOf(0.0, 0.0, 0.5, 1.7, 3.0)[rnd.nextInt(5)]
            val trail = if (plain) 0.0 else listOf(0.0, 1.0)[rnd.nextInt(2)]
            val cents = if (plain) 0.0 else (rnd.nextDouble() * 80.0 - 40.0)
            val renderRate = Math.round(SAMPLE_RATE / 2.0.pow(cents / 1200.0)).toInt()
            val applied = 1200.0 * ln(SAMPLE_RATE.toDouble() / renderRate) / ln(2.0)
            val song = SyntheticSong(
                bpm = bpm, tonic = tonic, mode = mode, bars = bars, introBars = intro, outroBars = outro, outroFade = fade,
                sampleRate = SAMPLE_RATE, stereo = true, seed = 1000 + i,
                leadingSilenceSec = lead, trailingSilenceSec = trail,
            )
            Case(i, song, applied, renderRate)
        }
    }

    /** The scores of one case. */
    class Score(
        val case: Case,
        val estBpm: Double,
        val tempoOk: Boolean,
        val octaveError: Boolean,
        val fMeasure: Double,
        val downbeatAccuracy: Double,
        val phaseOk: Boolean,
        val estKey: MusicalKey,
        val keyExact: Boolean,
        val keyRelative: Boolean,
        val trimErrorMs: Double,
        val tuningErrorCents: Double,
        val gridConfidence: Float,
        val keyStrength: Float,
        val millis: Long,
    ) {
        val trimOk: Boolean get() = trimErrorMs <= TRIM_TOLERANCE_MS
        val failed: Boolean get() = !tempoOk || fMeasure < F_MEASURE_OK || !phaseOk || !keyExact || !trimOk
    }

    fun score(case: Case, analysis: TrackAnalysis, millis: Long): Score {
        val sr = analysis.sampleRate.toDouble()
        val grid = analysis.grid
        val estBpm = analysis.tempo.bpm
        val trueBpm = case.trueBpm
        val ratio = if (trueBpm > 0) estBpm / trueBpm else Double.NaN
        val tempoOk = abs(ratio - 1.0) <= TEMPO_TOLERANCE
        val octave = abs(ratio - 2.0) <= 0.06 || abs(ratio - 0.5) <= 0.015

        // Estimated beats: the grid as the engine uses it — tracked beats, extended before the first and after the
        // last by the grid's own extrapolation (BeatGrid.frameOfBeat) — over the span of the true beats (± tolerance).
        val truth = case.trueBeats
        val spanFrom = (truth.firstOrNull() ?: 0.0) - BEAT_TOLERANCE_SEC
        val spanTo = (truth.lastOrNull() ?: 0.0) + BEAT_TOLERANCE_SEC
        val estIndex = ArrayList<Int>()
        val estTimes = ArrayList<Double>()
        if (!grid.isEmpty) {
            val kFrom = kotlin.math.floor(grid.beatAtFrame(Math.round(spanFrom * sr))).toInt() - 1
            val kTo = kotlin.math.ceil(grid.beatAtFrame(Math.round(spanTo * sr))).toInt() + 1
            for (k in kFrom..kTo) {
                val t = grid.frameOfBeat(k.toDouble()) / sr
                if (t >= spanFrom && t <= spanTo && (estTimes.isEmpty() || t > estTimes.last())) { estIndex += k; estTimes += t }
            }
        }
        val estBeats = estTimes.toDoubleArray()
        val matchedEst = matchBeats(truth, estBeats, BEAT_TOLERANCE_SEC)
        val matched = matchedEst.count { it >= 0 }
        val precision = if (estBeats.isEmpty()) 0.0 else matched.toDouble() / estBeats.size
        val recall = if (truth.isEmpty()) 0.0 else matched.toDouble() / truth.size
        val f = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)

        // Downbeats: nearest estimated beat within the tolerance; correct when the grid calls that beat a downbeat.
        var dbMatched = 0
        var dbCorrect = 0
        for (d in case.trueDownbeats) {
            var best = -1
            var bestDist = Double.MAX_VALUE
            for (i in estBeats.indices) {
                val dist = abs(estBeats[i] - d)
                if (dist < bestDist) { bestDist = dist; best = i }
            }
            if (best >= 0 && bestDist <= BEAT_TOLERANCE_SEC) {
                dbMatched++
                if (grid.isDownbeat(estIndex[best])) dbCorrect++
            }
        }
        val dbAcc = if (dbMatched == 0) 0.0 else dbCorrect.toDouble() / dbMatched

        val est = analysis.key.key
        val exact = est == case.trueKey
        val relative = !exact && est.camelot.number == case.trueKey.camelot.number && est.mode != case.trueKey.mode
        val trimMs = abs(analysis.trimStartFrame / sr - case.trueLeadingSilenceSec) * 1000.0
        return Score(
            case, estBpm, tempoOk, octave, f, dbAcc, dbMatched > 0 && dbAcc >= DOWNBEAT_OK, est, exact, relative, trimMs,
            abs(analysis.tuningCents - case.detuneCents), grid.confidence, analysis.key.strength, millis,
        )
    }

    /**
     * One-to-one matching of [truth] to [estimated] within [tol] seconds: for each true beat, the nearest estimated
     * beat not yet taken. Returns, per true beat, the index of its estimated beat or -1. Both arrays ascending.
     */
    fun matchBeats(truth: DoubleArray, estimated: DoubleArray, tol: Double): IntArray {
        val taken = BooleanArray(estimated.size)
        val out = IntArray(truth.size) { -1 }
        var lo = 0
        for (t in truth.indices) {
            while (lo < estimated.size && estimated[lo] < truth[t] - tol) lo++
            var best = -1
            var bestDist = Double.MAX_VALUE
            var j = lo
            while (j < estimated.size && estimated[j] <= truth[t] + tol) {
                val dist = abs(estimated[j] - truth[t])
                if (!taken[j] && dist < bestDist) { bestDist = dist; best = j }
                j++
            }
            if (best >= 0) { taken[best] = true; out[t] = best }
        }
        return out
    }

    /** Analyses every case with [analyzer]; [onCase] sees each score as it is produced. */
    fun run(cases: List<Case>, analyzer: TrackAnalyzer = DefaultTrackAnalyzer(), onCase: (Score) -> Unit = {}): List<Score> =
        cases.map { c ->
            val audio = c.render()
            val started = System.nanoTime()
            val analysis = analyzer.analyze(audio, "bench:${c.index}", Fingerprint.ofBuffer(audio))
            score(c, analysis, (System.nanoTime() - started) / 1_000_000).also(onCase)
        }

    /** Aggregate counts of a run (what the floor test asserts on). */
    class Summary(scores: List<Score>) {
        val songs = scores.size
        val tempoOk = scores.count { it.tempoOk }
        val octaveErrors = scores.count { it.octaveError }
        val beatFOk = scores.count { it.fMeasure >= F_MEASURE_OK }
        val meanF = scores.map { it.fMeasure }.average()
        val minF = scores.minOfOrNull { it.fMeasure } ?: 0.0
        val phaseOk = scores.count { it.phaseOk }
        val keyExact = scores.count { it.keyExact }
        val keyRelative = scores.count { it.keyRelative }
        val keyWrong = songs - keyExact - keyRelative
        val trimOk = scores.count { it.trimOk }
        val meanTrimMs = scores.map { it.trimErrorMs }.average()
        val maxTrimMs = scores.maxOfOrNull { it.trimErrorMs } ?: 0.0
        val meanTuningErrorCents = scores.map { it.tuningErrorCents }.average()
        val failed = scores.count { it.failed }
    }

    fun report(scores: List<Score>, seed: Long, showAll: Boolean): String = buildString {
        val s = Summary(scores)
        fun frac(n: Int) = "$n/${s.songs} (${Fmt.pct(n.toDouble() / max(1, s.songs))})"
        append("analysis bench: ").append(s.songs).append(" synthetic songs, seed ").append(seed).append('\n')
        append(Fmt.table(listOf(
            listOf("tempo within 1 %", frac(s.tempoOk)),
            listOf("octave errors", frac(s.octaveErrors)),
            listOf("beat F-measure (±70 ms)", "mean ${Fmt.num(s.meanF, 3)}, min ${Fmt.num(s.minF, 3)}; F ≥ $F_MEASURE_OK in ${frac(s.beatFOk)}"),
            listOf("downbeat phase", frac(s.phaseOk)),
            listOf("key exact", frac(s.keyExact)),
            listOf("key relative", frac(s.keyRelative)),
            listOf("key wrong", frac(s.keyWrong)),
            listOf("trim error", "mean ${Fmt.num(s.meanTrimMs, 1)} ms, max ${Fmt.num(s.maxTrimMs, 1)} ms; ≤ ${TRIM_TOLERANCE_MS.toInt()} ms in ${frac(s.trimOk)}"),
            listOf("tuning error", "mean ${Fmt.num(s.meanTuningErrorCents, 1)} cents"),
        ), "  "))
        append('\n')
        val rows = if (showAll) scores else scores.filter { it.failed }
        if (rows.isEmpty()) {
            append("\nno failing case\n")
        } else {
            append('\n').append(if (showAll) "all cases" else "failing cases (${rows.size})").append(":\n")
            append(Fmt.table(listOf(listOf("case", "true bpm", "est bpm", "tempo", "beat F", "downbeats", "key true/est", "trim ms", "tuning ±c", "grid conf", "ms")) + rows.map { r ->
                listOf(
                    r.case.name,
                    Fmt.num(r.case.trueBpm, 2),
                    Fmt.num(r.estBpm, 2),
                    if (r.tempoOk) "ok" else if (r.octaveError) "OCTAVE" else "OFF",
                    Fmt.num(r.fMeasure, 3) + if (r.fMeasure < F_MEASURE_OK) " LOW" else "",
                    Fmt.num(r.downbeatAccuracy, 2) + if (r.phaseOk) "" else " WRONG",
                    "${r.case.trueKey.shortName}/${r.estKey.shortName}" + if (r.keyExact) "" else if (r.keyRelative) " REL" else " WRONG",
                    Fmt.num(r.trimErrorMs, 1) + if (r.trimOk) "" else " HIGH",
                    Fmt.num(r.tuningErrorCents, 1),
                    Fmt.num(r.gridConfidence.toDouble(), 2),
                    "${r.millis}",
                )
            }, "  "))
            append('\n')
        }
    }
}
