package dev.muisc.analysis

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.PcmStream
import dev.muisc.audio.WavIo
import dev.muisc.audio.jvm.JavaSoundDecoder
import dev.muisc.audio.synth.SyntheticSong
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileAnalysisCacheTest {
    private fun tempDir(): File = Files.createTempDirectory("muisc-cache").toFile().also { it.deleteOnExit() }

    /** Counts analyzer invocations around a real analyzer. */
    private class CountingAnalyzer(private val inner: TrackAnalyzer) : TrackAnalyzer {
        var calls = 0
        override fun analyze(audio: AudioBuffer, sourceId: String, fingerprint: String, progress: AnalysisProgress): TrackAnalysis {
            calls++
            return inner.analyze(audio, sourceId, fingerprint, progress)
        }
        override fun analyze(stream: PcmStream, sourceId: String, fingerprint: String, progress: AnalysisProgress): TrackAnalysis {
            calls++
            return inner.analyze(stream, sourceId, fingerprint, progress)
        }
    }

    private fun sampleAnalysis(fingerprint: String = "fp-1", sampleRate: Int = 44100): TrackAnalysis {
        val audio = SyntheticSong(bpm = 120.0, bars = 6, introBars = 1, outroBars = 1, sampleRate = sampleRate, seed = 8).render()
        return DefaultTrackAnalyzer().analyze(audio, "sample", fingerprint)
    }

    @Test
    fun putGetClear_gzipJsonFiles_atomicWrite_andMemoryFront() {
        val dir = tempDir()
        val cache = FileAnalysisCache(dir, memoryEntries = 2)
        val a = sampleAnalysis("fp-a")
        assertNull(cache.get("fp-a", 44100))
        cache.put(a)
        val file = cache.fileFor("fp-a", 44100)
        assertTrue(file.isFile, "entry file ${file.name}")
        assertTrue(file.name.endsWith("-44100-v${TrackAnalysis.CURRENT_VERSION}.json.gz"))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") }, "no temp files left behind")
        // gzip + JSON on disk, identical to the analysis
        val json = GZIPInputStream(file.inputStream()).use { it.readBytes().decodeToString() }
        DefaultTrackAnalyzerTest.assertDeepEquals(a, TrackAnalysis.fromJson(json))
        // memory hit returns the same instance, disk hit a deep-equal copy
        assertTrue(cache.get("fp-a", 44100) === a)
        val fresh = FileAnalysisCache(dir, memoryEntries = 0)
        val fromDisk = fresh.get("fp-a", 44100)
        assertNotNull(fromDisk)
        DefaultTrackAnalyzerTest.assertDeepEquals(a, fromDisk)
        // keyed by sample rate and version
        assertNull(cache.get("fp-a", 48000))
        assertNull(cache.get("fp-a", 44100, TrackAnalysis.CURRENT_VERSION + 1))
        assertNull(cache.get("fp-b", 44100))
        // overwrite replaces the file
        val a2 = a.copy(analysisMillis = 4242)
        cache.put(a2)
        assertEquals(4242L, FileAnalysisCache(dir, 0).get("fp-a", 44100)!!.analysisMillis)
        assertEquals(1, cache.entryCount())
        // LRU front is bounded (3 entries with 2 slots: still all on disk)
        cache.put(a.copy(fingerprint = "fp-b")); cache.put(a.copy(fingerprint = "fp-c"))
        assertEquals(3, cache.entryCount())
        assertNotNull(cache.get("fp-a", 44100)); assertNotNull(cache.get("fp-b", 44100)); assertNotNull(cache.get("fp-c", 44100))
        cache.clear()
        assertEquals(0, cache.entryCount())
        assertNull(cache.get("fp-a", 44100))
        assertTrue(dir.isDirectory)
    }

    @Test
    fun corruptFiles_areIgnoredAndOverwritten() {
        val dir = tempDir()
        val cache = FileAnalysisCache(dir, memoryEntries = 0)
        val a = sampleAnalysis("fp-x")
        // garbage where the entry should be
        val file = cache.fileFor("fp-x", 44100)
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(cache.get("fp-x", 44100), "corrupt entry must read as a miss")
        assertTrue(!file.exists(), "corrupt entry is dropped")
        // valid gzip, invalid JSON
        java.util.zip.GZIPOutputStream(file.outputStream()).use { it.write("{not json".toByteArray()) }
        assertNull(cache.get("fp-x", 44100))
        // valid JSON but for another fingerprint under this name
        java.util.zip.GZIPOutputStream(file.outputStream()).use { it.write(a.copy(fingerprint = "other").toJson().toByteArray()) }
        assertNull(cache.get("fp-x", 44100))
        cache.put(a)
        assertNotNull(cache.get("fp-x", 44100))
        // truncated gzip
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        assertNull(cache.get("fp-x", 44100))
        cache.put(a)
        assertNotNull(cache.get("fp-x", 44100))
    }

    @Test
    fun analysisService_hitsCacheOnSecondCall_andRecoversFromCorruptEntry() {
        val dir = tempDir()
        val song = SyntheticSong(bpm = 126.0, tonic = 3, bars = 8, introBars = 1, outroBars = 1, sampleRate = 44100, seed = 6)
        val wav = File(dir, "track.wav")
        WavIo.write(wav, song.render())
        val counting = CountingAnalyzer(DefaultTrackAnalyzer())
        val cache = FileAnalysisCache(File(dir, "cache"))
        val service = AnalysisService(counting, cache, JavaSoundDecoder(), engineSampleRate = 44100, channels = 2)
        val source = AudioSourceId(wav.path)
        val stages = ArrayList<String>()
        assertNull(service.cachedAnalysisOf(source))
        val first = service.analysisOf(source, progress = { s, _ -> stages.add(s) })
        assertEquals(1, counting.calls)
        assertEquals(wav.path, first.sourceId)
        assertEquals(Fingerprint.ofFile(wav) + ":javasound", first.fingerprint)
        assertEquals(44100, first.sampleRate)
        assertEquals(126.0, first.tempo.bpm, 1.3)
        assertEquals(AnalysisService.STAGE_FINGERPRINT, stages.first()); assertTrue(AnalysisService.STAGE_DECODE in stages); assertEquals(DefaultTrackAnalyzer.STAGE_DONE, stages.last())

        val second = service.analysisOf(source)
        assertEquals(1, counting.calls, "second call must come from the cache")
        DefaultTrackAnalyzerTest.assertDeepEquals(first, second)
        assertNotNull(service.cachedAnalysisOf(source))
        // a fresh service over the same directory (new process) also hits the disk cache
        val counting2 = CountingAnalyzer(DefaultTrackAnalyzer())
        val service2 = AnalysisService(counting2, FileAnalysisCache(File(dir, "cache")), JavaSoundDecoder(), 44100, 2)
        DefaultTrackAnalyzerTest.assertDeepEquals(first, service2.analysisOf(source))
        assertEquals(0, counting2.calls)

        // corrupt the entry on disk: the service re-analyses and overwrites it
        val file = cache.fileFor(first.fingerprint, 44100)
        assertTrue(file.isFile)
        file.writeText("corrupt")
        val cache3 = FileAnalysisCache(File(dir, "cache"))
        val counting3 = CountingAnalyzer(DefaultTrackAnalyzer())
        val service3 = AnalysisService(counting3, cache3, JavaSoundDecoder(), 44100, 2)
        val third = service3.analysisOf(source)
        assertEquals(1, counting3.calls, "corrupt entry must trigger a re-analysis")
        DefaultTrackAnalyzerTest.assertDeepEquals(first, third)
        assertNotNull(FileAnalysisCache(File(dir, "cache"), 0).get(first.fingerprint, 44100), "corrupt entry overwritten")

        // force re-analysis
        service3.analysisOf(source, force = true)
        assertEquals(2, counting3.calls)

        // a different engine rate is a different entry: decoded 44.1 kHz audio is resampled to 48 kHz
        val service48 = AnalysisService(counting3, cache3, JavaSoundDecoder(), 48000, 1)
        val a48 = service48.analysisOf(source)
        assertEquals(3, counting3.calls)
        assertEquals(48000, a48.sampleRate)
        assertEquals(Math.round(song.render().frames * 48000.0 / 44100), a48.totalFrames)
        assertEquals(126.0, a48.tempo.bpm, 1.3)
        assertEquals(3, counting3.calls); service48.analysisOf(source); assertEquals(3, counting3.calls)
    }

    @Test
    fun memoryCache_behavesLikeFileCacheForTheInterface() {
        val cache = MemoryAnalysisCache()
        val a = sampleAnalysis("m")
        cache.put(a)
        assertTrue(cache.get("m", 44100) === a)
        assertNull(cache.get("m", 48000))
        cache.clear()
        assertNull(cache.get("m", 44100))
    }
}
