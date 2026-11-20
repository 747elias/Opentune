package com.opentune.data.reco

import com.opentune.data.DebugLog as Log
import com.opentune.data.spotify.Spotify
import com.opentune.data.spotify.SpotifyLibrary
import com.opentune.data.spotify.SpotifyWebPlayer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

internal object SpotifyPersonalRadio {
    private const val TAG = "SpotifyRadio"
    private const val MIX_TTL_MS = 30 * 60_000L
    private class Mix(val tracks: List<Spotify.Track>, val at: Long)
    private val mixes = ConcurrentHashMap<String, Mix>()

    /** The signed-in listener's song radio, or null when the track cannot be matched. */
    suspend fun tracks(title: String, artist: String, album: String?, durationMs: Long): List<Spotify.Track>? {
        val cookie = SpotifyWebPlayer.account() ?: return null
        val account = MessageDigest.getInstance("SHA-256")
            .digest(cookie.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val key = "$account|${title.lowercase()}|${artist.lowercase()}|${album.orEmpty().lowercase()}|${durationMs / 1000}"
        mixes[key]?.takeIf { System.currentTimeMillis() - it.at < MIX_TTL_MS }?.let { return it.tracks }
        return try {
            val seed = SpotifyWebPlayer.findTrack(title, artist, album, durationMs, cookie) ?: return null
            val playlist = SpotifyWebPlayer.songRadio(seed.uri, cookie) ?: return null
            val (items, _) = SpotifyLibrary.tracks(cookie, playlist)
            items.filter { it.uri != seed.uri }.map { it.track }
                .also { tracks ->
                    if (tracks.isNotEmpty()) mixes[key] = Mix(tracks, System.currentTimeMillis())
                    mixes.entries.removeIf { System.currentTimeMillis() - it.value.at >= MIX_TTL_MS }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "personal radio for $artist – $title failed", e)
            null
        }
    }

    fun clear() = mixes.clear()
}
