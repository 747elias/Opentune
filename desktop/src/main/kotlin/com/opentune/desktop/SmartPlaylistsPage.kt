package com.opentune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.SavedFile
import com.opentune.data.history.PlayRecord
import com.opentune.data.library.SmartPlaylist
import com.opentune.data.library.SmartPlaylistRules
import com.opentune.data.library.SmartRules
import com.opentune.data.library.SmartSort
import com.opentune.data.library.SmartSource
import com.opentune.data.model.Song
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal data class SmartPlaylistKey(val id: String)

private object DesktopSmartPlaylists {
    private val file = SavedFile(File(appContext.filesDir, "smart-playlists.json"), "Desktop smart playlists")
    private val json = Json { ignoreUnknownKeys = true }
    private val _lists = MutableStateFlow(file.read(emptyList<SmartPlaylist>()) { json.decodeFromString<List<SmartPlaylist>>(it) })
    val lists = _lists.asStateFlow()

    @Synchronized
    fun put(playlist: SmartPlaylist): Boolean {
        val current = _lists.value
        val next = if (current.any { it.id == playlist.id }) current.map { if (it.id == playlist.id) playlist else it }
            else current + playlist
        return write(next)
    }

    @Synchronized
    fun remove(id: String): Boolean = write(_lists.value.filterNot { it.id == id })

    private fun write(next: List<SmartPlaylist>): Boolean = runCatching {
        file.write(json.encodeToString(next))
        _lists.value = next
    }.isSuccess
}

@Composable
internal fun SmartPlaylistsPage(onOpen: (String) -> Unit) {
    val smart by DesktopSmartPlaylists.lists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    ScrollPage("Smart playlists", spacing = 10.dp, trailing = {
        Button(onClick = { creating = true }) {
            Icon(Icons.Rounded.Add, null)
            Text("New smart playlist", Modifier.padding(start = 6.dp))
        }
    }) {
        if (smart.isEmpty()) item { DiscoveryNotice("Create a playlist that updates from your listening and library.") }
        items(smart, key = { it.id }) { playlist ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(playlist.id) }.padding(horizontal = GUTTER, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                    Text(playlist.rules.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (creating) {
        SmartRulesDialog(null, onClose = { creating = false }) { playlist ->
            if (!DesktopSmartPlaylists.put(playlist)) return@SmartRulesDialog false
            creating = false
            onOpen(playlist.id)
            true
        }
    }
}

@Composable
internal fun SmartPlaylistPage(id: String, onBack: () -> Unit, onSnapshot: (String) -> Unit) {
    val smart by DesktopSmartPlaylists.lists.collectAsState()
    val playlist = smart.firstOrNull { it.id == id }
    if (playlist == null) {
        ScrollPage("Smart playlist", spacing = 10.dp) {
            item { TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = GUTTER)) { Text("Back to library") } }
        }
        return
    }
    val songs = rememberSmartTracks(playlist)
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(false) }
    val covers = songs.orEmpty().mapNotNull { it.thumbnailUrl }.distinct().take(4)
    CollectionLayout(playlist.name, covers.firstOrNull(), songs, failed = false, empty = "No songs match these rules yet.") {
        CollectionHero(
            cover = { Mosaic(covers, it) }, coverUrl = covers.firstOrNull(), kind = "Smart playlist",
            title = playlist.name, subtitle = playlist.rules.summary(), songs = songs,
        ) {
            HoverIcon(Icons.Rounded.Edit, "Edit rules", size = 42.dp, iconSize = 20.dp) { editing = true }
            if (!songs.isNullOrEmpty()) {
                HoverIcon(Icons.Rounded.PlaylistAdd, "Save as playlist", size = 42.dp, iconSize = 20.dp) { snapshot = true }
                DownloadAllButton(songs)
            }
            HoverIcon(Icons.Rounded.Delete, "Delete playlist", size = 42.dp, iconSize = 20.dp) { deleting = true }
        }
    }
    if (editing) SmartRulesDialog(playlist, onClose = { editing = false }) { edited ->
        val saved = DesktopSmartPlaylists.put(edited)
        if (saved) editing = false
        saved
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false; failed = false },
        title = { Text("Delete ${playlist.name}?") },
        text = { if (failed) Text("Couldn't delete the smart playlist.") },
        confirmButton = { TextButton(onClick = {
            if (DesktopSmartPlaylists.remove(id)) {
                deleting = false
                onBack()
            } else failed = true
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false; failed = false }) { Text("Cancel") } },
    )
    if (snapshot && !songs.isNullOrEmpty()) {
        NameDialog("Save as playlist", playlist.name, "Save", onDismiss = { snapshot = false }) { name ->
            snapshot = false
            runCatching { Library.importPlaylist(name, songs) }.onSuccess(onSnapshot).onFailure { failed = true }
        }
    }
    if (failed && !deleting) AlertDialog(
        onDismissRequest = { failed = false },
        title = { Text("Couldn't save the playlist") },
        confirmButton = { TextButton(onClick = { failed = false }) { Text("OK") } },
    )
}

