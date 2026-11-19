package com.opentune.desktop

import com.opentune.data.model.Song
import com.opentune.data.spotify.ExportedPlaylists
import com.opentune.data.spotify.Spotify
import com.opentune.data.spotify.TrackMatch
import com.opentune.data.spotify.YouTubePlaylists
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal object DesktopPlaylistImport {
    data class Progress(
        val working: Boolean = true,
        val message: String = "Reading playlist…",
        val done: Int = 0,
        val total: Int = 0,
        val missed: List<String> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress = _progress.asStateFlow()
    private var job: Job? = null

    fun link(text: String) = start {
        val id = YouTubePlaylists.parse(text)
        if (id != null) {
            val list = YouTubePlaylists.load(id) { count -> _progress.value = Progress(message = "Reading $count songs…") }
            Library.importPlaylist(list.name, list.songs)
            _progress.value = Progress(false, "Imported ${list.songs.size} songs into ${list.name}.")
        } else {
            val link = Spotify.resolve(text) ?: error("Paste a YouTube playlist or Spotify playlist, album or track link.")
            val collection = Spotify.load(link)
            import(listOf(ExportedPlaylists.Exported(collection.name, collection.tracks)))
        }
    }

    fun file(file: File) = start { import(ExportedPlaylists.read(file.name, file.readBytes())) }

    fun cancel() { job?.cancel() }

    @Synchronized
    private fun start(run: suspend () -> Unit) {
        if (job?.isCompleted == false) return
        _progress.value = Progress()
        job = scope.launch {
            try {
                run()
            } catch (cancelled: CancellationException) {
                _progress.value = Progress(false, "Import cancelled.")
                throw cancelled
            } catch (error: Exception) {
                _progress.value = Progress(false, error.localizedMessage ?: "Couldn't import the playlist.")
            }
        }
    }

    private suspend fun import(playlists: List<ExportedPlaylists.Exported>) = coroutineScope {
        require(playlists.isNotEmpty()) { "No playlist tracks were found in this file." }
        val total = playlists.sumOf { it.tracks.size }
        var done = 0
        val missed = mutableListOf<String>()
        val ready = playlists.map { list ->
            val found = mutableListOf<Song>()
            list.tracks.chunked(4).forEach { batch ->
                val matches = batch.map { track ->
                    async {
                        try {
                            TrackMatch.best(track, Pages.search("${track.title} ${track.artists.firstOrNull().orEmpty()}".trim()))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
                matches.forEachIndexed { index, song ->
                    if (song == null) missed += batch[index].let { "${it.title} — ${it.artists.joinToString()}" } else found += song
                }
                done += batch.size
                _progress.value = Progress(message = "Matching ${list.name}…", done = done, total = total)
            }
            list.name to found
        }
        val withMatches = ready.filter { it.second.isNotEmpty() }
        withMatches.forEach { (name, songs) -> Library.importPlaylist(name, songs) }
        _progress.value = Progress(false, "Imported ${total - missed.size} of $total tracks into ${withMatches.size} playlists.", missed = missed)
    }
}
