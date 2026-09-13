package dev.muisc.analysis.features

import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.BeatGrid
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fft.Stft
import dev.muisc.dsp.fft.StftFrameSink
import dev.muisc.dsp.filter.MultibandCrossover
import dev.muisc.dsp.hpss.Hpss
import dev.muisc.dsp.hpss.HpssResult
import dev.muisc.dsp.resample.Resampler
import dev.muisc.dsp.window.Window
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Per-bar descriptors ([BarFeatures]) of a track on its [BeatGrid].
 *
 * **Bars.** Bar `b` starts at the grid's downbeat `beatOfBar(b)` (bar 0 = the first downbeat; beats before
 * it are ignored) and ends at the next downbeat (extrapolated beyond the last tracked beat and clipped to the
 * end of the audio, so a partial last bar is included). Only bars whose start downbeat is a tracked beat and
 * lies inside the audio count; [barBoundaries] exposes the frame boundaries. An empty grid yields empty
 * features.
 *
 * **Features** (all arrays have `barCount` entries; the analysis runs on a 22.05 kHz downmix — mono, or L/R
 * for stereo input — while boundaries come from the engine-rate grid):
 *  - `energy`: bar RMS of the mono mix, normalised so the loudest bar is 1 (0..1);
 *  - `sub` / `bass` / `mid` / `high`: energy **shares** (fractions summing to 1 per bar; all 0 for a silent bar)
 *    of the four bands of a Linkwitz–Riley LR4 [MultibandCrossover] at [crossoverHz] = 60 / 250 / 4000 Hz;
 *  - `percussiveness`: 0..1 `percussive / (harmonic + percussive)` ratio of the [Hpss] components, averaged
 *    over the STFT frames centred inside the bar. The ratio is computed per frame on **log-compressed
 *    magnitude spectra** of the two components, `sum_k ln(1 + |P_k| / theta) / sum_k (ln(1 + |H_k| / theta)
 *    + ln(1 + |P_k| / theta))` with `theta` = [floorDb] (-60 dB) below the track's peak (STFT geometry = the
 *    separation's frame / hop, Hann window). The plain energy ratio ([HpssResult.percussiveness]) is dominated
 *    by the few loud low bins — a bass line with sharp attacks reads as "percussive" as a full drum kit —
 *    whereas the compressed ratio counts bins roughly equally above the floor, so broadband hats / snares
 *    register. Calibration on the synthetic song: pad-only bars ≈ 0.25–0.35 (HPSS leakage of note
 *    attacks), bass + pad ≈ 0.4, full kit over bass + pad ≈ 0.55–0.6; silence = 0, a drum-only bar → 1.
 *    Consumers should threshold relative to the track (e.g. against the loudest bars), not absolutely;
 *  - `vocalActivity`: 0..1 **heuristic** — stereo: centre-channel dominance
 *    `(E_mid - E_side) / (E_mid + E_side)` of the *harmonic* component band-limited to [vocalBandHz] =
 *    200 Hz .. 4 kHz (`mid = (L+R)/2`, `side = (L-R)/2`), gated by `1 - spectral flatness` of that band; mono:
 *    the harmonic band's energy share of the whole harmonic signal, gated the same way. Without a learned
 *    model this mostly measures "sustained, centred, tonal mid-band content", which also fires on centred lead
 *    synths and misses wide (detuned / doubled) vocals; treat it as weak evidence only.
 *
 * Pass a precomputed [HpssResult] (of this very buffer from frame 0, at any sample rate / channel count) to
 * avoid a second separation; otherwise [hpss] (frame 1024 / hop 256 at 22.05 kHz, time median 31 frames ≈
 * 360 ms, frequency median 17 bins ≈ 366 Hz) is run on the downmix.
 */
