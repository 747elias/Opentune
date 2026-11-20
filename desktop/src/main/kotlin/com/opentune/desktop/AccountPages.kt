package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import com.opentune.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.account.AccountStore
import com.opentune.data.account.PairingReceiver
import com.opentune.data.history.ListeningHistoryTarget
import com.opentune.data.innertube.Innertube
import com.opentune.data.model.AccountChannel
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext

internal data object AccountLibraryKey
internal data class AccountPlaylistKey(val item: ShelfItem, val account: Int = DesktopAccount.revision.value)

@Composable
internal fun YouTubeAccountSection() {
    val accounts by AccountStore.accounts.collectAsState()
    val active by AccountStore.account.collectAsState()
    val listeningAs by AccountStore.listeningAs.collectAsState()
    val signedIn by DesktopAccount.signedIn.collectAsState()
    val library by DesktopAccount.library.collectAsState()
    val playback by AppSettings.playback.collectAsState()
    var linking by remember { mutableStateOf(false) }
    var signingInOnMac by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }
    var choosingChannel by remember { mutableStateOf(false) }
    val macLogin = remember { MacYouTubeLogin.available }
    val name = listeningAs ?: active?.name

    Text(
        if (signedIn) name?.let { "Signed in as $it" } ?: "YouTube Music account connected"
        else if (macLogin) "Sign in on the YouTube Music website to open your account’s library."
        else "Use OpenTune on your Android phone to sign in and access your YouTube Music library.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (signedIn && listeningAs == null) active?.email?.takeIf { it.isNotBlank() }?.let {
        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (macLogin) {
            Button(onClick = { signInError = null; signingInOnMac = true }) {
                Text(if (signedIn) "Add another account" else "Sign in on this Mac")
            }
            OutlinedButton(onClick = { linking = true }) { Text("Sign in with Android") }
        } else {
            Button(onClick = { linking = true }) { Text(if (signedIn) "Link another account" else "Sign in with Android") }
        }
    }
    signInError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (signedIn) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { choosingChannel = true }) { Text("Listen as") }
            TextButton(onClick = DesktopAccount::signOut) { Text("Sign out") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sync listening history")
                Text("Send listened songs to your YouTube Music account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = playback.listeningHistoryTarget == ListeningHistoryTarget.YOUTUBE_MUSIC,
                enabled = !playback.pauseListeningHistory,
                onCheckedChange = { enabled ->
                    AppSettings.updatePlayback { it.copy(listeningHistoryTarget = if (enabled) ListeningHistoryTarget.YOUTUBE_MUSIC else ListeningHistoryTarget.DEVICE_ONLY) }
                },
            )
        }
        TextButton(onClick = DesktopAccount::refresh, enabled = !library.loading) { Text("Refresh YouTube Music library") }
        library.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (signingInOnMac) {
        LaunchedEffect(Unit) {
            try {
                MacYouTubeLogin.signIn()?.let { DesktopAccount.acceptSession(it) }
            } catch (_: TimeoutCancellationException) {
                signInError = "Sign-in timed out. Open a new sign-in window and try again."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                signInError = "Couldn’t finish signing in. Try again or connect from your Android phone."
            } finally {
                signingInOnMac = false
            }
        }
        AlertDialog(
            onDismissRequest = { signingInOnMac = false },
            title = { Text("Sign in to YouTube Music") },
            text = { Text("Complete sign-in in the YouTube Music window. If Google declines sign-in there, you can connect using the Android app.") },
            confirmButton = { TextButton(onClick = { signingInOnMac = false }) { Text("Cancel") } },
        )
    }
    accounts.filterNot { it.active }.forEach { account ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(account.name.ifBlank { account.email.ifBlank { "YouTube Music account" } }, Modifier.weight(1f))
            TextButton(onClick = { DesktopAccount.switchTo(account.id) }) { Text("Switch") }
            TextButton(onClick = { DesktopAccount.removeAccount(account.id) }) { Text("Remove") }
        }
    }
    if (linking) PairAccountDialog(onClose = { linking = false })
    if (choosingChannel) AccountChannelDialog(onClose = { choosingChannel = false })
}

