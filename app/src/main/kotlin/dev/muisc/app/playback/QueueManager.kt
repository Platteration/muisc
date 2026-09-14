package dev.muisc.app.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.KeyEstimate
import dev.muisc.analysis.model.LoudnessInfo
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.TempoEstimate
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.db.Song
import dev.muisc.audio.AudioSourceId
import dev.muisc.player.QueueItem
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.TrackRef
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/** Immutable view of the queue handed to the UI and to the coordinator. */
data class QueueSnapshot(
    /** The play order (already shuffled when [shuffle] is on). */
    val songs: List<Song> = emptyList(),
    val index: Int = -1,
    val context: PlaybackContext = PlaybackContext.SINGLE,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
) {
    val current: Song? get() = songs.getOrNull(index)
    val isEmpty: Boolean get() = songs.isEmpty()
}

/**
 * The queue the user sees: order, shuffle, repeat and the current index, plus the mapping into the engine's
 * [QueueItem] / [TrackRef] world.
 *
 * Two orders are kept: the *natural* order (as the user built it: album order, playlist order, "add to queue"
 * appends) and the *play* order, which equals the natural order unless shuffle is on. Turning shuffle on keeps the
 * current song where it is and shuffles everything else around it; turning it off restores the natural order and
 * re-finds the current song, exactly like Retro Music.
 *
 * Every method is `@Synchronized`: the UI thread mutates, the playback pump reads. Mutating methods return the
 * [QueueSnapshot] that resulted, so callers never have to take a second lock to read it back.
 *
 * Song identity in the engine is `Song.id.toString()`; the decoded source is `Song.uri` (which is also what
 * `RoomAnalysisCache` matches to flip `Song.hasAnalysis`). The same song may appear twice in a queue: the two
 * entries then share one engine deck and one analysis, which is correct (it is the same audio) but means the
 * coordinator's per-edge bookkeeping keys on the first occurrence — harmless for playback.
 */
class QueueManager {

    private val natural = ArrayList<Song>()
    private val order = ArrayList<Song>()

    var index: Int = -1
        private set
    var context: PlaybackContext = PlaybackContext.SINGLE
        private set
    var shuffle: Boolean = false
        private set
    var repeat: RepeatMode = RepeatMode.OFF
        private set

    private var rngSeed: Long = System.nanoTime()

    @Synchronized
    fun snapshot(): QueueSnapshot = QueueSnapshot(ArrayList(order), index, context, shuffle, repeat)

    @Synchronized
    fun current(): Song? = order.getOrNull(index)

    @Synchronized
    fun setQueue(songs: List<Song>, startIndex: Int, ctx: PlaybackContext): QueueSnapshot {
        natural.clear()
        natural.addAll(songs)
        order.clear()
        order.addAll(songs)
        context = ctx
        index = if (songs.isEmpty()) -1 else startIndex.coerceIn(0, songs.size - 1)
        if (shuffle && songs.isNotEmpty()) reshuffleAroundCurrent()
        return snapshot()
    }

    /** Inserts [songs] right after the current song (both orders). */
    @Synchronized
    fun playNext(songs: List<Song>): QueueSnapshot {
        if (songs.isEmpty()) return snapshot()
        if (order.isEmpty()) return setQueue(songs, 0, PlaybackContext.QUEUE)
        val at = (index + 1).coerceIn(0, order.size)
        order.addAll(at, songs)
        val cur = order.getOrNull(index)
        val naturalAt = (cur?.let { naturalIndexOf(it) }?.plus(1) ?: natural.size).coerceIn(0, natural.size)
        natural.addAll(naturalAt, songs)
        return snapshot()
    }

    @Synchronized
    fun addToQueue(songs: List<Song>): QueueSnapshot {
        if (songs.isEmpty()) return snapshot()
        if (order.isEmpty()) return setQueue(songs, 0, PlaybackContext.QUEUE)
        order.addAll(songs)
        natural.addAll(songs)
        return snapshot()
    }

    /** Drag-reorder in the play order; the natural order follows so switching shuffle off keeps the intent. */
    @Synchronized
    fun move(from: Int, to: Int): QueueSnapshot {
        if (from !in order.indices) return snapshot()
        val target = to.coerceIn(0, order.size - 1)
        if (from == target) return snapshot()
        val song = order.removeAt(from)
        order.add(target, song)
        index = when {
            index == from -> target
            from < index && target >= index -> index - 1
            from > index && target <= index -> index + 1
            else -> index
        }
        val nFrom = naturalIndexOf(song)
        if (nFrom >= 0) {
            natural.removeAt(nFrom)
            natural.add(target.coerceIn(0, natural.size), song)
        }
        return snapshot()
    }

