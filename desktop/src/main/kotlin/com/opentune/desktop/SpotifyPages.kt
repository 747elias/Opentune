package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.ui.theme.Text
import java.net.URI

internal data object SpotifyLibraryKey

@Composable
internal fun SpotifyAccountSection() {
    val state by DesktopSpotify.state.collectAsState()
    var clientId by remember(state.clientId) { mutableStateOf(state.clientId) }
    val clipboard = LocalClipboardManager.current
    val profile = state.profile
    val signingIn = state.busy == DesktopSpotify.Work.SIGN_IN
    if (profile != null) {
        Text("Connected as ${profile.name?.takeIf(String::isNotBlank) ?: profile.id}")
        Text("Open Spotify from your Library to view your playlists and liked tracks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = DesktopSpotify::signOut) { Text("Disconnect Spotify") }
    } else {
        Text(
            "Set up a Spotify Web API app in the Spotify Dashboard. Register the redirect URI below and add your account to the app’s user list if it uses Development Mode.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(SpotifyApi.REDIRECT_URI, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { clipboard.setText(AnnotatedString(SpotifyApi.REDIRECT_URI)) }) { Text("Copy redirect URI") }
        }
        var browserProblem by remember { mutableStateOf(false) }
        TextButton(onClick = {
            browserProblem = runCatching { DesktopSpotify.openInBrowser(URI("https://developer.spotify.com/dashboard")) }.isFailure
        }) { Text("Open Spotify Dashboard") }
        if (browserProblem) Text("Open developer.spotify.com/dashboard in your browser.", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            clientId, { clientId = it }, enabled = !signingIn, singleLine = true, label = { Text("Spotify Client ID") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (signingIn) {
            Text("Finish signing in to Spotify in your browser.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = DesktopSpotify::cancelSignIn) { Text("Cancel") }
        } else {
            Button(enabled = SpotifyApi.validClientId(clientId.trim()), onClick = { DesktopSpotify.signIn(clientId) }) { Text("Connect Spotify") }
        }
    }
    state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
internal fun SpotifyLibraryPage(onSettings: () -> Unit) {
    val state by DesktopSpotify.state.collectAsState()
    val selected = state.selected
    val busy = state.busy != null
    val importer by DesktopPlaylistImport.progress.collectAsState()
    var importedPlaylist by remember(state.profile) { mutableStateOf<String?>(null) }
    val liked = remember(state.liked) { state.liked.mapNotNullTo(hashSetOf(), SpotifyApi.Track::id) }
    LaunchedEffect(state.profile) {
        if (state.profile != null && !state.loaded) DesktopSpotify.loadLibrary()
    }
    ScrollPage(selected?.name ?: "Spotify", spacing = 8.dp) {
        if (state.profile == null) {
            item { Message("Connect Spotify", state.problem ?: "Connect an account to see your Spotify playlists and liked tracks.", action = "Settings" to onSettings) }
        } else {
            item {
                Column(Modifier.padding(horizontal = GUTTER), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (selected == null) {
                        OutlinedButton(enabled = !busy, onClick = DesktopSpotify::loadLibrary) {
                            Text(if (state.busy == DesktopSpotify.Work.LIBRARY) "Refreshing…" else "Refresh Spotify library")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = DesktopSpotify::closePlaylist) { Text("Spotify library") }
                            val tracks = state.tracks.orEmpty()
                            Button(enabled = tracks.isNotEmpty() && importer?.working != true, onClick = {
                                importedPlaylist = selected.id
                                DesktopPlaylistImport.spotify(selected.name, tracks.map(SpotifyApi.Track::metadata))
                            }) { Text("Copy playlist to OpenTune") }
                            if (state.tracks == null && !busy) {
                                OutlinedButton(onClick = { DesktopSpotify.openPlaylist(selected) }) { Text("Retry") }
                            }
                        }
                        if (importedPlaylist == selected.id) {
                            importer?.let { progress ->
                                Text(progress.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (progress.working) TextButton(onClick = DesktopPlaylistImport::cancel) { Text("Cancel copy") }
                            }
                        }
                        if (state.busy == DesktopSpotify.Work.PLAYLIST) Text("Loading playlist…")
                        if (state.tracks?.isEmpty() == true) Text("This playlist has no available tracks.")
                    }
                    state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (selected == null && state.loaded) {
                item { SpotifyHeading("Playlists") }
                if (state.playlists.isEmpty()) item { SpotifyHeading("Your Spotify library has no playlists.", heading = false) }
                items(state.playlists, key = { "playlist-${it.id}" }) { playlist ->
                    TextButton(enabled = !busy, onClick = { DesktopSpotify.openPlaylist(playlist) }, modifier = Modifier.padding(horizontal = GUTTER)) {
                        Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item { SpotifyHeading("Liked tracks") }
                if (state.liked.isEmpty()) item { SpotifyHeading("Your Spotify library has no liked tracks.", heading = false) }
            }
            val tracks = if (selected == null) state.liked else state.tracks.orEmpty()
            items(tracks) { track ->
                val isLiked = track.id in liked
                Row(Modifier.fillMaxWidth().padding(horizontal = GUTTER), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artists.joinToString { it.name }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(enabled = !busy, onClick = { DesktopSpotify.setLiked(track, !isLiked) }) {
                        Icon(
                            if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (isLiked) "Remove from Spotify liked tracks" else "Add to Spotify liked tracks",
                            tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyHeading(text: String, heading: Boolean = true) {
    Text(
        text, modifier = Modifier.padding(start = GUTTER, end = GUTTER, top = if (heading) 16.dp else 4.dp),
        style = if (heading) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
        color = if (heading) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
