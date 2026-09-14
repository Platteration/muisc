package dev.muisc.app.playback

import android.content.Context
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.EngineStreamFactory
import dev.muisc.audio.GaplessInfo
import dev.muisc.audio.PcmStream
import dev.muisc.player.ChannelAdaptingPcmStream
import dev.muisc.player.ResamplingPcmStream
import dev.muisc.player.SincKernelSpec
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * The Android [EngineStreamFactory] (DESIGN.md §7.1): every reader of a track gets its own chain
 *
 * ```
 * MediaCodecPcmStream → GaplessTrimPcmStream → ResamplingPcmStream → ChannelAdaptingPcmStream
 * ```
 *
 * so that frame 0 is the first PCM frame after gapless trimming, resampled to the engine rate — the definition
 * every frame position in `TrackAnalysis`, `TransitionPlan` and `PlaybackProgram` uses. `ResamplingPcmStream` is
 * position-deterministic, so the body deck and the renderer/analyser produce identical samples for identical
 * positions even though they read through separate decoders.
 *
 * [forRenderer] marks the streams the renderer and the analyser use: their seeks decode from frame 0 so seam-critical
 * positions are frame-exact even on MP3/AAC.
 *
 * Gapless info is parsed once per file and cached for the life of the factory. When the platform decoder reports
 * `encoder-delay`/`encoder-padding` itself it has already trimmed, and no trim stream is inserted.
 */
class AndroidEngineStreamFactory(
    private val context: Context,
    /** True for the renderer/analyser factory (frame-exact seeks, see [MediaCodecPcmStream.decodeFromZero]). */
    private val forRenderer: Boolean = false,
    private val kernel: SincKernelSpec = SincKernelSpec.DEFAULT,
) : EngineStreamFactory {

    private val tags = GaplessTagParser(context)
    private val gaplessCache = ConcurrentHashMap<String, GaplessInfo>()
    private val decoderNames = ConcurrentHashMap<String, String>()

    override fun open(source: AudioSourceId, sampleRate: Int, channels: Int): PcmStream {
        val decoded = MediaCodecPcmStream(context, source, decodeFromZero = forRenderer)
        decoderNames[source.value] = decoded.decoderName
        var stream: PcmStream = decoded
        try {
            val trim = gaplessOf(decoded)
            if (trim.encoderDelayFrames > 0 || trim.encoderPaddingFrames > 0) {
                stream = GaplessTrimPcmStream(stream, trim)
            }
            return adapt(stream, sampleRate, channels, kernel)
        } catch (e: Throwable) {
            stream.close()
            throw e
        }
    }

    /** Codec that decoded [source] the last time it was opened (part of the analysis fingerprint), or null. */
    fun decoderId(source: AudioSourceId): String? = decoderNames[source.value]

    /** Encoder delay/padding this factory will trim for [source] ([GaplessInfo.NONE] when the decoder trims itself). */
    fun gaplessOf(stream: MediaCodecPcmStream): GaplessInfo {
        if (stream.formatGapless != null) return GaplessInfo.NONE // the platform decoder has already trimmed
        return gaplessCache.computeIfAbsent(stream.source.value) { tags.parse(stream.source, stream.mimeType) }
    }

    companion object {
        /** Wraps [stream] so it delivers [sampleRate] / [channels] (no wrapping when it already does). */
        fun adapt(
            stream: PcmStream,
            sampleRate: Int,
            channels: Int,
            kernel: SincKernelSpec = SincKernelSpec.DEFAULT,
        ): PcmStream {
            var s = stream
            if (s.sampleRate != sampleRate) s = ResamplingPcmStream(s, sampleRate, kernel)
            if (s.channelCount != channels) s = ChannelAdaptingPcmStream(s, channels)
            return s
        }
    }
}

/**
 * Removes the encoder priming frames at the start and the padding frames at the end of a lossy stream, so that outer
 * frame 0 is the first real sample of the recording and [totalFrames] is the true length (DESIGN.md §7.1).
 *
 * The priming frames are decoded and dropped when the stream is created, so `position` never needs a correction
 * offset afterwards; [seek] maps outer frames to inner frames by the delay.
 */
class GaplessTrimPcmStream(val inner: PcmStream, val info: GaplessInfo) : PcmStream {

    private val delay: Long = max(0, info.encoderDelayFrames).toLong()
    private val padding: Long = max(0, info.encoderPaddingFrames).toLong()

    override val sampleRate: Int get() = inner.sampleRate
    override val channelCount: Int get() = inner.channelCount

    override val totalFrames: Long
        get() {
            val t = inner.totalFrames
            return if (t < 0) -1L else max(0L, t - delay - padding)
        }

    override val position: Long get() = max(0L, inner.position - delay)

    init {
        if (delay > 0) skipPriming()
    }

    private fun skipPriming() {
        if (inner.position >= delay) return
        val scratch = Array(inner.channelCount) { FloatArray(SKIP_BLOCK) }
        var left = delay - inner.position
        while (left > 0) {
            val n = inner.read(scratch, 0, min(left, SKIP_BLOCK.toLong()).toInt())
            if (n <= 0) break
            left -= n
        }
    }

    override fun read(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        if (frames <= 0) return 0
        var want = frames
        val total = totalFrames
        if (total >= 0) {
            val remaining = total - position
            if (remaining <= 0L) return 0
            want = min(want.toLong(), remaining).toInt()
        }
        if (want <= 0) return 0
        return inner.read(dst, offset, want)
    }

    /** The inner stream lands exactly on the requested frame (its own contract), so no correction is needed here. */
    override fun seek(frame: Long) = inner.seek(frame.coerceAtLeast(0L) + delay)

    override fun close() = inner.close()

    private companion object { const val SKIP_BLOCK = 4096 }
}
