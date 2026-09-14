@file:Suppress("unused", "UNUSED_PARAMETER")

package android.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import java.nio.ByteBuffer

class AudioAttributes private constructor() {
    class Builder {
        fun setUsage(usage: Int): Builder = this
        fun setContentType(type: Int): Builder = this
        fun build(): AudioAttributes = AudioAttributes()
    }
    companion object {
        const val USAGE_MEDIA = 1
        const val CONTENT_TYPE_MUSIC = 2
    }
}

class AudioFormat private constructor() {
    class Builder {
        fun setEncoding(encoding: Int): Builder = this
        fun setSampleRate(rate: Int): Builder = this
        fun setChannelMask(mask: Int): Builder = this
        fun build(): AudioFormat = AudioFormat()
    }
    companion object {
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_8BIT = 3
        const val ENCODING_PCM_FLOAT = 4
        const val CHANNEL_OUT_MONO = 4
        const val CHANNEL_OUT_STEREO = 12
        const val CHANNEL_OUT_QUAD = 204
        const val CHANNEL_OUT_5POINT1 = 252
    }
}

class AudioFocusRequest private constructor() {
    class Builder(focusGain: Int) {
        fun setAudioAttributes(attributes: AudioAttributes): Builder = this
        fun setWillPauseWhenDucked(value: Boolean): Builder = this
        fun setOnAudioFocusChangeListener(listener: AudioManager.OnAudioFocusChangeListener, handler: Handler): Builder = this
        fun build(): AudioFocusRequest = AudioFocusRequest()
    }
}

class AudioManager {
    fun getProperty(key: String): String? = null
    fun requestAudioFocus(request: AudioFocusRequest): Int = 1
    fun abandonAudioFocusRequest(request: AudioFocusRequest): Int = 1

    fun interface OnAudioFocusChangeListener {
        fun onAudioFocusChange(focusChange: Int)
    }

    companion object {
        const val PROPERTY_OUTPUT_SAMPLE_RATE = "android.media.property.OUTPUT_SAMPLE_RATE"
        const val PROPERTY_OUTPUT_FRAMES_PER_BUFFER = "android.media.property.OUTPUT_FRAMES_PER_BUFFER"
        const val ACTION_AUDIO_BECOMING_NOISY = "android.media.AUDIO_BECOMING_NOISY"
        const val AUDIOFOCUS_GAIN = 1
        const val AUDIOFOCUS_LOSS = -1
        const val AUDIOFOCUS_LOSS_TRANSIENT = -2
        const val AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3
        const val AUDIOFOCUS_REQUEST_GRANTED = 1
        const val AUDIOFOCUS_REQUEST_FAILED = 0
    }
}

class AudioTrack private constructor() {
    val playbackHeadPosition: Int = 0
    val underrunCount: Int = 0
    fun write(audioData: FloatArray, offsetInFloats: Int, sizeInFloats: Int, writeMode: Int): Int = sizeInFloats
    fun play() {}
    fun pause() {}
    fun flush() {}
    fun stop() {}
    fun release() {}
    fun setBufferSizeInFrames(frames: Int): Int = frames

    class Builder {
        fun setAudioAttributes(attributes: AudioAttributes): Builder = this
        fun setAudioFormat(format: AudioFormat): Builder = this
        fun setBufferSizeInBytes(size: Int): Builder = this
        fun setTransferMode(mode: Int): Builder = this
        fun build(): AudioTrack = AudioTrack()
    }

    companion object {
        const val WRITE_BLOCKING = 0
        const val MODE_STREAM = 1
        const val ERROR_DEAD_OBJECT = -6
        fun getMinBufferSize(sampleRate: Int, channelConfig: Int, audioFormat: Int): Int = 4096
    }
}

class MediaFormat {
    fun getString(name: String): String? = null
    fun getInteger(name: String): Int = 0
    fun getLong(name: String): Long = 0
    fun containsKey(name: String): Boolean = false
    fun setInteger(name: String, value: Int) {}
    companion object {
        const val KEY_MIME = "mime"
        const val KEY_SAMPLE_RATE = "sample-rate"
        const val KEY_CHANNEL_COUNT = "channel-count"
        const val KEY_DURATION = "durationUs"
        const val KEY_PCM_ENCODING = "pcm-encoding"
    }
}

class MediaExtractor {
    val trackCount: Int = 0
    val sampleTime: Long = 0
    fun setDataSource(context: Context, uri: Uri, headers: Map<String, String>?) {}
    fun setDataSource(path: String) {}
    fun getTrackFormat(index: Int): MediaFormat = MediaFormat()
    fun selectTrack(index: Int) {}
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int = -1
    fun advance(): Boolean = false
    fun seekTo(timeUs: Long, mode: Int) {}
    fun release() {}
    companion object {
        const val SEEK_TO_PREVIOUS_SYNC = 0
        const val SEEK_TO_CLOSEST_SYNC = 2
    }
}

class MediaCodec private constructor() {
    val name: String = "stub.decoder"
    val outputFormat: MediaFormat = MediaFormat()
    fun configure(format: MediaFormat, surface: Any?, crypto: Any?, flags: Int) {}
    fun start() {}
    fun stop() {}
    fun flush() {}
    fun release() {}
    fun dequeueInputBuffer(timeoutUs: Long): Int = -1
    fun dequeueOutputBuffer(info: BufferInfo, timeoutUs: Long): Int = -1
    fun getInputBuffer(index: Int): ByteBuffer? = null
    fun getOutputBuffer(index: Int): ByteBuffer? = null
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {}
    fun releaseOutputBuffer(index: Int, render: Boolean) {}

    class BufferInfo {
        var offset: Int = 0
        var size: Int = 0
        var flags: Int = 0
        var presentationTimeUs: Long = 0
    }

    companion object {
        const val INFO_TRY_AGAIN_LATER = -1
        const val INFO_OUTPUT_FORMAT_CHANGED = -2
        const val BUFFER_FLAG_END_OF_STREAM = 4
        fun createDecoderByType(type: String): MediaCodec = MediaCodec()
    }
}

class MediaMetadataRetriever {
    fun setDataSource(context: Context, uri: Uri) {}
    fun setDataSource(path: String) {}
    fun extractMetadata(keyCode: Int): String? = null
    fun release() {}
    companion object {
        const val METADATA_KEY_DURATION = 9
    }
}