@Composable
private fun rememberSmartTracks(playlist: SmartPlaylist): List<Song>? {
    val revision by Library.playHistoryRevision.collectAsState()
    val liked by DesktopAccount.liked.collectAsState(initial = Library.liked.value)
    val playlists by Library.playlists.collectAsState()
    val finished = remember {
        OfflineDownloads.entries.map { it.filterValues { entry -> entry.state == OfflineDownloads.State.DONE } }.distinctUntilChanged()
    }
    val downloads by finished.collectAsState(initial = emptyMap())
    val history by produceState<List<PlayRecord>?>(null, revision) {
        value = withContext(Dispatchers.IO) { Library.history(Int.MAX_VALUE) }
    }
    val date by produceState(System.currentTimeMillis()) {
        val day = 86_400_000L
        while (true) {
            delay(day - value % day)
            value = System.currentTimeMillis()
        }
    }
    return produceState<List<Song>?>(null, playlist, history, liked, playlists, downloads, date) {
        val plays = history ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val files = downloads.mapNotNull { (id, entry) ->
                OfflineDownloads.audio(id)?.let { audio ->
                    id to SmartPlaylistRules.Download(entry.song, audio.file.lastModified(), audio.lossless)
                }
            }.toMap()
            val facts = SmartPlaylistRules.Facts(plays, liked, files, playlists.flatMap { list -> list.songs.map { it.toSong() } })
            SmartPlaylistRules.songs(playlist.rules, facts, now = date, seed = playlist.id.hashCode().toLong())
        }
    }.value
}

