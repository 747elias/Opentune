package com.opentune.data.spotify

import com.opentune.data.DebugLog as Log
import com.opentune.data.MusicRepository
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Brings a Spotify playlist (or the liked songs) over as a playlist on the
 * phone: each track is looked up on YouTube Music by title and artist and
 * the closest catalogue song is kept. Runs on its own scope so leaving the
 * screen doesn't stop it.
 */
object PlaylistImport {
    private const val TAG = "PlaylistImport"
    private const val PARALLEL = 4

    data class Progress(
        val name: String,
        val total: Int,
        val done: Int = 0,
        val matched: Int = 0,
        val missed: List<String> = emptyList(),
        val playlistId: String? = null,
        val finished: Boolean = false,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    val running get() = job?.isActive == true

    /** Finds [tracks] on YouTube Music and saves them as a new phone playlist called [name]. */
    fun start(name: String, tracks: suspend () -> List<Spotify.Track>) = startAll(listOf(name to tracks))

    /**
     * Imports several playlists one after another, each its own phone
     * playlist. Progress counts every song across all of them; [Progress.name]
     * names the one being worked on.
     */
    fun startAll(playlists: List<Pair<String, suspend () -> List<Spotify.Track>>>) {
        if (running || playlists.isEmpty()) return
        val title = if (playlists.size == 1) playlists.first().first else com.opentune.i18n.Tr.f("{0} playlists", playlists.size)
        _progress.value = Progress(title, total = 0)
        job = scope.launch {
            try {
                val lists = playlists.map { (name, load) -> name to load() }
                _progress.value = Progress(title, total = lists.sumOf { it.second.size })
                val gate = Semaphore(PARALLEL)
                var lastId: String? = null
                lists.forEachIndexed { i, (name, list) ->
                    if (lists.size > 1) _progress.update { it?.copy(name = com.opentune.i18n.Tr.f("{0} ({1} of {2})", name, i + 1, lists.size)) }
                    val found = list.map { t ->
                        async {
                            val song = gate.withPermit { runCatching { match(t) }.getOrNull() }
                            _progress.update { p ->
                                p?.copy(
                                    done = p.done + 1,
                                    matched = p.matched + if (song != null) 1 else 0,
                                    missed = if (song == null) p.missed + "${t.artists.firstOrNull().orEmpty()} – ${t.title}" else p.missed,
                                )
                            }
                            song
                        }
                    }.awaitAll().filterNotNull()
                    val id = LibraryStore.createPlaylist(name)
                    LibraryStore.addAllToPlaylist(id, found)
                    lastId = id
                }
                // With several, "Open" goes to Library's list rather than the last one.
                _progress.update { it?.copy(name = title, playlistId = lastId.takeIf { lists.size == 1 }, finished = true) }
            } catch (e: CancellationException) {
                _progress.update { it?.copy(finished = true, error = "Cancelled") }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "import of $title failed", e)
                _progress.update { it?.copy(finished = true, error = e.message ?: "Import failed") }
            }
        }
    }

    /** Saves songs that are already YouTube Music songs, with nothing to look up. */
    fun saveSongs(name: String, songs: List<Song>) {
        if (running) return
        val id = LibraryStore.createPlaylist(name)
        LibraryStore.addAllToPlaylist(id, songs)
        _progress.value = Progress(name, total = songs.size, done = songs.size, matched = songs.size, playlistId = id, finished = true)
    }

    fun cancel() = job?.cancel()

    fun dismiss() {
        if (!running) _progress.value = null
    }

    /** The YouTube Music song for [track], or null when nothing is close enough. */
    suspend fun match(track: Spotify.Track, exactLength: Boolean = false): Song? {
        val artist = track.artists.firstOrNull().orEmpty()
        val rows = MusicRepository.search("${track.title} $artist".trim(), SearchFilter.SONGS)
        val candidates = rows.mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
        return best(track, candidates, exactLength)
    }

    internal fun best(track: Spotify.Track, candidates: List<Song>, exactLength: Boolean = false): Song? =
        TrackMatch.best(track, candidates, exactLength)

    internal fun key(text: String): String = TrackMatch.key(text)

    internal fun placeholder(artistKey: String): Boolean = TrackMatch.placeholder(artistKey)

}
