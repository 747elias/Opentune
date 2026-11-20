package com.opentune.desktop

import com.opentune.data.SavedFile
import com.opentune.data.library.HiddenArtist
import com.opentune.data.library.HiddenList
import com.opentune.data.library.HiddenSong
import com.opentune.data.model.Song
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The exclusions apply to suggestions. User-picked music can still be played directly. */
internal object DesktopHidden {
    @Serializable
    private data class State(
        val songs: List<HiddenSong> = emptyList(),
        val artists: List<HiddenArtist> = emptyList(),
        val disliked: Set<String> = emptySet(),
    )

    private val disk = SavedFile(File(appContext.filesDir, "hidden-music.json"), "HiddenMusic")
    private val json = Json { ignoreUnknownKeys = true }
    private val gate = Any()
    @Volatile private var state = disk.read(State()) { json.decodeFromString<State>(it) }
    private val _list = MutableStateFlow(HiddenList(state.songs, state.artists))
    val list = _list.asStateFlow()
    private val _dislikes = MutableStateFlow(state.disliked)
    val dislikes = _dislikes.asStateFlow()

    fun isDisliked(id: String): Boolean = id in state.disliked

    fun suggestions(songs: List<Song>): List<Song> {
        val hidden = list.value
        val rejected = dislikes.value
        return songs.filterNot { hidden.hides(it) || it.videoId in rejected }
    }

    fun hideSong(song: Song): Boolean {
        val item = HiddenSong(song.videoId, song.title, song.artist, song.thumbnailUrl, System.currentTimeMillis())
        return change { it.copy(songs = listOf(item) + it.songs.filterNot { old -> old.videoId == item.videoId }) }
            .also { written -> if (written) Player.dropHiddenRecommendations() }
    }

    fun hideArtist(song: Song): Boolean {
        val name = HiddenList.mainArtist(song.artist).takeIf(String::isNotBlank) ?: return false
        val item = HiddenArtist(name, song.artistId, System.currentTimeMillis())
        return change { it.copy(artists = listOf(item) + it.artists.filterNot { old -> old.key == item.key }) }
            .also { written -> if (written) Player.dropHiddenRecommendations() }
    }

    fun unhideSong(id: String): Boolean = change { it.copy(songs = it.songs.filterNot { old -> old.videoId == id }) }
    fun unhideArtist(key: String): Boolean = change { it.copy(artists = it.artists.filterNot { old -> old.key == key }) }

    fun markDisliked(song: Song): Boolean {
        val written = change { it.copy(disliked = it.disliked + song.videoId) }
        if (written) Player.dropDisliked(song.videoId)
        return written
    }

    fun clearDislike(id: String): Boolean = change { it.copy(disliked = it.disliked - id) }

    private fun change(transform: (State) -> State): Boolean = synchronized(gate) {
        val next = transform(state)
        if (next == state) return@synchronized true
        runCatching {
            disk.write(json.encodeToString(next))
            state = next
            _list.value = HiddenList(next.songs, next.artists)
            _dislikes.value = next.disliked
        }.isSuccess
    }
}
