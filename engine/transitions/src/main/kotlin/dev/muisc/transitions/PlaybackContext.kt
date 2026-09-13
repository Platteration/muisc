package dev.muisc.transitions

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioSourceId
import kotlinx.serialization.Serializable

/**
 * How the current queue was built. This is a property of the QUEUE, decided when it is created,
 * never inferred per pair of tracks.
 */
enum class PlaybackContext {
    /** An album played in track order: no transitions, gapless playback, respect the artist's sequencing. */
    ALBUM,
    /** A user playlist played in order. */
    PLAYLIST,
    /** Shuffle (whole library, playlist, artist, genre...). */
    SHUFFLE,
    /** A hand-built queue ("play next" / "add to queue"). */
    QUEUE,
    /** A single track with nothing after it. */
    SINGLE,
}

/** A track as the transition engine sees it: identity, where to decode it from, album membership, and its analysis. */
data class TrackRef(
    val id: String,
    val source: AudioSourceId,
    val analysis: TrackAnalysis,
    val albumId: String? = null,
    val title: String = "",
    val artist: String = "",
) {
    override fun toString(): String = if (title.isNotEmpty()) "$artist – $title" else id
}

/** User preferences that shape planning and rendering. Serializable so they round-trip between phone and CLI. */
@Serializable
data class TransitionPrefs(
    /** Master switch. */
    val enabled: Boolean = true,
    /** Apply transitions even inside an album played in order (off by default: respect the album). */
    val allowInAlbums: Boolean = false,
    /** When shuffling, still keep album flow for consecutive tracks that happen to come from the same album. */
    val keepAlbumFlowInShuffle: Boolean = false,
    /** Maximum tempo change applied by time-stretching, in percent (8 = ±8 %). */
    val maxStretchPercent: Double = 8.0,
    /** Maximum pitch shift for harmonic mixing, in semitones. */
    val maxPitchShiftSemitones: Double = 1.0,
    /** Preserve pitch when stretching (key lock). If false, small ratios use plain resampling (vinyl-style). */
    val keyLock: Boolean = true,
    /** Target loudness both decks are normalised to before mixing, LUFS. */
    val targetLufs: Double = -14.0,
    /** Preferred transition length in bars for long blends (strategies scale their defaults from it). */
    val preferredOverlapBars: Int = 16,
    /** 0..1: how adventurous the planner is (0 = safest strategies only, 1 = showpieces welcome). */
    val energy: Double = 0.5,
    /** Per-strategy multipliers on the applicability score (id -> weight, default 1.0). */
    val strategyWeights: Map<String, Double> = emptyMap(),
    val disabledStrategies: Set<String> = emptySet(),
    /** Per-strategy parameter overrides (strategy id -> param id -> value string). */
    val paramOverrides: Map<String, Map<String, String>> = emptyMap(),
    /** Penalise repeating the same strategy as the previous transition (0 = off, 1 = strong). */
    val varietyPenalty: Double = 0.3,
    /** Engine sample rate everything is rendered at. */
    val sampleRate: Int = 44100,
    val channels: Int = 2,
)

/** The gating rule: when do two consecutive queue items get a transition at all. */
object TransitionGating {
    fun transitionsEnabled(context: PlaybackContext, a: TrackRef, b: TrackRef, prefs: TransitionPrefs): Boolean {
        if (!prefs.enabled) return false
        val sameAlbum = a.albumId != null && a.albumId == b.albumId
        return when (context) {
            PlaybackContext.ALBUM -> prefs.allowInAlbums || !sameAlbum
            PlaybackContext.SHUFFLE -> !(prefs.keepAlbumFlowInShuffle && sameAlbum)
            PlaybackContext.PLAYLIST, PlaybackContext.QUEUE -> true
            PlaybackContext.SINGLE -> false
        }
    }
}
