package dev.muisc.metrics

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** One metric that got worse between the golden render and the current one. */
data class MetricRegression(
    val id: String,
    val goldenVerdict: Verdict,
    val actualVerdict: Verdict,
    val goldenValue: Double,
    val actualValue: Double,
) {
    override fun toString(): String =
        "$id ${MetricsReport.format(goldenValue)} $goldenVerdict -> ${MetricsReport.format(actualValue)} $actualVerdict"
}

/**
 * The outcome of comparing a stored [GoldenFingerprint] with a fresh one. [matches] is the answer a test
 * asserts on; [issues] is why it failed, in the order they were checked, and [summary] prints the whole thing.
 */
data class GoldenDiff(
    val planEqual: Boolean,
    /** RMSE of the 20 ms dBFS envelopes. */
    val rmsEnvelopeRmseDb: Double,
    /** RMSE per band of the 4 band envelopes. */
    val bandEnvelopeRmseDb: DoubleArray,
    /** RMSE and worst single-band difference of the 64-band log spectra. */
    val spectrumRmseDb: Double,
    val spectrumMaxDiffDb: Double,
    val pcm16HashEqual: Boolean,
    val metricRegressions: List<MetricRegression>,
    val issues: List<String>,
    val strict: Boolean,
) {
    /** True when nothing exceeded its tolerance. */
    val matches: Boolean get() = issues.isEmpty()

    fun summary(): String = buildString {
        append(if (matches) "golden MATCH" else "golden MISMATCH").append(if (strict) " (strict)" else "")
        append("\n  plan: ").append(if (planEqual) "equal" else "DIFFERENT")
        append("\n  rms envelope RMSE: ").append(MetricsReport.format(rmsEnvelopeRmseDb)).append(" dB (tol ")
            .append(GoldenCompare.ENVELOPE_RMSE_DB).append(')')
        append("\n  band envelope RMSE: ")
        for (i in bandEnvelopeRmseDb.indices) {
            if (i > 0) append(", ")
            append(GoldenCompare.BAND_NAMES.getOrElse(i) { "band$i" }).append(' ').append(MetricsReport.format(bandEnvelopeRmseDb[i]))
        }
        append("\n  log spectrum: RMSE ").append(MetricsReport.format(spectrumRmseDb)).append(" dB, max ")
            .append(MetricsReport.format(spectrumMaxDiffDb)).append(" dB (tol ").append(GoldenCompare.SPECTRUM_TOLERANCE_DB).append(')')
        append("\n  pcm16 sha256: ").append(if (pcm16HashEqual) "equal" else "different")
        if (metricRegressions.isEmpty()) append("\n  metrics: no regression")
        else for (r in metricRegressions) append("\n  metric regression: ").append(r)
        for (i in issues) append("\n  ISSUE: ").append(i)
    }

    override fun toString(): String = summary()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GoldenDiff) return false
        return planEqual == other.planEqual && rmsEnvelopeRmseDb == other.rmsEnvelopeRmseDb &&
            bandEnvelopeRmseDb.contentEquals(other.bandEnvelopeRmseDb) && spectrumRmseDb == other.spectrumRmseDb &&
            spectrumMaxDiffDb == other.spectrumMaxDiffDb && pcm16HashEqual == other.pcm16HashEqual &&
            metricRegressions == other.metricRegressions && issues == other.issues && strict == other.strict
    }

    override fun hashCode(): Int {
        var h = planEqual.hashCode()
        h = 31 * h + rmsEnvelopeRmseDb.hashCode()
        h = 31 * h + bandEnvelopeRmseDb.contentHashCode()
        h = 31 * h + spectrumRmseDb.hashCode()
        h = 31 * h + spectrumMaxDiffDb.hashCode()
        h = 31 * h + pcm16HashEqual.hashCode()
        h = 31 * h + metricRegressions.hashCode()
        h = 31 * h + issues.hashCode()
        h = 31 * h + strict.hashCode()
        return h
    }
}

/**
 * Compares a render against its golden fingerprint (DESIGN.md §9).
 *
 * Default mode: the plan must be equal, every envelope RMSE must stay under [ENVELOPE_RMSE_DB], the log-spectral
 * fingerprint within [SPECTRUM_TOLERANCE_DB], and no metric may be worse than it was. Strict mode
 * (`-Pmuisc.goldenStrict=true`) additionally requires the PCM16 hash, which only holds within one platform
 * (DESIGN.md §9, "Determinism policy").
 *
 * Envelopes of different lengths are compared over their common part and the length difference is reported as
 * an issue of its own, so a render that got longer is not also drowned in envelope noise.
 */
object GoldenCompare {
    /** Envelope tolerance: 0.5 dB RMS error over the whole segment. */
    const val ENVELOPE_RMSE_DB = 0.5
    /** Log-spectral fingerprint tolerance, in dB RMS error. */
    const val SPECTRUM_TOLERANCE_DB = 1.0
    /** Envelope lengths may differ by at most this many blocks (20 ms each) before it counts as an issue. */
    const val ENVELOPE_LENGTH_SLACK = 1

