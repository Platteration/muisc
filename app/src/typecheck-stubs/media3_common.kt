@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.media3.common

import android.net.Uri
import android.os.Looper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

object C {
    const val TIME_UNSET: Long = Long.MIN_VALUE + 1
    const val INDEX_UNSET: Int = -1
}

class MediaMetadata private constructor() {
    class Builder {
        fun setTitle(title: CharSequence?): Builder = this
        fun setSubtitle(subtitle: CharSequence?): Builder = this
        fun setDisplayTitle(title: CharSequence?): Builder = this
        fun setArtist(artist: CharSequence?): Builder = this
        fun setAlbumTitle(album: CharSequence?): Builder = this
        fun setAlbumArtist(artist: CharSequence?): Builder = this
        fun setGenre(genre: CharSequence?): Builder = this
        fun setArtworkUri(uri: Uri?): Builder = this
        fun setTrackNumber(value: Int?): Builder = this
        fun setDiscNumber(value: Int?): Builder = this
        fun setRecordingYear(value: Int?): Builder = this
        fun setIsBrowsable(value: Boolean?): Builder = this
        fun setIsPlayable(value: Boolean?): Builder = this
        fun setMediaType(value: Int?): Builder = this
        fun build(): MediaMetadata = MediaMetadata()
    }
    companion object {
        const val MEDIA_TYPE_MUSIC = 0
        const val MEDIA_TYPE_ALBUM = 4
        const val MEDIA_TYPE_ARTIST = 5
        const val MEDIA_TYPE_PLAYLIST = 6
        const val MEDIA_TYPE_FOLDER_MIXED = 13
        const val MEDIA_TYPE_FOLDER_ALBUMS = 14
        const val MEDIA_TYPE_FOLDER_ARTISTS = 15
        const val MEDIA_TYPE_FOLDER_PLAYLISTS = 17
    }
}

class MediaItem private constructor() {
    val mediaId: String = ""
    val mediaMetadata: MediaMetadata = MediaMetadata.Builder().build()
    class Builder {
        fun setMediaId(id: String): Builder = this
        fun setUri(uri: Uri?): Builder = this
        fun setMimeType(mimeType: String?): Builder = this
        fun setMediaMetadata(metadata: MediaMetadata): Builder = this
        fun build(): MediaItem = MediaItem()
    }
}

interface Player {
    fun pause()
    fun release()

    class Commands private constructor() {
        class Builder {
            fun addAll(vararg commands: Int): Builder = this
            fun build(): Commands = Commands()
        }
    }

    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2
        const val PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST = 1
        const val COMMAND_PLAY_PAUSE = 1
        const val COMMAND_PREPARE = 2
        const val COMMAND_STOP = 3
        const val COMMAND_SEEK_TO_DEFAULT_POSITION = 4
        const val COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM = 5
        const val COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM = 6
        const val COMMAND_SEEK_TO_PREVIOUS = 7
        const val COMMAND_SEEK_TO_NEXT_MEDIA_ITEM = 8
        const val COMMAND_SEEK_TO_NEXT = 9
        const val COMMAND_SEEK_TO_MEDIA_ITEM = 10
        const val COMMAND_SET_REPEAT_MODE = 11
        const val COMMAND_SET_SHUFFLE_MODE = 12
        const val COMMAND_GET_CURRENT_MEDIA_ITEM = 13
        const val COMMAND_GET_TIMELINE = 14
        const val COMMAND_GET_METADATA = 15
        const val COMMAND_SET_MEDIA_ITEM = 16
        const val COMMAND_CHANGE_MEDIA_ITEMS = 17
        const val COMMAND_RELEASE = 18
    }
}

abstract class SimpleBasePlayer(applicationLooper: Looper) : Player {

    protected abstract fun getState(): State

    fun invalidateState() {}

    override fun pause() {}
    override fun release() {}

    protected open fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

    fun interface PositionSupplier {
        fun get(): Long
        companion object {
            val ZERO: PositionSupplier = PositionSupplier { 0L }
            fun getConstant(positionMs: Long): PositionSupplier = PositionSupplier { positionMs }
            fun getExtrapolating(currentPositionMs: Long, playbackSpeed: Float): PositionSupplier = PositionSupplier { currentPositionMs }
        }
    }

    class MediaItemData private constructor() {
        class Builder(uid: Any) {
            fun setMediaItem(mediaItem: MediaItem): Builder = this
            fun setMediaMetadata(metadata: MediaMetadata): Builder = this
            fun setDurationUs(durationUs: Long): Builder = this
            fun setIsSeekable(value: Boolean): Builder = this
            fun setIsDynamic(value: Boolean): Builder = this
            fun build(): MediaItemData = MediaItemData()
        }
    }

    class State private constructor() {
        class Builder {
            fun setAvailableCommands(commands: Player.Commands): Builder = this
            fun setPlaybackState(state: Int): Builder = this
            fun setPlayWhenReady(playWhenReady: Boolean, reason: Int): Builder = this
            fun setPlaylist(playlist: List<MediaItemData>): Builder = this
            fun setCurrentMediaItemIndex(index: Int): Builder = this
            fun setContentPositionMs(supplier: PositionSupplier): Builder = this
            fun setContentBufferedPositionMs(supplier: PositionSupplier): Builder = this
            fun setShuffleModeEnabled(enabled: Boolean): Builder = this
            fun setRepeatMode(mode: Int): Builder = this
            fun setIsLoading(loading: Boolean): Builder = this
            fun build(): State = State()
        }
    }
}
