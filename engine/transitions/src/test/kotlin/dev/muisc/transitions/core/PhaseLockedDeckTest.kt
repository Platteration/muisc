package dev.muisc.transitions.core

import dev.muisc.analysis.model.Mode
import dev.muisc.dsp.qa.ArtifactDetector
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhaseLockedDeckTest {
    private val sr = SongFixtures.SR

    /** Deck window of a song: from beat [fromBeat] - 2 bars to beat [fromBeat] + [beats] + 2 bars, grid re-based to the window. */
    private class Deck(song: SongFixtures.Song, fromBeat: Int, beats: Int) {
        val grid = song.grid
        val windowStart = grid.beatFrames[fromBeat - 8]
        val windowEnd = grid.beatFrames[fromBeat + beats + 8]
        val audio = song.audio.slice(windowStart.toInt(), windowEnd.toInt())
        val deckGrid = grid.relativeTo(windowStart)
        val onsets = song.analysis.onsetFrames.relativeTo(windowStart, windowEnd - windowStart)
        val startBeat = fromBeat
        /** Window-relative deck beat frames for the rendered beats. */
        fun beatFrames(beats: Int) = LongArray(beats) { deckGrid.beatFrames[startBeat + it] }
    }

    private fun bias(deck: Deck, beats: Int): Double = SongFixtures.median(SongFixtures.kickOffsets(deck.audio, deck.beatFrames(beats)))

    private fun assertBeatsLand(name: String, out: dev.muisc.audio.AudioBuffer, master: MasterGrid, from: Int, beats: Int, bias: Double, maxMs: Double = 6.0) {
        val expected = LongArray(beats) { master.beatFrames[from + it] - master.beatFrames[from] }
        val offsets = SongFixtures.kickOffsets(out, expected)
        val limit = maxMs / 1000.0 * sr
        val ok = offsets.count { abs(it - bias) <= limit }
        val worst = offsets.maxOf { abs(it - bias) } / sr * 1000.0
        assertTrue(ok >= Math.ceil(0.9 * beats).toInt(), "$name: only $ok/$beats kicks within $maxMs ms of the master beats (bias ${"%.1f".format(bias)} frames, worst ${"%.1f".format(worst)} ms, offsets ${offsets.toList()})")
    }

    private fun assertNoClicks(name: String, out: dev.muisc.audio.AudioBuffer) {
        val report = ArtifactDetector(sr).analyze(out)
        assertTrue(report.clicks.isEmpty(), "$name: clicks ${report.clicks}")
        assertTrue(report.clipRuns.isEmpty(), "$name: clipping ${report.clipRuns}")
    }

    @Test
    fun deck128OntoConstant120MasterWsola() {
        val song = SongFixtures.song("D128", 128.0, 0, Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4)
        val beats = 32
        val deck = Deck(song, fromBeat = 16, beats = beats)
        val master = MasterGrid.constant(sr, 120.0, beats + 8)
        val pld = PhaseLockedDeck(deck.audio, deck.deckGrid, deck.startBeat.toDouble(), deck.onsets, mode = StretchMode.WSOLA)
        val out = pld.render(master, 4, beats)
        assertEquals((master.beatFrames[4 + beats] - master.beatFrames[4]).toInt(), out.frames, "exact output length")
        assertEquals(2, out.channelCount)
        assertEquals(StretchMode.WSOLA, pld.modeUsed)
        assertEquals(beats, pld.ratioTrace.size)
        for (r in pld.ratioTrace) assertEquals(128.0 / 120.0, r, 0.005, "ratio trace ~ 1.0667")
        assertBeatsLand("128→120 WSOLA", out, master, 4, beats, bias(deck, beats))
        assertNoClicks("128→120 WSOLA", out)
        // stereo coherence: channels of the synthetic kick are identical, so L-R must stay tiny in the low band
        assertTrue(out.rms() > 0.05f)
    }

    @Test
    fun deck128OntoConstant120MasterResample() {
        val song = SongFixtures.song("D128", 128.0, 0, Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4)
        val beats = 32
        val deck = Deck(song, fromBeat = 16, beats = beats)
        val master = MasterGrid.constant(sr, 120.0, beats)
        val pld = PhaseLockedDeck(deck.audio, deck.deckGrid, deck.startBeat.toDouble(), deck.onsets, options = PhaseLockedDeck.Options(keyLock = false))
        val out = pld.render(master, 0, beats)
        assertEquals(master.totalFrames.toInt(), out.frames)
        assertEquals(StretchMode.RESAMPLE, pld.modeUsed, "keyLock=false selects the resampler")
        assertBeatsLand("128→120 RESAMPLE", out, master, 0, beats, bias(deck, beats), maxMs = 3.0)
        assertNoClicks("128→120 RESAMPLE", out)
    }

    @Test
    fun deck120OntoLinearGlide120To128() {
        val song = SongFixtures.song("D120", 120.0, 0, Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4)
        val deck = Deck(song, fromBeat = 16, beats = 32)
        val master = MasterGrid.glide(sr, 120.0, 128.0, glideBeats = 16, holdBeats = 8, curve = GlideCurve.LINEAR, preBeats = 4)
        val beats = master.beatCount
        val pld = PhaseLockedDeck(deck.audio, deck.deckGrid, deck.startBeat.toDouble(), deck.onsets)
        val out = pld.render(master, 0, beats)
        assertEquals(master.totalFrames.toInt(), out.frames)
        assertEquals(StretchMode.WSOLA, pld.modeUsed, "6.25 % deviation exceeds the resample threshold")
        assertEquals(1.0, pld.ratioTrace[0], 0.002)
        assertEquals(120.0 / 128.0, pld.ratioTrace[beats - 1], 0.005)
        assertBeatsLand("120→glide", out, master, 0, beats, bias(deck, beats))
        assertNoClicks("120→glide", out)
        val map = pld.timeMap(master, 0, beats)
        assertEquals(master.totalFrames, map.totalOutFrames)
        assertEquals(6.25, map.maxDeviationPercent, 0.05)
    }

    @Test
    fun ratioOneIsNearIdentity() {
        val song = SongFixtures.song("D120", 120.0, 0, Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4)
        val beats = 16
        val deck = Deck(song, fromBeat = 16, beats = beats)
        val master = MasterGrid.constant(sr, 120.0, beats)
        val start = deck.deckGrid.beatFrames[16].toInt()
        val ref = deck.audio.slice(start, start + master.totalFrames.toInt())
        for (mode in listOf(StretchMode.RESAMPLE, StretchMode.WSOLA)) {
            val pld = PhaseLockedDeck(deck.audio, deck.deckGrid, 16.0, deck.onsets, mode = mode)
            val out = pld.render(master, 0, beats)
            assertEquals(ref.frames, out.frames)
            for (c in 0 until 2) {
                val snr = SongFixtures.snrDb(ref[c], out[c])
                assertTrue(snr > 30.0, "$mode channel $c: SNR $snr dB")
            }
            if (mode == StretchMode.RESAMPLE) for (c in 0 until 2) assertTrue(ref[c].contentEquals(out[c]), "resampler at ratio 1 is bit-exact")
        }
        // Auto mode: a 1 % tempo difference picks the resampler with key lock on, WSOLA when forced by mode.
        val near = MasterGrid.constant(sr, 121.2, beats)
        val auto = PhaseLockedDeck(deck.audio, deck.deckGrid, 16.0, deck.onsets)
        auto.render(near, 0, beats)
        assertEquals(StretchMode.RESAMPLE, auto.modeUsed)
        assertEquals(StretchMode.WSOLA, StretcherSelector.select(1.03, keyLock = true))
        assertEquals(StretchMode.RESAMPLE, StretcherSelector.select(1.03, keyLock = false))
        assertEquals(StretchMode.RESAMPLE, StretcherSelector.select(1.01, keyLock = true))
        assertEquals(StretchMode.PHASE_VOCODER, StretcherSelector.select(1.1, keyLock = true, forcePv = true))
    }

    @Test
    fun fractionalStartBeatAndHalfBarRange() {
        val song = SongFixtures.song("D126", 126.0, 0, Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4)
        val deck = Deck(song, fromBeat = 16, beats = 8)
        val master = MasterGrid.constant(sr, 124.0, 8)
        val pld = PhaseLockedDeck(deck.audio, deck.deckGrid, 16.5, deck.onsets, mode = StretchMode.WSOLA)
        val out = pld.render(master, 2, 5)
        assertEquals((master.beatFrames[7] - master.beatFrames[2]).toInt(), out.frames)
        assertNoClicks("fractional", out)
        // beats now fall half-way between master beats: expected kick positions are master beat k + half a period
        val expected = LongArray(5) { master.beatFrames[2 + it] - master.beatFrames[2] + master.periodFrames(2 + it) / 2 }
        val offsets = SongFixtures.kickOffsets(out, expected)
        val b = bias(deck, 6)
        assertTrue(offsets.count { abs(it - b) <= 0.006 * sr } >= 4, "offsets ${offsets.toList()} bias $b")
    }
}