    val BAND_NAMES = listOf("sub", "bass", "mid", "high")

    fun compare(golden: GoldenFingerprint, actual: GoldenFingerprint, strict: Boolean = false): GoldenDiff {
        val issues = ArrayList<String>()

        val planEqual = golden.planJson == actual.planJson
        if (!planEqual) issues += "plan differs from the golden plan"

        val rmsRmse = rmse(golden.rmsEnvelope20ms, actual.rmsEnvelope20ms)
        if (lengthGap(golden.rmsEnvelope20ms.size, actual.rmsEnvelope20ms.size) > ENVELOPE_LENGTH_SLACK) {
            issues += "rms envelope length ${actual.rmsEnvelope20ms.size} != golden ${golden.rmsEnvelope20ms.size} blocks"
        }
        if (rmsRmse > ENVELOPE_RMSE_DB) {
            issues += "rms envelope RMSE ${MetricsReport.format(rmsRmse)} dB > $ENVELOPE_RMSE_DB dB"
        }

        val bands = min(golden.bandEnvelopes.size, actual.bandEnvelopes.size)
        val bandRmse = DoubleArray(bands) { rmse(golden.bandEnvelopes[it], actual.bandEnvelopes[it]) }
        for (b in 0 until bands) {
            if (bandRmse[b] > ENVELOPE_RMSE_DB) {
                issues += "${BAND_NAMES.getOrElse(b) { "band$b" }} envelope RMSE ${MetricsReport.format(bandRmse[b])} dB > $ENVELOPE_RMSE_DB dB"
            }
        }

        val spec = spectrumDiff(golden.logSpec64Per250ms, actual.logSpec64Per250ms)
        if (golden.logSpec64Per250ms.size != actual.logSpec64Per250ms.size) {
            issues += "log spectrum has ${actual.logSpec64Per250ms.size} frames, golden has ${golden.logSpec64Per250ms.size}"
        }
        if (spec[0] > SPECTRUM_TOLERANCE_DB) {
            issues += "log spectrum RMSE ${MetricsReport.format(spec[0])} dB > $SPECTRUM_TOLERANCE_DB dB"
        }

        val hashEqual = golden.pcm16Sha256 == actual.pcm16Sha256
        if (strict && !hashEqual) issues += "pcm16 sha256 differs (strict mode)"

        val regressions = regressions(golden.metrics, actual.metrics)
        for (r in regressions) issues += "metric regression: $r"

        return GoldenDiff(planEqual, rmsRmse, bandRmse, spec[0], spec[1], hashEqual, regressions, issues, strict)
    }

    /** Metrics whose verdict got worse, plus metrics that fail now and were not in the golden report. */
    fun regressions(golden: MetricsReport, actual: MetricsReport): List<MetricRegression> {
        val out = ArrayList<MetricRegression>()
        for (m in actual.metrics) {
            val g = golden.metric(m.id)
            if (g == null) {
                if (m.verdict != Verdict.PASS) out += MetricRegression(m.id, Verdict.PASS, m.verdict, Double.NaN, m.value)
            } else if (m.verdict.worseThan(g.verdict)) {
                out += MetricRegression(m.id, g.verdict, m.verdict, g.value, m.value)
            }
        }
        return out
    }

    /** Root-mean-square difference over the common prefix; 0.0 when either side is empty. */
    fun rmse(golden: FloatArray, actual: FloatArray): Double {
        val n = min(golden.size, actual.size)
        if (n == 0) return 0.0
        var acc = 0.0
        var counted = 0
        for (i in 0 until n) {
            val d = (golden[i] - actual[i]).toDouble()
            if (!d.isFinite()) continue
            acc += d * d
            counted++
        }
        return if (counted == 0) 0.0 else sqrt(acc / counted)
    }

    /** `[rmse, maxAbsDiff]` of two `[frame][band]` spectra over their common part. */
    fun spectrumDiff(golden: Array<FloatArray>, actual: Array<FloatArray>): DoubleArray {
        val frames = min(golden.size, actual.size)
        var acc = 0.0
        var counted = 0
        var worst = 0.0
        for (f in 0 until frames) {
            val g = golden[f]; val a = actual[f]
            val n = min(g.size, a.size)
            for (b in 0 until n) {
                val d = (g[b] - a[b]).toDouble()
                if (!d.isFinite()) continue
                acc += d * d
                counted++
                val ad = abs(d)
                if (ad > worst) worst = ad
            }
        }
        return doubleArrayOf(if (counted == 0) 0.0 else sqrt(acc / counted), worst)
    }

    private fun lengthGap(a: Int, b: Int): Int = abs(a - b)
}