@Composable
private fun SmartRulesDialog(original: SmartPlaylist?, onClose: () -> Unit, onSave: (SmartPlaylist) -> Boolean) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var from by remember { mutableStateOf(original?.rules?.from ?: SmartSource.EVERYTHING) }
    var sort by remember { mutableStateOf(original?.rules?.sort ?: SmartSort.MOST_PLAYED) }
    var liked by remember { mutableStateOf(original?.rules?.likedOnly ?: false) }
    var downloaded by remember { mutableStateOf(original?.rules?.downloadedOnly ?: false) }
    var lossless by remember { mutableStateOf(original?.rules?.losslessOnly ?: false) }
    var artist by remember { mutableStateOf(original?.rules?.artist.orEmpty()) }
    var minPlays by remember { mutableStateOf(original?.rules?.minPlays?.takeIf { it > 0 }?.toString().orEmpty()) }
    var recent by remember { mutableStateOf(original?.rules?.playedWithinDays?.toString().orEmpty()) }
    var old by remember { mutableStateOf(original?.rules?.notPlayedForDays?.toString().orEmpty()) }
    var newest by remember { mutableStateOf(original?.rules?.newWithinDays?.toString().orEmpty()) }
    var limit by remember { mutableStateOf(if (original == null) "50" else original.rules.limit?.toString().orEmpty()) }
    var error by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && listOf(minPlays, recent, old, newest, limit).all { it.isBlank() || (it.toIntOrNull() ?: 0) > 0 }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (original == null) "New smart playlist" else "Edit smart playlist") },
        text = {
            Column(Modifier.heightIn(max = 445.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RuleChoice("Start from a preset", SmartPlaylistRules.presets.map { it.first.replace("this phone", "this computer") }) { index ->
                    val (preset, rules) = SmartPlaylistRules.presets[index]
                    name = preset.replace("this phone", "this computer")
                    from = rules.from
                    sort = rules.sort
                    liked = rules.likedOnly
                    downloaded = rules.downloadedOnly
                    lossless = rules.losslessOnly
                    artist = rules.artist
                    minPlays = rules.minPlays.takeIf { it > 0 }?.toString().orEmpty()
                    recent = rules.playedWithinDays?.toString().orEmpty()
                    old = rules.notPlayedForDays?.toString().orEmpty()
                    newest = rules.newWithinDays?.toString().orEmpty()
                    limit = rules.limit?.toString().orEmpty()
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                RuleChoice("From · ${from.label()}", SmartSource.entries.map { it.label() }) { from = SmartSource.entries[it] }
                RuleChoice("Sort · ${sort.label()}", SmartSort.entries.map { it.label() }) { sort = SmartSort.entries[it] }
                HorizontalDivider()
                OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth(), label = { Text("Artist contains") }, singleLine = true)
                RuleNumber(minPlays, { minPlays = it }, "Minimum plays")
                RuleNumber(recent, { recent = it }, "Played within (days)")
                RuleNumber(old, { old = it }, "Not played for (days)")
                RuleNumber(newest, { newest = it }, "First seen within (days)")
                RuleNumber(limit, { limit = it }, "Maximum songs")
                RuleToggle("Liked only", liked) { liked = it }
                RuleToggle("Downloaded only", downloaded) { downloaded = it }
                RuleToggle("Lossless only", lossless) { lossless = it }
                if (error) Text("Couldn't save the smart playlist", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val rules = SmartRules(from, minPlays.toIntOrNull() ?: 0, recent.toIntOrNull(), old.toIntOrNull(), newest.toIntOrNull(),
                    liked, downloaded, lossless, artist.trim(), sort, limit.toIntOrNull())
                val playlist = original?.copy(name = name.trim(), rules = rules) ?: SmartPlaylist(UUID.randomUUID().toString(), name.trim(), rules)
                error = !onSave(playlist)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun RuleChoice(label: String, options: List<String>, choose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, title ->
                DropdownMenuItem(text = { Text(title) }, onClick = { open = false; choose(index) })
            }
        }
    }
}

@Composable
private fun RuleNumber(value: String, set: (String) -> Unit, label: String) {
    OutlinedTextField(value, { input -> if (input.all(Char::isDigit)) set(input) }, Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true, isError = value.isNotEmpty() && (value.toIntOrNull() ?: 0) <= 0)
}

@Composable
private fun RuleToggle(label: String, value: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { set(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = value, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
        Text(label)
    }
}

private fun SmartSource.label() = when (this) {
    SmartSource.EVERYTHING -> "Everything"
    SmartSource.HISTORY -> "Play history"
    SmartSource.LIKED -> "Liked songs"
    SmartSource.DOWNLOADS -> "Downloads"
}

private fun SmartSort.label() = when (this) {
    SmartSort.MOST_PLAYED -> "Most played"
    SmartSort.RECENTLY_PLAYED -> "Recently played"
    SmartSort.LONGEST_UNPLAYED -> "Longest unplayed"
    SmartSort.NEWEST -> "Newest first"
    SmartSort.TITLE -> "Title, A to Z"
    SmartSort.ARTIST -> "Artist, A to Z"
    SmartSort.SHUFFLE -> "Shuffled each day"
}

private fun SmartRules.summary() = listOfNotNull(from.label(), sort.label(), artist.takeIf { it.isNotBlank() },
    "lossless".takeIf { losslessOnly }, limit?.let { "Up to $it songs" }).joinToString(" · ")
