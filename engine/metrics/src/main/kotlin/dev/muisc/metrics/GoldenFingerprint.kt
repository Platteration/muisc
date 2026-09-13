package dev.muisc.metrics

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fft.RealFft
import dev.muisc.dsp.filter.MultibandCrossover
import dev.muisc.dsp.window.Window
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionPlan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The stored form of a reference ("golden") render (DESIGN.md §9, "Golden renders").
 *
 * A fingerprint is small (a few tens of kB of JSON for a 10 s segment), text-only — no FLAC, no LFS — and
 * describes a render at four levels of strictness, so a comparison can say *how* a render changed:
 *
 *  - [planJson]: the [TransitionPlan] as JSON. A plan change means the strategy decided differently; the audio
 *    comparison below is then meaningless and the diff says so first.
 *  - [rmsEnvelope20ms]: dBFS RMS of the mono mix per 20 ms block — the level ride of the transition.
 *  - [bandEnvelopes]: the same envelope per band of a 60 / 250 / 4000 Hz LR4 crossover (`[band][block]`), which
 *    catches "the bass swap happens 200 ms late" without caring about the exact waveform.
 *  - [logSpec64Per250ms]: 64 log-spaced band magnitudes in dB per 250 ms (`[frame][band]`) — the timbral
 *    fingerprint, tolerant to resampling and decoder differences across platforms.
 *  - [pcm16Sha256]: SHA-256 of the render quantised to interleaved 16-bit little-endian PCM. Bit-exact, and
 *    therefore only required in strict mode (`-Pmuisc.goldenStrict=true`), since MP3/AAC decode differently on
 *    different platforms.
 *  - [metrics]: [ArtifactMetrics.evaluate] of the render, so a regression that only shows up as "now it clicks"
 *    is caught even when the envelopes still match.
 */
