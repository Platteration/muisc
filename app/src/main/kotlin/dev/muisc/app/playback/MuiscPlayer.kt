package dev.muisc.app.playback

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.muisc.app.data.ArtworkLoader
import dev.muisc.app.data.db.Song
import dev.muisc.transitions.PlaybackContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Media3 face of the engine (DESIGN.md §8): a [SimpleBasePlayer] that maps [EngineController.state] to a
 * `SimpleBasePlayer.State` and Media3 player commands back to [EngineController] calls. ExoPlayer is never on the
 * audio path — this adapter exists purely so the notification, the lock screen, Bluetooth/AVRCP, headset buttons
 * and Android Auto see a normal `Player`.
 *
 * Everything happens on the main thread (the application looper the session is built on). [invalidateState] is
 * called whenever the controller's state flow emits; the position between emissions is extrapolated by Media3.
 */
@OptIn(markerClass = [UnstableApi::class])
class MuiscPlayer(
    private val controller: EngineController,
    private val scope: CoroutineScope,
    /**
     * Resolves the media ids Media3 hands us (Android Auto, "play this id") back to library rows, in the requested
     * order; unknown ids are dropped. A media id is a [Song.id] rendered as a string.
     */
    private val lookup: suspend (mediaIds: List<String>) -> List<Song>,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    /** Cached playlist, rebuilt only when the queue itself changes (the state flow emits ~10×/s). */
    private var cachedQueue: List<Song> = emptyList()
    private var cachedIds: LongArray = LongArray(0)
    private var cachedPlaylist: List<MediaItemData> = emptyList()

    init {
        // Every engine state change (queue, track, play/pause, position resync) invalidates the Media3 state.
        // [scope] must dispatch on the application looper — the service uses Dispatchers.Main.immediate.
        scope.launch {
            controller.state.collect { invalidateState() }
        }
    }

    // ------------------------------------------------------------------------------------------------ state

    override fun getState(): State {
        val s = controller.state.value
        val playlist = playlistOf(s.queue)
        val index = if (s.queue.isEmpty()) 0 else s.currentIndex.coerceIn(0, s.queue.size - 1)
        val positionMs = s.positionMs.coerceAtLeast(0L)
        val position = if (s.isPlaying) {
            PositionSupplier.getExtrapolating(positionMs, 1f)
        } else {
            PositionSupplier.getConstant(positionMs)
        }
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaybackState(if (s.queue.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setPlayWhenReady(s.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(position)
            .setContentBufferedPositionMs(position)
            .setShuffleModeEnabled(s.shuffle)
            .setRepeatMode(
                when (s.repeat) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                }
            )
            .setIsLoading(false)
            .build()
    }

    private fun playlistOf(queue: List<Song>): List<MediaItemData> {
        if (queue === cachedQueue) return cachedPlaylist
        if (queue.size == cachedIds.size) {
            var same = true
            for (i in queue.indices) if (queue[i].id != cachedIds[i]) { same = false; break }
            if (same) {
                cachedQueue = queue
                return cachedPlaylist
            }
        }
        val items = ArrayList<MediaItemData>(queue.size)
        for (i in queue.indices) items += itemData(queue[i], i)
        cachedQueue = queue
        cachedIds = LongArray(queue.size) { queue[it].id }
        cachedPlaylist = items
        return items
    }

    // ------------------------------------------------------------------------------------------------ commands

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        // The service owns the engine and releases it in onDestroy; releasing the Player must not kill playback
        // started from somewhere else.
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.previous()
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> {
                val state = controller.state.value
                if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != state.currentIndex) {
                    controller.skipToQueueItem(mediaItemIndex)
                }
                controller.seekTo(if (positionMs == C.TIME_UNSET) 0L else positionMs)
            }
            else -> controller.seekTo(if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L))
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        controller.setRepeat(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        controller.setShuffle(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val ids = mediaItems.map { it.mediaId }
        scope.launch {
            val songs = lookup(ids)
            if (songs.isEmpty()) return@launch
            val start = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, songs.size - 1)
            controller.setQueue(songs, start, PlaybackContext.QUEUE, playNow = true)
            if (startPositionMs > 0 && startPositionMs != C.TIME_UNSET) controller.seekTo(startPositionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val ids = mediaItems.map { it.mediaId }
        val state = controller.state.value
        val playNext = index <= state.currentIndex + 1 && state.queue.isNotEmpty()
        scope.launch {
            val songs = lookup(ids)
            if (songs.isEmpty()) return@launch
            if (playNext) controller.playNext(songs) else controller.addToQueue(songs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        // Media3 moves the half-open range [fromIndex, toIndex) so that it starts at newIndex.
        val count = toIndex - fromIndex
        if (count > 0) {
            if (newIndex > fromIndex) {
                repeat(count) { controller.moveQueueItem(fromIndex, newIndex + count - 1) }
            } else {
                for (i in 0 until count) controller.moveQueueItem(fromIndex + i, newIndex + i)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        for (i in toIndex - 1 downTo fromIndex) controller.removeQueueItem(i)
        return Futures.immediateVoidFuture()
    }

    companion object {
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_RELEASE,
            )
            .build()

        /** One queue entry. The uid carries the index so the same song may appear twice in a queue. */
        fun itemData(song: Song, index: Int): MediaItemData =
            MediaItemData.Builder("${song.id}#$index")
                .setMediaItem(mediaItem(song))
                .setMediaMetadata(metadata(song))
                .setDurationUs(if (song.durationMs > 0) song.durationMs * 1000L else C.TIME_UNSET)
                .setIsSeekable(true)
                .setIsDynamic(false)
                .build()

        /** The `MediaItem` a Media3 controller sees: the song's content URI plus its metadata. */
        fun mediaItem(song: Song): MediaItem =
            MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(Uri.parse(song.uri))
                .setMimeType(song.mimeType)
                .setMediaMetadata(metadata(song))
                .build()

        /** Browsable/playable metadata used by the notification, the lock screen and Android Auto. */
        fun metadata(song: Song): MediaMetadata {
            val builder = MediaMetadata.Builder()
                .setTitle(song.title)
                .setDisplayTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .setAlbumArtist(song.albumArtist ?: song.artist)
                .setArtworkUri(ArtworkLoader.albumArtUri(song.albumId))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            if (song.track > 0) builder.setTrackNumber(song.track)
            if (song.disc > 0) builder.setDiscNumber(song.disc)
            if (song.year > 0) builder.setRecordingYear(song.year)
            song.genre?.let { builder.setGenre(it) }
            return builder.build()
        }
    }
}
