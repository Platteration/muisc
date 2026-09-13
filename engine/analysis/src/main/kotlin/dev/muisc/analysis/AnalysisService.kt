package dev.muisc.analysis

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.AudioDecoder
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.PcmStream
import dev.muisc.dsp.resample.Resampler
import java.io.File
import kotlin.math.min

/**
 * Cache-fronted analysis of a track by source id: fingerprint → cache lookup → (decode → resample to the engine
 * rate → adapt channels → analyse → cache). This is what the CLI uses; the Android app wires the same class
 * with its MediaCodec decoder, Room cache and its own [fingerprinter] over content URIs.
 *
 * The cache key is `"<content fingerprint>:<decoderId>"` (design §3.3): different decoders may emit different
 * priming, so each decoder's frame timeline is cached separately. The [TrackAnalysis] carries this full string
 * as its `fingerprint`, and its `sampleRate` is [engineSampleRate].
 *
 * Decoding happens into one float buffer per track (the [Resampler] needs random access to the whole signal,
 * and every decoder that lands here currently emits the file's native rate). Once a position-deterministic
 * `ResamplingPcmStream` exists this can hand the analyzer the streaming overload directly.
 *
 * @param decoderId identity of [decoder] in the cache key (`"javasound"` on the JVM, the MediaCodec component
 *   name on Android).
 * @param fingerprinter content fingerprint of a source; defaults to [Fingerprint.ofFile] on the source's path.
 */
class AnalysisService(
    val analyzer: TrackAnalyzer,
    val cache: AnalysisCache,
    val decoder: AudioDecoder,
    val engineSampleRate: Int,
    val channels: Int,
    val decoderId: String = DEFAULT_DECODER_ID,
    val resampler: Resampler = Resampler(),
    val fingerprinter: (AudioSourceId) -> String = { Fingerprint.ofFile(File(it.value)) },
) {
    init {
        require(engineSampleRate > 0) { "engineSampleRate must be positive" }
        require(channels >= 1) { "channels must be >= 1" }
    }

    /** Full cache key of [source] (content fingerprint plus decoder id). Reads the file's head and tail. */
    fun fingerprintOf(source: AudioSourceId): String = "${fingerprinter(source)}:$decoderId"

    /** Cached analysis of [source] at the engine rate and current version, or null without decoding anything. */
    fun cachedAnalysisOf(source: AudioSourceId): TrackAnalysis? = cache.get(fingerprintOf(source), engineSampleRate, TrackAnalysis.CURRENT_VERSION)

    /**
     * Analysis of [source]: the cached one when present, else decode + analyse + cache. [progress] receives a
     * `"fingerprint"` and `"decode"` stage before the analyzer's own stages; a cache hit reports `"cached"` at 1.
     * @param force re-analyse even when a cached entry exists (the CLI's `--force`).
     */
    fun analysisOf(source: AudioSourceId, progress: AnalysisProgress = AnalysisProgress.NONE, force: Boolean = false): TrackAnalysis {
        progress.onProgress(STAGE_FINGERPRINT, 0.0)
        val fingerprint = fingerprintOf(source)
        if (!force) {
            cache.get(fingerprint, engineSampleRate, TrackAnalysis.CURRENT_VERSION)?.let {
                progress.onProgress(STAGE_CACHED, 1.0)
                return it
            }
        }
        progress.onProgress(STAGE_DECODE, 0.0)
        val audio = decoder.open(source).use { decodeAtEngineFormat(it) }
        val analysis = analyzer.analyze(audio, source.value, fingerprint, progress)
        cache.put(analysis)
        return analysis
    }

    /** Decodes the whole stream and converts it to the engine rate and channel count. */
    fun decodeAtEngineFormat(stream: PcmStream): AudioBuffer {
        val native = readAll(stream)
        val resampled = if (native.sampleRate == engineSampleRate) native else resampler.resample(native, engineSampleRate)
        return resampled.withChannels(channels)
    }

    /** Reads a stream into one exactly-sized buffer when the length is known, otherwise with geometric growth. */
    private fun readAll(stream: PcmStream): AudioBuffer {
        val ch = stream.channelCount
        val known = stream.totalFrames
        var capacity = if (known > 0) min(known, Int.MAX_VALUE.toLong()).toInt() else stream.sampleRate * 30
        var data = Array(ch) { FloatArray(capacity) }
        var total = 0
        while (true) {
            if (total == capacity) {
                val grown = min(Int.MAX_VALUE.toLong(), capacity.toLong() * 2).toInt()
                if (grown <= capacity) break
                data = Array(ch) { data[it].copyOf(grown) }
                capacity = grown
            }
            val n = stream.read(data, total, min(1 shl 16, capacity - total))
            if (n <= 0) break
            total += n
        }
        if (total != capacity) data = Array(ch) { data[it].copyOf(total) }
        return AudioBuffer(stream.sampleRate, data)
    }

    companion object {
        const val DEFAULT_DECODER_ID = "javasound"
        const val STAGE_FINGERPRINT = "fingerprint"
        const val STAGE_CACHED = "cached"
        const val STAGE_DECODE = "decode"
    }
}
