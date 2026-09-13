package dev.muisc.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.muisc.app.data.LibraryRepository
import dev.muisc.app.data.db.Album
import dev.muisc.app.data.db.Artist
import dev.muisc.app.data.db.Genre
import dev.muisc.app.data.db.Playlist
import dev.muisc.app.data.db.Song
import dev.muisc.app.di.AppGraph
import dev.muisc.app.playback.EngineController
import dev.muisc.app.ui.components.SongSort
import dev.muisc.app.ui.components.applySort
import dev.muisc.transitions.PlaybackContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A node of the folder tree built from [Song.path]. */
data class FolderNode(
    val name: String,
    val path: String,
    val children: List<FolderNode>,
    val songs: List<Song>,
) {
    val totalSongs: Int get() = songs.size + children.sumOf { it.totalSongs }
    /** All songs below this node, in folder order. */
    fun allSongs(): List<Song> = songs + children.flatMap { it.allSongs() }
}

data class SearchResults(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

/** Builds a tree from the parent directories of all songs. Root has an empty path. */
fun buildFolderTree(songs: List<Song>): FolderNode {
    class Mut(val name: String, val path: String) {
        val children = LinkedHashMap<String, Mut>()
        val songs = ArrayList<Song>()
        fun freeze(): FolderNode = FolderNode(
            name = name,
            path = path,
            children = children.values.sortedBy { it.name.lowercase() }.map { it.freeze() },
            songs = songs.sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() })),
        )
    }
    val root = Mut("", "")
    for (song in songs) {
        val dir = song.path.substringBeforeLast('/', "")
        if (dir.isEmpty()) {
            root.songs += song
            continue
        }
        var node = root
        val acc = StringBuilder()
        for (segment in dir.split('/')) {
            if (segment.isEmpty()) continue
            acc.append('/').append(segment)
            node = node.children.getOrPut(segment) { Mut(segment, acc.toString()) }
        }
        node.songs += song
    }
    return root.freeze()
}

/** Finds the node with the given path (or null). */
fun FolderNode.find(path: String): FolderNode? {
    if (this.path == path) return this
    for (c in children) c.find(path)?.let { return it }
    return null
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val repo: LibraryRepository) : ViewModel() {

    private val controller: EngineController get() = AppGraph.engineController

    private fun <T> Flow<List<T>>.hot(): StateFlow<List<T>> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sort = MutableStateFlow(SongSort.TITLE)

    /** Every song in the library, in the repository's default (title) order. Sorting happens here. */
    private val allSongs: Flow<List<Song>> = repo.songs()

    val songs: StateFlow<List<Song>> = combine(allSongs, sort) { list, s -> list.applySort(s) }
        .flowOn(Dispatchers.Default)
        .hot()

    val albums: StateFlow<List<Album>> = repo.albums().hot()
    val artists: StateFlow<List<Artist>> = repo.artists().hot()
    val genres: StateFlow<List<Genre>> = repo.genres().hot()
    val playlists: StateFlow<List<Playlist>> = repo.playlists().hot()
    val recentlyAdded: StateFlow<List<Song>> = repo.recentlyAdded().hot()
    val mostPlayed: StateFlow<List<Song>> = repo.mostPlayed().hot()
    val history: StateFlow<List<Song>> = repo.history().hot()

    val folderTree: StateFlow<FolderNode> = allSongs
        .map { buildFolderTree(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderNode("", "", emptyList(), emptyList()))

    fun songsOfAlbum(albumId: Long): Flow<List<Song>> = repo.songsOfAlbum(albumId)
    fun songsOfArtist(artistId: Long): Flow<List<Song>> = repo.songsOfArtist(artistId)
    fun songsOfGenre(genreId: Long): Flow<List<Song>> = repo.songsOfGenre(genreId)
    fun songsOfPlaylist(playlistId: Long): Flow<List<Song>> = repo.songsOfPlaylist(playlistId)

    fun album(albumId: Long): Flow<Album?> = albums.map { list -> list.firstOrNull { it.id == albumId } }
    fun artist(artistId: Long): Flow<Artist?> = artists.map { list -> list.firstOrNull { it.id == artistId } }
    fun genre(genreId: Long): Flow<Genre?> = genres.map { list -> list.firstOrNull { it.id == genreId } }
    fun playlist(playlistId: Long): Flow<Playlist?> = playlists.map { list -> list.firstOrNull { it.id == playlistId } }
    fun albumsOfArtist(artistId: Long): Flow<List<Album>> = albums.map { list -> list.filter { it.artistId == artistId } }

    // ---- Search (client-side over the hot library flows; cheap for a phone-sized library) ----

    val searchQuery = MutableStateFlow("")

    val searchResults: StateFlow<SearchResults> = searchQuery
        .flatMapLatest { q ->
            val needle = q.trim().lowercase()
            if (needle.length < 2) {
                MutableStateFlow(SearchResults())
            } else {
                combine(allSongs, repo.albums(), repo.artists()) { songs, albums, artists ->
                    SearchResults(
                        songs = songs.filter {
                            it.title.lowercase().contains(needle) || it.artist.lowercase().contains(needle) || it.album.lowercase().contains(needle)
                        }.take(200),
                        albums = albums.filter { it.title.lowercase().contains(needle) || it.artist.lowercase().contains(needle) }.take(50),
                        artists = artists.filter { it.name.lowercase().contains(needle) }.take(50),
                    )
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    // ---- Playback entry points ----

    fun play(songs: List<Song>, index: Int, context: PlaybackContext) {
        if (songs.isEmpty()) return
        controller.setQueue(songs, index.coerceIn(0, songs.lastIndex), context)
    }

    /** Shuffles [songs] and plays them as a SHUFFLE queue (transitions enabled). */
    fun shuffle(songs: List<Song>) {
        if (songs.isEmpty()) return
        controller.setQueue(songs.shuffled(), 0, PlaybackContext.SHUFFLE)
    }

    fun shuffleAll() = shuffle(songs.value)

    fun playSingle(song: Song) = controller.setQueue(listOf(song), 0, PlaybackContext.SINGLE)

    fun playNext(songs: List<Song>) = controller.playNext(songs)
    fun addToQueue(songs: List<Song>) = controller.addToQueue(songs)

    // ---- Playlists ----

    fun createPlaylist(name: String, songs: List<Song> = emptyList()) {
        viewModelScope.launch {
            val id = repo.createPlaylist(name)
            if (songs.isNotEmpty()) repo.addToPlaylist(id, songs.map { it.id })
        }
    }

    fun addToPlaylist(playlistId: Long, songs: List<Song>) {
        viewModelScope.launch { repo.addToPlaylist(playlistId, songs.map { it.id }) }
    }

    fun removeFromPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch { repo.removeFromPlaylist(playlistId, song.id) }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        viewModelScope.launch { repo.renamePlaylist(playlistId, name) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { repo.deletePlaylist(playlistId) }
    }

    // ---- Library maintenance ----

    fun rescan(full: Boolean = false) = AppGraph.requestScan(full)
}
