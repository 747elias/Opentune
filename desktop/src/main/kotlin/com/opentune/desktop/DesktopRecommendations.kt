package com.opentune.desktop

import com.opentune.data.history.History
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.library.HiddenList
import com.opentune.data.model.Song
import com.opentune.data.reco.JioSaavnRadio
import com.opentune.data.reco.SpotifyRadio
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.Recommender
import com.opentune.data.spotify.Spotify
import com.opentune.data.spotify.TrackMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Builds an autoplay or Smart Shuffle queue from the user's chosen catalogue. */
internal object DesktopRecommendations {
    private const val MIN_MATCHES = 3
    private const val RECENT_MS = 3 * 60 * 60_000L

    suspend fun after(seed: Song): List<Song> {
        val settings = AppSettings.playback.value
        val seedArtist = HiddenList.mainArtist(seed.artist)
        val matches = when (settings.recommender) {
            Recommender.YOUTUBE -> emptyList()
            Recommender.JIOSAAVN -> available { match(seed, JioSaavnRadio.radio(seed.title, seedArtist)) }
            Recommender.SPOTIFY -> available { match(seed, SpotifyRadio.radio(seed.title, seedArtist, seed.albumName)) }
        }
        val candidates = matches.takeIf { settings.recommender == Recommender.SPOTIFY || it.size >= MIN_MATCHES } ?: available {
            InnertubeParser.parseWatchQueue(Innertube.next(seed.videoId), songsOnly = settings.songsOnly)
        }
        val songs = DesktopHidden.suggestions(candidates).filter { it.videoId != seed.videoId && Pages.allowed(it) }
        val cutoff = System.currentTimeMillis() - RECENT_MS
        val recent = History.records.value.asSequence().takeWhile { it.playedAt >= cutoff }.mapTo(HashSet()) { it.videoId }
        return songs.filterNot { it.videoId in recent }.takeIf { it.size >= MIN_MATCHES } ?: songs
    }

    private suspend fun match(seed: Song, suggestions: List<Spotify.Track>): List<Song> = coroutineScope {
        val hidden = DesktopHidden.list.value
        val picks = suggestions.asSequence()
            .filterNot { hidden.hidesArtist(it.artists.joinToString(", ")) }
            .take(15)
            .toList()
        val matches = picks.chunked(4).flatMap { batch ->
            batch.map { track ->
                async {
                    available {
                        val query = "${track.title} ${track.artists.firstOrNull().orEmpty()}".trim()
                        listOfNotNull(TrackMatch.best(track, Pages.search(query)))
                    }
                }
            }.awaitAll().flatten()
        }
        DesktopHidden.suggestions(matches).filter { it.videoId != seed.videoId && Pages.allowed(it) }.distinctBy { it.videoId }
    }

    private suspend fun available(load: suspend () -> List<Song>): List<Song> = try {
        load()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
}
