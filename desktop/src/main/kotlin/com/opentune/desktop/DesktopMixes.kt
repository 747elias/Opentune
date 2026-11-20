package com.opentune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.opentune.data.history.DailyMixes
import com.opentune.data.history.Daylist
import com.opentune.data.history.GenreMixes
import com.opentune.data.history.MixSnapshots
import com.opentune.data.history.PlayRecord
import com.opentune.data.history.Wrapped
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.metadata.ArtistGenres
import com.opentune.data.reco.GenreDiscover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object DesktopMixes {
    private val refreshGate = Mutex()

    fun build(history: List<PlayRecord>): List<DailyMixes.Mix> {
        val genreOf = ArtistGenres::genreOf
        val genre = MixSnapshots.mixes(MixSnapshots.GENRES).value.ifEmpty { GenreMixes.build(history, genreOf) }
        val mixes = listOfNotNull(Daylist.build(history, genreOf)) + DailyMixes.build(history) + genre + GenreDiscover.mixes.value
        return mixes.mapNotNull { mix ->
            val songs = DesktopHidden.suggestions(mix.songs)
            mix.copy(songs = songs).takeIf { songs.size >= 6 }
        }
    }

    suspend fun refresh(history: List<PlayRecord>) = refreshGate.withLock {
        val counts = history.groupingBy { Wrapped.mainArtist(it.artist) }.eachCount()
        ArtistGenres.learn(counts.entries.sortedByDescending { it.value }.map { it.key }, max = 8)
        val genreOf = ArtistGenres::genreOf
        val hidden = DesktopHidden.list.value
        val genre = GenreMixes.build(history, genreOf, hidden = hidden)
        if (genre.isNotEmpty() && MixSnapshots.due(MixSnapshots.GENRES, genre.map { it.id })) {
            MixSnapshots.save(MixSnapshots.GENRES, genre)
        }
        val favourites = GenreMixes.favourites(history, genreOf, hidden)
        if (favourites.isNotEmpty()) {
            val played = history.mapTo(mutableSetOf(), PlayRecord::videoId)
            DesktopAccount.liked.value.mapTo(played) { it.videoId }
            GenreDiscover.refresh(favourites, played, hidden, genreOf, counts.keys) { seed ->
                DesktopHidden.suggestions(InnertubeParser.parseWatchQueue(Innertube.next(seed.videoId)))
            }
        }
    }
}

@Composable
internal fun rememberDesktopMixes(history: List<PlayRecord>): List<DailyMixes.Mix> {
    val genres by ArtistGenres.genres.collectAsState()
    val savedGenres by MixSnapshots.mixes(MixSnapshots.GENRES).collectAsState()
    val discover by GenreDiscover.mixes.collectAsState()
    val hidden by DesktopHidden.list.collectAsState()
    val dislikes by DesktopHidden.dislikes.collectAsState()
    var part by remember { mutableStateOf(Daylist.partAt(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60_000L)
            part = Daylist.partAt(System.currentTimeMillis())
        }
    }
    LaunchedEffect(history.size / 6, hidden, dislikes, part) {
        withContext(Dispatchers.IO) { DesktopMixes.refresh(history) }
    }
    return remember(history, genres, savedGenres, discover, hidden, dislikes, part) { DesktopMixes.build(history) }
}
