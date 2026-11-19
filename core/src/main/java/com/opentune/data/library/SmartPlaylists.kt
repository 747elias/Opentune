package com.opentune.data.library

import com.opentune.data.download.DownloadEntry
import com.opentune.data.download.DownloadState
import com.opentune.data.history.PlayRecord

/** Adapts the Android library to the common smart-playlist rules. */
object SmartPlaylists {
    data class Facts(
        val history: List<PlayRecord>,
        val liked: List<SongRef>,
        val downloads: Map<String, DownloadEntry>,
        val playlists: List<LocalPlaylist> = emptyList(),
    )

    val presets get() = SmartPlaylistRules.presets

    fun songs(rules: SmartRules, facts: Facts, now: Long = System.currentTimeMillis(), seed: Long = 0): List<SongRef> {
        val inputs = SmartPlaylistRules.Facts(
            history = facts.history,
            liked = facts.liked.map { it.toSong() },
            downloads = facts.downloads.filterValues { it.state == DownloadState.DONE }.mapValues { (_, entry) ->
                SmartPlaylistRules.Download(entry.song.toSong(), entry.addedAt, entry.bits != null)
            },
            playlists = facts.playlists.flatMap { playlist -> playlist.songs.map { it.toSong() } },
        )
        return SmartPlaylistRules.songs(rules, inputs, now, seed).map(SongRef::of)
    }
}