@Composable
private fun PairAccountDialog(onClose: () -> Unit) {
    var attempt by remember { mutableStateOf(0) }
    val opening = remember(attempt) { runCatching { PairingReceiver() } }
    val listener = opening.getOrNull()
    var error by remember(attempt) { mutableStateOf(if (listener == null) "Connect your computer and phone to the same local network." else null) }
    DisposableEffect(listener) {
        onDispose { listener?.close() }
    }
    LaunchedEffect(listener) {
        if (listener == null) return@LaunchedEffect
        try {
            val cookie = withContext(Dispatchers.IO) {
                var received: String? = null
                listener.receive { received = it; true }
                checkNotNull(received)
            }
            DesktopAccount.acceptSession(cookie)
            onClose()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "This code expired or the connection was interrupted. Generate a new code."
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sign in to YouTube Music") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("On your Android phone, open OpenTune, go to Settings › Link a device and scan this code. Approve the connection on the phone.")
                if (listener != null && error == null) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCode(listener.offer.link, 200.dp)
                    }
                    Text("This code expires after two minutes. Keep both devices on the same local network.")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (error != null) TextButton(onClick = { attempt++ }) { Text("New code") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun AccountChannelDialog(onClose: () -> Unit) {
    val channels by produceState<List<AccountChannel>?>(null) { value = AccountStore.profiles() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Listen as") },
        text = {
            when {
                channels == null -> CircularProgressIndicator()
                channels.orEmpty().isEmpty() -> Text("YouTube Music did not return any channels for this sign-in.")
                else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(channels.orEmpty(), key = { it.key }) { channel ->
                        TextButton(onClick = {
                            DesktopAccount.listenAs(channel, own = channel.pageId == null)
                            onClose()
                        }) { Text(channel.name) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

private enum class AccountShelf(val label: String) {
    PLAYLISTS("Playlists"),
    LIKED("Liked songs"),
    HISTORY("Listening history"),
}

@Composable
internal fun AccountLibraryPage(onOpenAny: (Any) -> Unit, onSettings: () -> Unit) {
    val data by DesktopAccount.library.collectAsState()
    val signedIn by DesktopAccount.signedIn.collectAsState()
    val revision by DesktopAccount.revision.collectAsState()
    var shelf by remember { mutableStateOf(AccountShelf.PLAYLISTS) }
    var naming by remember { mutableStateOf(false) }
    var historyRetry by remember { mutableStateOf(0) }
    val history by produceState<Result<List<Pair<String, List<Song>>>>?>(null, revision, shelf, historyRetry) {
        value = null
        if (signedIn && shelf == AccountShelf.HISTORY) value = loadAccountPage(DesktopAccount::history)
    }
    ScrollPage("YouTube Music library") {
        if (!signedIn) {
            item { Message("Sign in to YouTube Music", "Connect your account to open its library.", action = "Open settings" to onSettings) }
        } else {
            item {
                Row(Modifier.padding(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccountShelf.entries.forEach { tab ->
                        FilterChip(shelf == tab, { shelf = tab }, label = { Text(tab.label) })
                    }
                    TextButton(onClick = {
                        if (shelf == AccountShelf.HISTORY) historyRetry++ else DesktopAccount.refresh()
                    }, enabled = !data.loading) { Text("Refresh") }
                }
            }
            if (shelf != AccountShelf.HISTORY) {
                data.problem?.let { problem -> item { Message(problem, null) } }
                if (data.loading && data.liked.isEmpty() && data.playlists.isEmpty()) item { AccountLoading() }
            }
            when (shelf) {
                AccountShelf.PLAYLISTS -> {
                    item {
                        Row(Modifier.padding(horizontal = GUTTER)) {
                            OutlinedButton(onClick = { naming = true }) { Text("New YouTube Music playlist") }
                        }
                    }
                    if (data.playlists.isEmpty() && !data.loading) item { Message("No account playlists yet", null) }
                    else item {
                        ShelfRow(Pages.Shelf("Saved playlists", data.playlists)) { onOpenAny(AccountPlaylistKey(it)) }
                    }
                }
                AccountShelf.LIKED -> {
                    if (data.liked.isEmpty() && !data.loading) item { Message("No liked songs yet", null) }
                    itemsIndexed(data.liked, key = { _, song -> song.videoId }) { index, song ->
                        TrackRow(index, song, data.liked)
                    }
                }
                AccountShelf.HISTORY -> when {
                    history == null -> item { AccountLoading() }
                    history?.isFailure == true -> item { Message("Couldn't load your YouTube Music history", "Check your connection and try again.") }
                    history?.getOrNull().isNullOrEmpty() -> item { Message("No account history yet", null) }
                    else -> history?.getOrNull().orEmpty().forEach { (name, songs) ->
                        item {
                            Text(name, Modifier.padding(horizontal = GUTTER), style = MaterialTheme.typography.titleLarge)
                        }
                        itemsIndexed(songs) { index, song ->
                            TrackRow(index, song, songs)
                        }
                    }
                }
            }
        }
    }
    if (naming && signedIn) {
        NameDialog("New YouTube Music playlist", "", "Create", onDismiss = { naming = false }) { title ->
            naming = false
            DesktopAccount.editPlaylist("Couldn't create your YouTube Music playlist.", edit = {
                Innertube.createPlaylist(title.trim(), PlaylistPrivacy.PRIVATE)
            }) { id ->
                onOpenAny(AccountPlaylistKey(ShelfItem(title.trim(), "YouTube Music", null, null, "VL${id.removePrefix("VL")}")))
            }
        }
    }
}

@Composable
internal fun AccountPlaylistPage(key: AccountPlaylistKey, onBack: () -> Unit) {
    val browseId = key.item.browseId ?: return
    val revision by DesktopAccount.revision.collectAsState()
    val signedIn by DesktopAccount.signedIn.collectAsState()
    if (!signedIn || key.account != revision) {
        ScrollPage("YouTube Music") { item { Message("Open this account’s library to choose a playlist", null, action = "Back" to onBack) } }
        return
    }
    var reload by remember(browseId) { mutableStateOf(0) }
    val result by produceState<Result<DesktopAccount.Playlist>?>(null, browseId, revision, reload) {
        value = null
        if (signedIn) value = loadAccountPage { DesktopAccount.playlist(browseId) }
    }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val playlist = result?.getOrNull()
    val title = playlist?.title?.ifBlank { null } ?: key.item.title
    val cover = playlist?.cover ?: key.item.thumbnailUrl
    val songs = playlist?.songs
    val canRemove = playlist?.editable == true && songs.orEmpty().any { it.setVideoId != null }
    CollectionLayout(
        title, cover, songs, result?.isFailure == true,
        onRemove = if (canRemove) ({ song ->
            val entry = song.setVideoId
            if (entry != null) DesktopAccount.editPlaylist("Couldn't remove this song from YouTube Music.", edit = {
                Innertube.removeFromPlaylist(browseId, listOf(entry to song.videoId))
            }) { reload++ }
        }) else null,
        empty = "No songs in this playlist yet",
    ) {
        CollectionHero(
            cover = { modifier -> Mosaic(listOfNotNull(cover), modifier) },
            coverUrl = cover,
            kind = "YouTube Music playlist",
            title = title,
            subtitle = playlist?.privacy?.label.orEmpty(),
            songs = songs.orEmpty(),
        ) {
            HoverIcon(Icons.Rounded.Refresh, "Refresh playlist", size = 42.dp, iconSize = 20.dp) { reload++ }
            if (playlist?.editable == true) {
                HoverIcon(Icons.Rounded.Edit, "Rename playlist", size = 42.dp, iconSize = 20.dp) { renaming = true }
                HoverIcon(Icons.Rounded.Delete, "Delete playlist", size = 42.dp, iconSize = 20.dp) { deleting = true }
            }
        }
    }
    if (renaming) {
        NameDialog("Rename YouTube Music playlist", title, "Rename", onDismiss = { renaming = false }) { name ->
            renaming = false
            DesktopAccount.editPlaylist("Couldn't rename this playlist on YouTube Music.", edit = {
                Innertube.renamePlaylist(browseId, name.trim())
            }) { reload++ }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete $title?") },
            text = { Text("This removes the playlist from your YouTube Music account.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    DesktopAccount.editPlaylist("Couldn't delete this playlist from YouTube Music.", edit = {
                        Innertube.deletePlaylist(browseId)
                    }) { onBack() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AccountLoading() {
    Row(Modifier.padding(horizontal = GUTTER, vertical = 12.dp)) { CircularProgressIndicator() }
}

private suspend fun <T> loadAccountPage(load: suspend () -> T): Result<T> = try {
    Result.success(load())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
