package dev.muisc.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.muisc.app.data.db.Song
import dev.muisc.app.di.AppGraph
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The playback service (DESIGN.md §7, §8).
 *
 * It owns the engine graph for the process: it creates the real [EngineController] and [TransitionLabApi], installs
 * them in [AppGraph] (the UI's delegating stand-ins replay whatever was buffered), wraps the controller in
 * [MuiscPlayer] and publishes it through a `MediaLibrarySession` — notification, lock screen, Bluetooth/AVRCP,
 * headset buttons and the Android Auto browse tree. Foreground promotion and the notification itself are Media3's
 * defaults (`DefaultMediaNotificationProvider`); the service is foreground only while something plays.
 *
 * The "becoming noisy" broadcast is handled here, driving the controller through its public
 * contract; ducking goes to the engine when the controller implements [DuckableEngine].
 */
@OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaLibraryService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var session: MediaLibrarySession? = null
    private var controller: EngineController? = null
    private var lab: TransitionLabApi? = null
    private var noisy: BecomingNoisyReceiver? = null

    private val library get() = AppGraph.libraryRepository

    override fun onCreate() {
        super.onCreate()
        val engine = createEngine()
        val engineController = engine.first
        controller = engineController
        lab = engine.second
        AppGraph.installPlayback(engineController, engine.second)

        val player = MuiscPlayer(engineController, serviceScope) { ids ->
            library.songsByIds(ids.mapNotNull { it.toLongOrNull() })
        }
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setId(SESSION_ID)
            .apply { sessionActivityIntent()?.let { setSessionActivity(it) } }
            .build()

        // Audio focus is owned by EngineControllerImpl, which holds the state a transient loss needs and can duck
        // the master gain directly. The service only watches for unplugged headphones.
        noisy = BecomingNoisyReceiver(this) { engineController.pause() }
        observePlayback(engineController)
    }

    /**
     * Builds the engine graph. [EngineGraph] wires the ProgramPlayer, the AudioTrack sink, the planner/renderer and
     * the TransitionCoordinator, and returns the controller plus the Transition Lab that share them.
     */
    private fun createEngine(): Pair<EngineController, TransitionLabApi> = EngineGraph.create(this)

    /** Listens for unplugged headphones only while playing. */
    private fun observePlayback(engineController: EngineController) {
        serviceScope.launch {
            engineController.state
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { playing -> if (playing) noisy?.register() else noisy?.unregister() }
        }
    }

    private fun sessionActivityIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Swiping the app away stops the service unless something is still playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val playing = controller?.state?.value?.isPlaying == true
        if (!playing) {
            session?.player?.pause()
            stopSelf()
        }
    }

    /**
     * Memory pressure: the engine drops the next pre-rendered transition (a live DJ move is installed instead) and
     * cancels background analysis rather than risking the service being killed mid-track.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        (controller as? EngineControllerImpl)?.onTrimMemory(level)
    }

    override fun onDestroy() {
        noisy?.unregister()
        noisy = null
        session?.run {
            player.release()
            release()
        }
        session = null
        AppGraph.uninstallPlayback(controller, lab)
        controller?.release()
        controller = null
        lab = null
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------ session callback

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val commands = base.availableSessionCommands.buildUpon()
                .add(SessionCommand(COMMAND_TOGGLE_TRANSITIONS, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_NEXT_TRANSITION_INFO, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(commands, base.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val engine = this@PlaybackService.controller
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
            return when (customCommand.customAction) {
                COMMAND_TOGGLE_TRANSITIONS -> {
                    val prefs = engine.state.value.transitionPrefs
                    val next = prefs.copy(enabled = !prefs.enabled)
                    engine.updateTransitionPrefs(next)
                    serviceScope.launch { AppGraph.settings.update(next) }
                    val extras = Bundle().apply { putBoolean(EXTRA_TRANSITIONS_ENABLED, next.enabled) }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
                }
                COMMAND_NEXT_TRANSITION_INFO -> {
                    val state = engine.state.value
                    val edge = state.edges[state.currentIndex]
                    val extras = Bundle().apply {
                        putString(EXTRA_EDGE_STATE, edgeLabel(edge))
                        putString(EXTRA_NEXT_TITLE, state.next?.title ?: "")
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
                }
                else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }

        /** Media3 controllers (Android Auto, "play this id") send items that carry only a media id. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            val resolved = ArrayList<MediaItem>(mediaItems.size)
            for (item in mediaItems) {
                val song = item.mediaId.toLongOrNull()?.let { library.songById(it) }
                resolved += if (song != null) MuiscPlayer.mediaItem(song) else item
            }
            resolved
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(browsable(ROOT_ID, ROOT_TITLE, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED), params)
            )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            val songId = mediaId.toLongOrNull()
            if (songId != null) {
                val song = library.songById(songId)
                if (song != null) LibraryResult.ofItem(MuiscPlayer.mediaItem(song), null)
                else LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
            } else {
                val node = nodeItem(mediaId)
                if (node != null) LibraryResult.ofItem(node, null)
                else LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val all = childrenOf(parentId)
            val paged = if (pageSize > 0) all.drop(page * pageSize).take(pageSize) else all
            LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
        }
    }

    // ------------------------------------------------------------------------------------------ browse tree

    private suspend fun childrenOf(parentId: String): List<MediaItem> = when {
        parentId == ROOT_ID -> listOf(
            browsable(NODE_SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browsable(NODE_ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            browsable(NODE_ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            browsable(NODE_PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        )
        parentId == NODE_SONGS -> library.songs().first().map { MuiscPlayer.mediaItem(it) }
        parentId == NODE_ALBUMS -> library.albums().first().map {
            browsable("$PREFIX_ALBUM${it.id}", it.title, MediaMetadata.MEDIA_TYPE_ALBUM, it.artist)
        }
        parentId == NODE_ARTISTS -> library.artists().first().map {
            browsable("$PREFIX_ARTIST${it.id}", it.name, MediaMetadata.MEDIA_TYPE_ARTIST)
        }
        parentId == NODE_PLAYLISTS -> library.playlists().first().map {
            browsable("$PREFIX_PLAYLIST${it.id}", it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST)
        }
        parentId.startsWith(PREFIX_ALBUM) -> songsOf(parentId, PREFIX_ALBUM) { library.songsOfAlbum(it).first() }
        parentId.startsWith(PREFIX_ARTIST) -> songsOf(parentId, PREFIX_ARTIST) { library.songsOfArtist(it).first() }
        parentId.startsWith(PREFIX_PLAYLIST) -> songsOf(parentId, PREFIX_PLAYLIST) { library.songsOfPlaylist(it).first() }
        else -> emptyList()
    }

    private suspend fun songsOf(parentId: String, prefix: String, load: suspend (Long) -> List<Song>): List<MediaItem> {
        val id = parentId.removePrefix(prefix).toLongOrNull() ?: return emptyList()
        return load(id).map { MuiscPlayer.mediaItem(it) }
    }

    private fun nodeItem(mediaId: String): MediaItem? = when {
        mediaId == ROOT_ID -> browsable(ROOT_ID, ROOT_TITLE, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        mediaId == NODE_SONGS -> browsable(NODE_SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        mediaId == NODE_ALBUMS -> browsable(NODE_ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        mediaId == NODE_ARTISTS -> browsable(NODE_ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
        mediaId == NODE_PLAYLISTS -> browsable(NODE_PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        mediaId.startsWith(PREFIX_ALBUM) -> browsable(mediaId, "Album", MediaMetadata.MEDIA_TYPE_ALBUM)
        mediaId.startsWith(PREFIX_ARTIST) -> browsable(mediaId, "Artist", MediaMetadata.MEDIA_TYPE_ARTIST)
        mediaId.startsWith(PREFIX_PLAYLIST) -> browsable(mediaId, "Playlist", MediaMetadata.MEDIA_TYPE_PLAYLIST)
        else -> null
    }

    private fun browsable(id: String, title: String, mediaType: Int, subtitle: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun edgeLabel(edge: EdgeState?): String = when (edge) {
        null, EdgeState.Unknown -> "unknown"
        is EdgeState.Gated -> "gated: ${edge.reason}"
        EdgeState.Analysing -> "analysing"
        is EdgeState.Planned -> "planned: ${edge.displayName} (${String.format(Locale.US, "%.2f", edge.score)})"
        is EdgeState.Rendering -> "rendering: ${edge.strategyId}"
        is EdgeState.Ready -> "ready: ${edge.displayName}"
        is EdgeState.Live -> "live: ${edge.kind}"
        is EdgeState.Failed -> "failed: ${edge.reason}"
    }

    /** Runs [block] on the service scope and completes the returned future with its result. */
    private fun <T : Any> future(block: suspend CoroutineScope.() -> T): ListenableFuture<T> {
        val settable = SettableFuture.create<T>()
        serviceScope.launch {
            try {
                settable.set(block(this))
            } catch (e: Throwable) {
                settable.setException(e)
            }
        }
        return settable
    }

    companion object {
        const val SESSION_ID = "muisc"

        /** Custom session command: flips the transition master switch and persists it. */
        const val COMMAND_TOGGLE_TRANSITIONS = "dev.muisc.playback.TOGGLE_TRANSITIONS"

        /** Custom session command: returns the planned transition for the current queue edge. */
        const val COMMAND_NEXT_TRANSITION_INFO = "dev.muisc.playback.NEXT_TRANSITION_INFO"

        const val EXTRA_TRANSITIONS_ENABLED = "transitionsEnabled"
        const val EXTRA_EDGE_STATE = "edgeState"
        const val EXTRA_NEXT_TITLE = "nextTitle"

        const val ROOT_TITLE = "Muisc"
        const val ROOT_ID = "muisc:root"
        const val NODE_SONGS = "muisc:songs"
        const val NODE_ALBUMS = "muisc:albums"
        const val NODE_ARTISTS = "muisc:artists"
        const val NODE_PLAYLISTS = "muisc:playlists"
        const val PREFIX_ALBUM = "muisc:album:"
        const val PREFIX_ARTIST = "muisc:artist:"
        const val PREFIX_PLAYLIST = "muisc:playlist:"
    }
}