    @Synchronized
    fun removeAt(at: Int): QueueSnapshot {
        if (at !in order.indices) return snapshot()
        val song = order.removeAt(at)
        val nAt = naturalIndexOf(song)
        if (nAt >= 0) natural.removeAt(nAt)
        if (order.isEmpty()) {
            index = -1
        } else if (at < index) {
            index -= 1
        } else if (at == index) {
            index = index.coerceAtMost(order.size - 1)
        }
        return snapshot()
    }

    @Synchronized
    fun skipTo(at: Int): QueueSnapshot {
        if (at in order.indices) index = at
        return snapshot()
    }

    /** Moves to the next index; false when the queue is exhausted (repeat is handled by the controller). */
    @Synchronized
    fun advance(): Boolean {
        if (index < 0 || index + 1 >= order.size) return false
        index += 1
        return true
    }

    @Synchronized
    fun back(): Boolean {
        if (index <= 0) return false
        index -= 1
        return true
    }

    @Synchronized
    fun setShuffle(enabled: Boolean): QueueSnapshot {
        if (enabled == shuffle) return snapshot()
        shuffle = enabled
        if (order.isEmpty()) return snapshot()
        if (enabled) {
            reshuffleAroundCurrent()
        } else {
            val cur = order.getOrNull(index)
            order.clear()
            order.addAll(natural)
            index = cur?.let { order.indexOfFirst { s -> s.id == it.id } } ?: 0
            if (index < 0) index = 0
        }
        return snapshot()
    }

    @Synchronized
    fun setRepeat(mode: RepeatMode): QueueSnapshot {
        repeat = mode
        return snapshot()
    }

    @Synchronized
    fun clear(): QueueSnapshot {
        order.clear()
        natural.clear()
        index = -1
        context = PlaybackContext.SINGLE
        return snapshot()
    }

    /** Index of the song whose engine id is [id] (the engine reports track changes by id), or -1. */
    @Synchronized
    fun indexOfEngineId(id: String): Int = order.indexOfFirst { trackId(it) == id }

    /** Restores a persisted queue (ids already resolved to songs by the caller). */
    @Synchronized
    fun restore(songs: List<Song>, naturalSongs: List<Song>, at: Int, ctx: PlaybackContext, shuffled: Boolean, repeatMode: RepeatMode): QueueSnapshot {
        order.clear()
        order.addAll(songs)
        natural.clear()
        natural.addAll(if (naturalSongs.isEmpty()) songs else naturalSongs)
        context = ctx
        shuffle = shuffled
        repeat = repeatMode
        index = if (order.isEmpty()) -1 else at.coerceIn(0, order.size - 1)
        return snapshot()
    }

    /** The natural (un-shuffled) order, for persistence. */
    @Synchronized
    fun naturalOrder(): List<Song> = ArrayList(natural)

    private fun naturalIndexOf(song: Song): Int = natural.indexOfFirst { it.id == song.id }

    /** Keeps the current song at its place and shuffles the rest (deterministic per call via a rolling seed). */
    private fun reshuffleAroundCurrent() {
        val cur = order.getOrNull(index)
        val rest = ArrayList(order)
        if (cur != null) rest.removeAt(index)
        rngSeed = rngSeed * 6364136223846793005L + 1442695040888963407L
        val rnd = java.util.Random(rngSeed)
        for (i in rest.indices.reversed()) {
            val j = rnd.nextInt(i + 1)
            val tmp = rest[i]; rest[i] = rest[j]; rest[j] = tmp
        }
        order.clear()
        if (cur != null) {
            order.add(cur)
            order.addAll(rest)
            index = 0
        } else {
            order.addAll(rest)
            index = if (order.isEmpty()) -1 else 0
        }
    }