@Serializable
data class GoldenFingerprint(
    val planJson: String,
    val rmsEnvelope20ms: FloatArray,
    val bandEnvelopes: Array<FloatArray>,
    val logSpec64Per250ms: Array<FloatArray>,
    val pcm16Sha256: String,
    val metrics: MetricsReport,
) {
    /** The plan this fingerprint was taken from. */
    fun plan(): TransitionPlan = JSON.decodeFromString(TransitionPlan.serializer(), planJson)

    fun toJson(pretty: Boolean = false): String =
        (if (pretty) PRETTY_JSON else JSON).encodeToString(serializer(), this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GoldenFingerprint) return false
        return planJson == other.planJson &&
            rmsEnvelope20ms.contentEquals(other.rmsEnvelope20ms) &&
            bandEnvelopes.contentDeepEquals(other.bandEnvelopes) &&
            logSpec64Per250ms.contentDeepEquals(other.logSpec64Per250ms) &&
            pcm16Sha256 == other.pcm16Sha256 &&
            metrics == other.metrics
    }

    override fun hashCode(): Int {
        var h = planJson.hashCode()
        h = 31 * h + rmsEnvelope20ms.contentHashCode()
        h = 31 * h + bandEnvelopes.contentDeepHashCode()
        h = 31 * h + logSpec64Per250ms.contentDeepHashCode()
        h = 31 * h + pcm16Sha256.hashCode()
        h = 31 * h + metrics.hashCode()
        return h
    }

    override fun toString(): String =
        "GoldenFingerprint(${rmsEnvelope20ms.size} blocks, ${logSpec64Per250ms.size} spectra, ${metrics.worst}, ${pcm16Sha256.take(12)})"

    companion object {
        /** Envelope block length. */
        const val ENVELOPE_MS = 20.0
        /** Crossover frequencies of [bandEnvelopes] (4 bands). */
        val BAND_EDGES_HZ: DoubleArray = doubleArrayOf(60.0, 250.0, 4000.0)
        const val BANDS = 4
        /** Time grid of the log-spectral fingerprint. */
        const val SPECTRUM_MS = 250.0
        const val SPECTRUM_BANDS = 64
        const val SPECTRUM_FFT = 2048
        const val SPECTRUM_LOW_HZ = 20.0
        const val SPECTRUM_HIGH_HZ = 20000.0

        val JSON: Json = Json {
            allowSpecialFloatingPointValues = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        val PRETTY_JSON: Json = Json(JSON) { prettyPrint = true }

        fun fromJson(json: String): GoldenFingerprint = JSON.decodeFromString(serializer(), json)

        /** Fingerprint of [rendered] (metrics computed without sources: [ArtifactMetrics.evaluate] with null input). */
        fun of(rendered: RenderedTransition): GoldenFingerprint = of(rendered, ArtifactMetrics.evaluate(rendered, null))

        /** Fingerprint of [rendered] with an already-computed [metrics] report (e.g. one that saw the sources). */
        fun of(rendered: RenderedTransition, metrics: MetricsReport): GoldenFingerprint {
            val audio = rendered.audio
            return GoldenFingerprint(
                planJson = JSON.encodeToString(TransitionPlan.serializer(), rendered.plan),
                rmsEnvelope20ms = rmsEnvelope(audio),
                bandEnvelopes = bandEnvelopes(audio),
                logSpec64Per250ms = logSpectra(audio),
                pcm16Sha256 = pcm16Sha256(audio),
                metrics = metrics,
            )
        }

        /** dBFS RMS of the mono mix per [ENVELOPE_MS] block. */
        fun rmsEnvelope(audio: AudioBuffer): FloatArray {
            val block = Signals.msFrames(ENVELOPE_MS, audio.sampleRate)
            val db = Signals.blockRmsDb(Signals.mono(audio), block)
            return FloatArray(db.size) { db[it].toFloat() }
        }

        /** [rmsEnvelope] per band of the [BAND_EDGES_HZ] LR4 crossover. */
        fun bandEnvelopes(audio: AudioBuffer): Array<FloatArray> {
            if (audio.frames == 0) return Array(BANDS) { FloatArray(0) }
            val nyquist = audio.sampleRate / 2.0
            val edges = BAND_EDGES_HZ.filter { it < nyquist }.toDoubleArray()
            if (edges.isEmpty()) return Array(BANDS) { rmsEnvelope(audio) }
            val split = MultibandCrossover(audio.sampleRate, audio.channelCount, edges).split(audio)
            return Array(BANDS) { b -> rmsEnvelope(split[min(b, split.size - 1)]) }
        }

        /**
         * 64 log-spaced band magnitudes in dB per [SPECTRUM_MS] chunk (`[chunk][band]`): inside each chunk the
         * power spectra of 2048-sample Hann windows (hop 1024) are averaged, then the bins are aggregated into
         * bands log-spaced from [SPECTRUM_LOW_HZ] to min([SPECTRUM_HIGH_HZ], Nyquist). Partial trailing chunks
         * are dropped so the grid is the same for any two renders of the same length.
         */
        fun logSpectra(audio: AudioBuffer): Array<FloatArray> {
            val sr = audio.sampleRate
            val chunk = Signals.msFrames(SPECTRUM_MS, sr)
            val n = SPECTRUM_FFT
            if (audio.frames < chunk || chunk < n) return emptyArray()
            val x = Signals.mono(audio)
            val chunks = x.size / chunk
            val window = Window.hann(n)
            val fft = RealFft(n)
            val re = FloatArray(fft.bins)
            val im = FloatArray(fft.bins)
            val frame = FloatArray(n)
            val power = DoubleArray(fft.bins)
            val edges = bandEdgeBins(sr, n, fft.bins)
            val out = Array(chunks) { FloatArray(SPECTRUM_BANDS) }
            for (c in 0 until chunks) {
                java.util.Arrays.fill(power, 0.0)
                var windows = 0
                var pos = c * chunk
                val end = (c + 1) * chunk
                while (pos + n <= end) {
                    for (i in 0 until n) frame[i] = x[pos + i] * window[i]
                    fft.forward(frame, re, im)
                    for (k in 0 until fft.bins) power[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                    windows++
                    pos += n / 2
                }
                if (windows == 0) continue
                val scale = 1.0 / (windows.toDouble() * n * n)
                val row = out[c]
                for (b in 0 until SPECTRUM_BANDS) {
                    val from = edges[b]
                    val to = max(edges[b + 1], from + 1)
                    var acc = 0.0
                    for (k in from until min(to, fft.bins)) acc += power[k]
                    val mean = acc * scale / (min(to, fft.bins) - from).coerceAtLeast(1)
                    row[b] = (if (mean <= 1e-20) Signals.DB_FLOOR else max(Signals.DB_FLOOR, 10.0 * log10(mean))).toFloat()
                }
            }
            return out
        }

        /** Bin index of each of the 65 log-spaced band edges. */
        fun bandEdgeBins(sampleRate: Int, fftSize: Int, bins: Int): IntArray {
            val hi = min(SPECTRUM_HIGH_HZ, sampleRate / 2.0)
            val lo = min(SPECTRUM_LOW_HZ, hi / 2.0)
            val ratio = hi / lo
            val out = IntArray(SPECTRUM_BANDS + 1)
            for (b in 0..SPECTRUM_BANDS) {
                val f = lo * ratio.pow(b.toDouble() / SPECTRUM_BANDS)
                out[b] = (f * fftSize / sampleRate).toInt().coerceIn(0, bins - 1)
            }
            for (b in 1..SPECTRUM_BANDS) if (out[b] <= out[b - 1]) out[b] = min(out[b - 1] + 1, bins - 1)
            return out
        }

        /** SHA-256 (lowercase hex) of the render as interleaved 16-bit little-endian PCM. */
        fun pcm16Sha256(audio: AudioBuffer): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val channels = audio.channelCount
            val buf = ByteArray(2 * channels * CHUNK_FRAMES)
            var pos = 0
            while (pos < audio.frames) {
                val n = min(CHUNK_FRAMES, audio.frames - pos)
                var j = 0
                for (i in 0 until n) {
                    for (c in 0 until channels) {
                        val v = audio[c][pos + i]
                        val q = if (v.isNaN()) 0 else Math.round((v.coerceIn(-1f, 1f) * 32767f)).toInt()
                        buf[j++] = (q and 0xFF).toByte()
                        buf[j++] = ((q shr 8) and 0xFF).toByte()
                    }
                }
                digest.update(buf, 0, j)
                pos += n
            }
            val bytes = digest.digest()
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) { val v = b.toInt() and 0xFF; sb.append(HEX[v ushr 4]).append(HEX[v and 0xF]) }
            return sb.toString()
        }

        private const val CHUNK_FRAMES = 4096
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