class BarFeatureExtractor(
    val hpss: Hpss = Hpss(frameSize = 1024, hop = 256, harmonicKernel = 31, percussiveKernel = 17),
    val resampler: Resampler = Resampler(),
    val crossoverHz: DoubleArray = doubleArrayOf(60.0, 250.0, 4000.0),
    val vocalBandHz: DoubleArray = doubleArrayOf(200.0, 4000.0),
    /** Floor of the log-compressed percussiveness ratio, dB below the track's peak. */
    val floorDb: Float = -60f,
) {
    init { require(crossoverHz.size == 3 && vocalBandHz.size == 2 && floorDb < 0f) }

    /**
     * Engine-frame boundaries of the bars of [grid] within [totalFrames] frames: `barCount + 1` entries,
     * `[i]` = start of bar i, last = end of the final (possibly partial) bar. Empty when there are no bars.
     */
    fun barBoundaries(grid: BeatGrid, totalFrames: Long): LongArray {
        if (grid.isEmpty || totalFrames <= 0) return LongArray(0)
        val starts = ArrayList<Long>()
        var bar = 0
        var lastBar = -1
        // beatOfBar(0) = downbeatPhase >= 0: bars before the first downbeat (negative beat indices) never appear.
        while (true) {
            val beat = grid.beatOfBar(bar)
            if (beat < 0 || beat >= grid.beatCount) break
            val f = grid.beatFrames[beat]
            if (f >= totalFrames) break
            if (f >= 0) { starts.add(f); lastBar = bar }
            bar++
        }
        if (starts.isEmpty()) return LongArray(0)
        val end = min(totalFrames, grid.frameOfBeat(grid.beatOfBar(lastBar + 1).toDouble()).coerceAtLeast(starts.last() + 1))
        val out = LongArray(starts.size + 1)
        for (i in starts.indices) out[i] = starts[i]
        out[starts.size] = end
        return out
    }

    fun extract(audio: AudioBuffer, grid: BeatGrid, hpssResult: HpssResult? = null): BarFeatures {
        val bounds = barBoundaries(grid, audio.frames.toLong())
        val bars = bounds.size - 1
        if (bars <= 0 || audio.frames == 0) return BarFeatures()
        val engineRate = audio.sampleRate
        val rate = FeatureRate.SAMPLE_RATE

        // Analysis signal: mono, or L/R for stereo, at the analysis rate.
        val srcChannels = if (audio.channelCount >= 2) 2 else 1
        val work = AudioBuffer(rate, Array(srcChannels) { c ->
            val src = if (audio.channelCount >= 2) audio[c] else audio[0]
            if (engineRate == rate) src.copyOf() else resampler.resample(src, engineRate, rate)
        })
        val mono = work.mono()
        val n = mono.size
        val b = IntArray(bounds.size) { Math.round(bounds[it] * rate.toDouble() / engineRate).toInt().coerceIn(0, n) }

        // Energy (RMS per bar, normalised).
        val energy = FloatArray(bars)
        var maxRms = 0f
        for (i in 0 until bars) {
            val rms = sqrt(meanSquare(mono, b[i], b[i + 1])).toFloat()
            energy[i] = rms
            if (rms > maxRms) maxRms = rms
        }
        if (maxRms > 0f) for (i in 0 until bars) energy[i] /= maxRms

        // Band shares.
        val bandsOut = MultibandCrossover(rate, 1, crossoverHz).split(AudioBuffer.mono(rate, mono))
        val sub = FloatArray(bars); val bass = FloatArray(bars); val mid = FloatArray(bars); val high = FloatArray(bars)
        for (i in 0 until bars) {
            val e = DoubleArray(4) { sumSquare(bandsOut[it][0], b[i], b[i + 1]) }
            val total = e[0] + e[1] + e[2] + e[3]
            if (total > 0.0) {
                sub[i] = (e[0] / total).toFloat(); bass[i] = (e[1] / total).toFloat()
                mid[i] = (e[2] / total).toFloat(); high[i] = (e[3] / total).toFloat()
            }
        }

        // HPSS components.
        val sep = hpssResult ?: hpss.separate(work)
        val perc = percussiveness(sep, bounds, engineRate, bars)
        val vocal = vocalActivity(sep.harmonic, bounds, engineRate, bars)
        return BarFeatures(energy, sub, bass, mid, high, perc, vocal)
    }

    /** Per-bar log-compressed P / (H + P) ratio (see class doc). */
    private fun percussiveness(sep: HpssResult, bounds: LongArray, engineRate: Int, bars: Int): FloatArray {
        val out = FloatArray(bars)
        val h = sep.harmonic.mono()
        val p = sep.percussive.mono()
        val rate = sep.sampleRate
        if (h.isEmpty()) return out
        val frame = sep.frameSize; val hop = sep.hop
        // Floor relative to the mix peak, in unscaled Hann-STFT magnitude units (peak sine reads A * frame / 4).
        var peak = 0f
        for (i in h.indices) { val v = h[i] + p[i]; val a = if (v < 0) -v else v; if (a > peak) peak = a }
        if (peak <= 0f) return out
        val theta = peak * frame / 4.0 * 10.0.pow(floorDb / 20.0)
        val stft = Stft(frame, hop, Window.hann(frame), center = true)
        val frames = stft.frameCount(h.size)
        val hSum = DoubleArray(frames)
        val pSum = DoubleArray(frames)
        val bins = stft.bins
        val invTheta = 1.0 / theta
        fun pass(x: FloatArray, dst: DoubleArray) {
            stft.reset()
            val sink = StftFrameSink { t, re, im ->
                var s = 0.0
                for (k in 1 until bins) { val r = re[k].toDouble(); val i = im[k].toDouble(); s += ln(1.0 + sqrt(r * r + i * i) * invTheta) }
                if (t < frames) dst[t] = s
            }
            stft.process(x, 0, x.size, sink)
            stft.flush(sink)
        }
        pass(h, hSum)
        pass(p, pSum)
        for (i in 0 until bars) {
            val startSec = bounds[i].toDouble() / engineRate
            val endSec = bounds[i + 1].toDouble() / engineRate
            var acc = 0.0; var cnt = 0
            var t = Math.ceil(startSec * rate / hop).toInt().coerceAtLeast(0)
            while (t < frames && t.toDouble() * hop / rate < endSec) {
                val d = hSum[t] + pSum[t]
                if (d > 1e-9) { acc += pSum[t] / d; cnt++ }
                t++
            }
            out[i] = if (cnt > 0) (acc / cnt).toFloat().coerceIn(0f, 1f) else 0f
        }
        return out
    }

    private fun vocalActivity(harmonic: AudioBuffer, bounds: LongArray, engineRate: Int, bars: Int): FloatArray {
        val out = FloatArray(bars)
        val hr = harmonic.sampleRate
        val n = harmonic.frames
        if (n == 0) return out
        val hb = IntArray(bounds.size) { Math.round(bounds[it] * hr.toDouble() / engineRate).toInt().coerceIn(0, n) }
        val stereo = harmonic.channelCount >= 2
        val src = if (harmonic.channelCount > 2) AudioBuffer.stereo(hr, harmonic[0], harmonic[1]) else harmonic
        val band = MultibandCrossover(hr, src.channelCount, doubleArrayOf(vocalBandHz[0], vocalBandHz[1])).split(src)[1]
        // Spectral flatness of the band-limited harmonic mono per bar.
        val bandMono = band.mono()
        val frame = 1024; val hop = 512
        val stft = Stft(frame, hop, Window.hann(frame), center = true)
        val kLo = Math.ceil(vocalBandHz[0] * frame / hr).toInt().coerceAtLeast(1)
        val kHi = Math.floor(vocalBandHz[1] * frame / hr).toInt().coerceAtMost(frame / 2)
        val nk = (kHi - kLo + 1).coerceAtLeast(1)
        val flatnessAcc = Array(bars) { DoubleArray(nk) }
        val frameCount = IntArray(bars)
        var barIdx = 0
        val sink = StftFrameSink { t, re, im ->
            val sample = t * hop
            while (barIdx < bars && sample >= hb[barIdx + 1]) barIdx++
            if (barIdx < bars && sample >= hb[barIdx]) {
                val acc = flatnessAcc[barIdx]
                for (k in kLo..kHi) { val r = re[k]; val i = im[k]; acc[k - kLo] += (r * r + i * i).toDouble() }
                frameCount[barIdx]++
            }
        }
        stft.process(bandMono, 0, bandMono.size, sink)
        stft.flush(sink)
        for (i in 0 until bars) {
            val gate = if (frameCount[i] > 0) 1.0 - flatness(flatnessAcc[i]) else 0.0
            val base = if (stereo) {
                val l = band[0]; val r = band[1]
                var eMid = 0.0; var eSide = 0.0
                for (s in hb[i] until hb[i + 1]) {
                    val m = (l[s] + r[s]) * 0.5; val d = (l[s] - r[s]) * 0.5
                    eMid += m.toDouble() * m; eSide += d.toDouble() * d
                }
                if (eMid + eSide > 0.0) ((eMid - eSide) / (eMid + eSide)).coerceIn(0.0, 1.0) else 0.0
            } else {
                val eBand = sumSquare(band[0], hb[i], hb[i + 1])
                val eAll = sumSquare(harmonic[0], hb[i], hb[i + 1])
                if (eAll > 0.0) (eBand / eAll).coerceIn(0.0, 1.0) else 0.0
            }
            out[i] = (base * gate).toFloat().coerceIn(0f, 1f)
        }
        return out
    }

    /** Geometric / arithmetic mean of a power spectrum (0 = pure line spectrum, 1 = flat). */
    private fun flatness(p: DoubleArray): Double {
        var logSum = 0.0; var sum = 0.0
        for (v in p) { logSum += ln(v + EPS); sum += v }
        val geo = exp(logSum / p.size)
        val arith = sum / p.size + EPS
        return (geo / arith).coerceIn(0.0, 1.0)
    }

    private fun meanSquare(x: FloatArray, from: Int, to: Int): Double = if (to <= from) 0.0 else sumSquare(x, from, to) / (to - from)

    private fun sumSquare(x: FloatArray, from: Int, to: Int): Double {
        var acc = 0.0
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(x.size)) { val v = x[i].toDouble(); acc += v * v }
        return acc
    }

    private companion object { const val EPS = 1e-12 }
}
