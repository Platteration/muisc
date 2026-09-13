package dev.muisc.analysis

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.WavIo
import dev.muisc.audio.synth.SyntheticSong
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FingerprintTest {
    private fun tempDir(): File = Files.createTempDirectory("muisc-fp").toFile().also { it.deleteOnExit() }

    @Test
    fun fileFingerprint_isStable_andChangesWithContentSizeOrMtime() {
        val dir = tempDir()
        val song = SyntheticSong(bpm = 120.0, bars = 6, sampleRate = 44100, seed = 2) // ~2 MB of 16-bit stereo: head + middle + tail
        val file = File(dir, "song.wav")
        WavIo.write(file, song.render())
        assertTrue(file.length() > Fingerprint.HEAD_BYTES + Fingerprint.TAIL_BYTES, "test file must be larger than the hashed regions")
        val fp1 = Fingerprint.ofFile(file)
        val fp2 = Fingerprint.ofFile(file)
        assertEquals(fp1, fp2, "stable across calls")
        assertEquals(64, fp1.length); assertTrue(fp1.all { it in "0123456789abcdef" })

        // same bytes through the InputStream lambda (the Android content-URI path)
        val bytes = file.readBytes()
        assertEquals(fp1, Fingerprint.ofSource(file.length(), file.lastModified()) { bytes.inputStream() })
        assertEquals(fp1, Fingerprint.ofBytes(bytes, file.length(), file.lastModified()))
        // a stream whose skip() is lazy (returns 0) still works
        assertEquals(fp1, Fingerprint.ofSource(file.length(), file.lastModified()) { NoSkipStream(bytes.inputStream()) })

        val mtime = file.lastModified()
        // edit inside the head region (like an ID3v2 / RIFF chunk edit), same size + mtime → changes
        val head = bytes.copyOf(); head[4096] = (head[4096] + 1).toByte()
        assertNotEquals(fp1, Fingerprint.ofBytes(head, file.length(), mtime), "head edit")
        // edit inside the tail region (like an ID3v1 / APE tag) → changes
        val tail = bytes.copyOf(); tail[bytes.size - 100] = (tail[bytes.size - 100] + 1).toByte()
        assertNotEquals(fp1, Fingerprint.ofBytes(tail, file.length(), mtime), "tail edit")
        // an edit strictly in the un-hashed middle with size + mtime preserved is (by design) not detected
        val mid = bytes.copyOf(); val m = (Fingerprint.HEAD_BYTES + 5000).toInt(); mid[m] = (mid[m] + 1).toByte()
        assertEquals(fp1, Fingerprint.ofBytes(mid, file.length(), mtime), "middle edit is outside the hashed regions")
        // size or mtime change → changes
        assertNotEquals(fp1, Fingerprint.ofBytes(bytes + byteArrayOf(0), file.length() + 1, mtime))
        assertNotEquals(fp1, Fingerprint.ofBytes(bytes, file.length(), mtime + 1000))

        // on disk: rewrite the file with different audio content (another key: the head region differs from sample 0)
        WavIo.write(file, SyntheticSong(bpm = 120.0, tonic = 5, bars = 6, sampleRate = 44100, seed = 2).render())
        file.setLastModified(mtime)
        assertNotEquals(fp1, Fingerprint.ofFile(file), "different audio, same size and mtime")
    }

    @Test
    fun smallFile_hashesWholeContentOnce_matchesReferenceRecipe() {
        val bytes = ByteArray(10_000) { (it * 31).toByte() }
        val fp = Fingerprint.ofBytes(bytes, 10_000L, 123_456L)
        // reference: sha256(size BE ‖ mtime BE ‖ all bytes) since size < HEAD + TAIL
        val md = MessageDigest.getInstance("SHA-256")
        md.update(java.nio.ByteBuffer.allocate(16).putLong(10_000L).putLong(123_456L).array())
        md.update(bytes)
        assertEquals(Fingerprint.hex(md.digest()), fp)
        // every byte matters
        val edited = bytes.copyOf(); edited[7_777] = 1
        assertNotEquals(fp, Fingerprint.ofBytes(edited, 10_000L, 123_456L))
        assertEquals(Fingerprint.ofBytes(ByteArray(0)), Fingerprint.ofBytes(ByteArray(0)))
    }

    @Test
    fun bufferFingerprint_stable_changesWithContent_independentOfIdentity() {
        val a = SyntheticSong(bpm = 100.0, bars = 4, sampleRate = 44100, seed = 1).render()
        val fa = Fingerprint.ofBuffer(a)
        assertEquals(fa, Fingerprint.ofBuffer(a.copy()))
        assertEquals(64, fa.length)
        val b = a.copy(); b[0][b.frames / 2] += 0.1f
        // the sample hit may fall between strides: change a whole run so at least one sampled point differs
        for (i in b.frames / 2 until minOf(b.frames, b.frames / 2 + 200)) b[0][i] = 0.9f
        assertNotEquals(fa, Fingerprint.ofBuffer(b))
        assertNotEquals(fa, Fingerprint.ofBuffer(AudioBuffer(48000, a.channels)), "sample rate is part of the hash")
        assertNotEquals(fa, Fingerprint.ofBuffer(a.withChannels(1)))
        assertEquals(Fingerprint.ofBuffer(AudioBuffer.silence(44100, 2, 0)), Fingerprint.ofBuffer(AudioBuffer.silence(44100, 2, 0)))
        assertNotEquals(Fingerprint.ofBuffer(AudioBuffer.silence(44100, 2, 0)), Fingerprint.ofBuffer(AudioBuffer.silence(44100, 2, 10)))
    }

    /** InputStream whose skip() always returns 0, forcing the read-based fallback. */
    private class NoSkipStream(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun skip(n: Long): Long = 0L
        override fun close() = inner.close()
    }
}
