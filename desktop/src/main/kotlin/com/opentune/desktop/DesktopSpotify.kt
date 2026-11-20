package com.opentune.desktop

import android.content.Context
import android.content.SharedPreferences
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The optional Spotify Web API connection, separate from the YouTube Music account. */
internal object DesktopSpotify {
    enum class Work { SIGN_IN, LIBRARY, PLAYLIST, LIKE }

    data class State(
        val clientId: String = "",
        val profile: SpotifyApi.Profile? = null,
        val playlists: List<SpotifyApi.Playlist> = emptyList(),
        val liked: List<SpotifyApi.Track> = emptyList(),
        val selected: SpotifyApi.Playlist? = null,
        val tracks: List<SpotifyApi.Track>? = null,
        val loaded: Boolean = false,
        val busy: Work? = null,
        val problem: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = SpotifyApi()
    private var prefs: SharedPreferences? = null
    private var refresh: String? = null
    private var access: String? = null
    private var expiresAt = 0L
    private var job: Job? = null
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun init(context: Context) {
        val storage = context.getSharedPreferences("spotify", Context.MODE_PRIVATE)
        prefs = storage
        val clientId = storage.getString("client_id", null)?.takeIf(SpotifyApi::validClientId)
            ?: System.getenv("OPENTUNE_SPOTIFY_CLIENT_ID")?.takeIf(SpotifyApi::validClientId).orEmpty()
        refresh = storage.getString("refresh_token", null)?.takeIf { it.isNotBlank() && clientId.isNotBlank() }
        val profile = storage.getString("profile_id", null)?.takeIf { refresh != null }
            ?.let { SpotifyApi.Profile(it, storage.getString("profile_name", null)) }
        _state.value = State(clientId = clientId, profile = profile)
    }

    fun signIn(clientId: String) = run(Work.SIGN_IN) {
        val id = clientId.trim()
        require(SpotifyApi.validClientId(id)) { "Enter the 32-character Spotify Client ID." }
        requireStorage().edit().putString("client_id", id).commit()
        _state.update { it.copy(clientId = id) }
        val token = api.signIn(id, ::openInBrowser)
        val profile = api.profile(token.access)
        currentCoroutineContext().ensureActive()
        val refreshToken = token.refresh?.takeIf(String::isNotBlank)
            ?: throw IOException("Spotify didn't return a renewable session. Try signing in again.")
        val saved = requireStorage().edit().putString("client_id", id).putString("refresh_token", refreshToken)
            .putString("profile_id", profile.id).putString("profile_name", profile.name).commit()
        if (!saved) throw IOException("Couldn't save the Spotify session on this Mac.")
        refresh = refreshToken
        hold(token)
        _state.update { State(clientId = id, profile = profile, busy = it.busy) }
    }

    fun cancelSignIn() {
        if (_state.value.busy != Work.SIGN_IN) return
        cancel()
        _state.update { it.copy(busy = null, problem = null) }
    }

    fun signOut() {
        cancel()
        clearSession()
    }

    fun loadLibrary() = run(Work.LIBRARY) {
        if (_state.value.profile == null) return@run
        val (playlists, liked) = withAccess { api.playlists(it) to api.liked(it) }
        _state.update { it.copy(playlists = playlists.distinctBy(SpotifyApi.Playlist::id), liked = liked.distinctBy(SpotifyApi.Track::id), loaded = true) }
    }

    fun openPlaylist(playlist: SpotifyApi.Playlist) = run(Work.PLAYLIST) {
        if (_state.value.profile == null) return@run
        _state.update { it.copy(selected = playlist, tracks = null) }
        val tracks = withAccess { api.tracks(it, playlist.id) }
        _state.update { it.copy(tracks = tracks) }
    }

    fun closePlaylist() {
        if (_state.value.busy == Work.PLAYLIST) cancel()
        _state.update {
            it.copy(selected = null, tracks = null, busy = it.busy.takeUnless { work -> work == Work.PLAYLIST }, problem = null)
        }
    }

    fun setLiked(track: SpotifyApi.Track, liked: Boolean) = run(Work.LIKE) {
        val id = track.id ?: return@run
        withAccess { api.setLiked(it, id, liked) }
        _state.update { current ->
            val remaining = current.liked.filterNot { it.id == id }
            current.copy(liked = if (liked) listOf(track) + remaining else remaining)
        }
    }

    private fun run(work: Work, task: suspend () -> Unit) {
        if (job?.isCompleted == false) return
        _state.update { it.copy(busy = work, problem = null) }
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                task()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                _state.update { it.copy(problem = error.message ?: "Couldn't connect to Spotify.") }
            } catch (_: Exception) {
                _state.update { it.copy(problem = "Couldn't complete the Spotify request. Check the setup and try again.") }
            } finally {
                if (job == currentCoroutineContext()[Job]) {
                    job = null
                    _state.update { it.copy(busy = null) }
                }
            }
        }
        job = next
        next.start()
    }

    private fun cancel() {
        val running = job
        job = null
        running?.cancel()
    }

    private suspend fun <T> withAccess(request: suspend (String) -> T): T {
        val current = access?.takeIf { System.currentTimeMillis() < expiresAt } ?: renew()
        val result = try {
            request(current)
        } catch (error: SpotifyApi.Problem) {
            if (error.status != 401) throw error
            request(renew())
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun renew(): String {
        val oldRefresh = refresh ?: throw IOException("Connect your Spotify account first.")
        val token = try {
            api.refresh(_state.value.clientId, oldRefresh)
        } catch (error: SpotifyApi.Problem) {
            if (error.status !in setOf(400, 401)) throw error
            clearSession()
            throw IOException("Spotify has signed you out. Reconnect your account.")
        }
        currentCoroutineContext().ensureActive()
        token.refresh?.takeIf(String::isNotBlank)?.let { replacement ->
            if (replacement != oldRefresh) {
                refresh = replacement
                requireStorage().edit().putString("refresh_token", replacement).commit()
            }
        }
        hold(token)
        return token.access
    }

    private fun hold(token: SpotifyApi.Token) {
        access = token.access
        expiresAt = System.currentTimeMillis() + (token.expiresIn - 30).coerceAtLeast(0) * 1_000L
    }

    private fun clearSession() {
        refresh = null
        access = null
        expiresAt = 0L
        val cleared = prefs?.edit()?.remove("refresh_token")?.remove("profile_id")?.remove("profile_name")?.commit()
        _state.update { State(clientId = it.clientId, problem = if (cleared == false) "Couldn't remove the saved Spotify session." else null) }
    }

    private fun requireStorage() = prefs ?: throw IOException("Spotify settings couldn't be opened.")

    fun openInBrowser(uri: URI) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw IOException("OpenTune couldn't open your default browser.")
        }
        try {
            Desktop.getDesktop().browse(uri)
        } catch (_: IOException) {
            throw IOException("OpenTune couldn't open your default browser.")
        }
    }
}