    companion object {
        /** The engine identity of a song (stable across queue edits). */
        fun trackId(song: Song): String = song.id.toString()

        /** What the decoder opens. The content URI is also what `RoomAnalysisCache` matches on. */
        fun sourceOf(song: Song): AudioSourceId = AudioSourceId(song.uri)

        /** MediaStore reports 0 for "no album"; that must not make every album-less track look like one album. */
        fun albumIdOf(song: Song): String? = if (song.albumId > 0L) song.albumId.toString() else null

        fun queueItem(song: Song): QueueItem = QueueItem(
            sourceId = sourceOf(song),
            albumId = albumIdOf(song),
            title = song.title,
            artist = song.artist,
            id = trackId(song),
        )

        fun queueItems(songs: List<Song>): List<QueueItem> = songs.map { queueItem(it) }

        /**
         * A [TrackRef] for a song whose analysis is not available yet: an EMPTY beat grid, no tempo, loudness at the
         * preference target (so its deck gain is 0 dB) and the duration MediaStore reports. Good enough to play the
         * body of a track gapless; every transition planned against it degrades to the crossfade floor, which is why
         * the controller only ever uses it for the track the listener is waiting to hear.
         */
        fun placeholderAnalysis(song: Song, sampleRate: Int, targetLufs: Double): TrackAnalysis {
            val frames = if (song.durationMs > 0) song.durationMs * sampleRate / 1000L else 0L
            val lufs = if (targetLufs.isFinite()) targetLufs.toFloat() else -14f
            return TrackAnalysis(
                sourceId = song.uri,
                fingerprint = PLACEHOLDER_PREFIX + song.id,
                sampleRate = sampleRate,
                totalFrames = frames,
                trimStartFrame = 0L,
                trimEndFrame = frames,
                tempo = TempoEstimate(0.0, 0f),
                grid = BeatGrid.EMPTY,
                key = KeyEstimate(MusicalKey(0, Mode.MAJOR), 0f),
                loudness = LoudnessInfo(lufs, -1f),
            )
        }

        /** True when [analysis] is a stand-in produced by [placeholderAnalysis] (never cached, never trusted). */
        fun isPlaceholder(analysis: TrackAnalysis): Boolean = analysis.fingerprint.startsWith(PLACEHOLDER_PREFIX)

        fun trackRef(song: Song, analysis: TrackAnalysis): TrackRef = TrackRef(
            id = trackId(song),
            source = sourceOf(song),
            analysis = analysis,
            albumId = albumIdOf(song),
            title = song.title,
            artist = song.artist,
        )

        const val PLACEHOLDER_PREFIX = "placeholder:"
    }
}

/** What survives a process death: the ids of both orders, where we were and how we were playing. */
data class PersistedQueue(
    val orderIds: List<Long> = emptyList(),
    val naturalIds: List<Long> = emptyList(),
    val index: Int = -1,
    val context: PlaybackContext = PlaybackContext.SINGLE,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val positionMs: Long = 0L,
)

private val Context.queueDataStore: DataStore<Preferences> by preferencesDataStore(name = "muisc_queue")

/**
 * Queue persistence in its own DataStore file (the queue changes far more often than the settings and must never
 * make a settings write wait). Ids only — the songs themselves are re-read from Room on restore, so a song deleted
 * from the library simply disappears from the restored queue.
 */
class QueuePersistence(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.queueDataStore

    suspend fun save(queue: PersistedQueue) {
        try {
            store.edit { p ->
                p[Keys.ORDER] = queue.orderIds.joinToString(",")
                p[Keys.NATURAL] = queue.naturalIds.joinToString(",")
                p[Keys.INDEX] = queue.index
                p[Keys.CONTEXT] = queue.context.name
                p[Keys.SHUFFLE] = queue.shuffle
                p[Keys.REPEAT] = queue.repeat.name
                p[Keys.POSITION] = queue.positionMs
            }
        } catch (e: IOException) {
            // A queue we could not persist is not worth crashing playback for.
        }
    }

    suspend fun load(): PersistedQueue? {
        val p = try {
            store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.first()
        } catch (e: IOException) {
            return null
        }
        val ids = parseIds(p[Keys.ORDER])
        if (ids.isEmpty()) return null
        return PersistedQueue(
            orderIds = ids,
            naturalIds = parseIds(p[Keys.NATURAL]),
            index = p[Keys.INDEX] ?: 0,
            context = PlaybackContext.entries.firstOrNull { it.name == p[Keys.CONTEXT] } ?: PlaybackContext.QUEUE,
            shuffle = p[Keys.SHUFFLE] ?: false,
            repeat = RepeatMode.entries.firstOrNull { it.name == p[Keys.REPEAT] } ?: RepeatMode.OFF,
            positionMs = p[Keys.POSITION] ?: 0L,
        )
    }

    private fun parseIds(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

    private object Keys {
        val ORDER = stringPreferencesKey("queue.order")
        val NATURAL = stringPreferencesKey("queue.natural")
        val INDEX = intPreferencesKey("queue.index")
        val CONTEXT = stringPreferencesKey("queue.context")
        val SHUFFLE = booleanPreferencesKey("queue.shuffle")
        val REPEAT = stringPreferencesKey("queue.repeat")
        val POSITION = longPreferencesKey("queue.positionMs")
    }
}
