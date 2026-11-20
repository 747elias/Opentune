package com.opentune.data.download

import com.opentune.data.history.PlayRecord
import com.opentune.data.model.Song
import com.opentune.data.roundRobin

internal const val SMART_RESERVE_BYTES = 512L * 1024 * 1024
internal val SMART_SONG_LIMIT = 10..500
internal val SMART_BATTERY_LIMIT = 15..80

internal fun smartDownloadAllowed(
    enabled: Boolean, wifi: Boolean, charging: Boolean, battery: Int, minimum: Int, freeBytes: Long,
): Boolean = enabled && wifi && (charging || battery >= minimum.coerceIn(SMART_BATTERY_LIMIT)) &&
    freeBytes >= SMART_RESERVE_BYTES

/** Ignore manual copies and keep earlier candidates ahead of later duplicates. */
internal fun selectSmartDownloads(candidates: List<Song>, manualIds: Set<String>, limit: Int): List<Song> =
    candidates.distinctBy(Song::videoId).filterNot { it.videoId in manualIds }.take(limit.coerceIn(SMART_SONG_LIMIT))

/** Mixes lead the selection, followed by likes and the tracks with the most listening time. */
internal fun smartDownloadCandidates(mixes: List<Song>, liked: List<Song>, history: List<PlayRecord>): List<Song> {
    val favourites = history.groupBy(PlayRecord::videoId).values
        .sortedByDescending { group -> group.sumOf(PlayRecord::listenedMs) }
        .map { group -> group.maxBy(PlayRecord::playedAt).toSong() }
    return mixes + liked + favourites
}

internal const val MIX_SONGS_KEPT = 15

/** Keep the start of each mix, taking one song from each in turn. */
internal fun mixStarts(mixes: List<List<Song>>): List<Song> =
    roundRobin(mixes.map { it.take(MIX_SONGS_KEPT) }).distinctBy(Song::videoId)
