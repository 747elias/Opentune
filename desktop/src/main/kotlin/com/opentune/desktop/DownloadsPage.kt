package com.opentune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import com.opentune.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.model.Song
import com.opentune.data.download.SMART_BATTERY_LIMIT
import com.opentune.data.download.SMART_SONG_LIMIT
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import kotlin.math.roundToInt

@Composable
internal fun DownloadTrackButton(song: Song) {
    if (!OfflineDownloads.supports(song)) return
    val changes = remember(song.videoId) { OfflineDownloads.entry(song.videoId) }
    val entry by changes.collectAsState(OfflineDownloads.entries.value[song.videoId])
    val id = song.videoId
    when (entry?.state) {
        OfflineDownloads.State.QUEUED, OfflineDownloads.State.DOWNLOADING ->
            HoverIcon(Icons.Rounded.Close, "Cancel download", size = 32.dp, iconSize = 18.dp) { OfflineDownloads.cancel(id) }
        OfflineDownloads.State.DONE ->
            HoverIcon(Icons.Rounded.DownloadDone, "Remove offline copy", size = 32.dp, iconSize = 18.dp) { OfflineDownloads.remove(id) }
        OfflineDownloads.State.FAILED ->
            HoverIcon(Icons.Rounded.Download, "Retry download", size = 32.dp, iconSize = 18.dp) { OfflineDownloads.retry(id) }
        null -> HoverIcon(Icons.Rounded.Download, "Download", size = 32.dp, iconSize = 18.dp) { OfflineDownloads.enqueue(song) }
    }
}

@Composable
internal fun DownloadsPage() {
    val entries by OfflineDownloads.entries.collectAsState()
    val error by OfflineDownloads.error.collectAsState()
    val smartWaiting by DesktopSmartDownloads.waiting.collectAsState()
    val ready = remember(entries) {
        entries.values.filter { it.state == OfflineDownloads.State.DONE && OfflineDownloads.audio(it.song.videoId) != null }
            .asReversed().map(OfflineDownloads::playableSong)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = LocalPageTop.current + 20.dp, bottom = LocalPageBottom.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Downloads", style = MaterialTheme.typography.displaySmall) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (smartWaiting) item {
            Text("Smart downloads are waiting for an unmetered connection, enough battery and free space.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (entries.isEmpty()) {
            item { Message("No downloads yet", "Choose Download from a song, album or playlist to listen offline.", Icons.Rounded.Download) }
        }
        if (ready.isNotEmpty()) {
            item {
                Button(onClick = { Player.playAll(ready, 0) }) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                    Text("Play downloads", Modifier.padding(start = 8.dp))
                }
            }
        }
        items(entries.values.reversed(), key = { it.song.videoId }) { entry ->
            val index = ready.indexOfFirst { it.videoId == entry.song.videoId }
            val song = ready.getOrNull(index) ?: entry.song
            val present = entry.state == OfflineDownloads.State.DONE && index >= 0
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = present) { Player.playAll(ready, index) }.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(song.thumbnailUrl, 48.dp, RoundedCornerShape(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when (entry.state) {
                        OfflineDownloads.State.QUEUED -> "Queued"
                        OfflineDownloads.State.DOWNLOADING -> if (entry.progress > 0f) "Downloading ${(entry.progress * 100).toInt()}%" else "Downloading"
                        OfflineDownloads.State.DONE -> if (present) entry.detail ?: "Downloaded" else "Audio file is missing"
                        OfflineDownloads.State.FAILED -> entry.error ?: "Download failed"
                    }
                    Text(
                        listOfNotNull(song.artist.takeIf(String::isNotBlank), status, "Smart download".takeIf { entry.smartDownload })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.state == OfflineDownloads.State.DOWNLOADING) {
                        if (entry.progress > 0f) LinearProgressIndicator(progress = { entry.progress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (entry.smartDownload) {
                    TextButton(onClick = { OfflineDownloads.enqueue(entry.song) }) { Text("Keep offline") }
                }
                when (entry.state) {
                    OfflineDownloads.State.QUEUED, OfflineDownloads.State.DOWNLOADING -> {
                        TextButton(onClick = { OfflineDownloads.cancel(song.videoId) }) { Text("Cancel") }
                    }
                    OfflineDownloads.State.FAILED, OfflineDownloads.State.DONE -> {
                        if (!present && !entry.smartDownload) TextButton(onClick = { OfflineDownloads.enqueue(song) }) { Text("Retry") }
                        TextButton(onClick = { OfflineDownloads.remove(song.videoId) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DownloadAllButton(songs: List<Song>, modifier: Modifier = Modifier) {
    val wanted = remember(songs) { songs.filter(OfflineDownloads::supports).distinctBy { it.videoId } }
    if (wanted.isEmpty()) return
    val entries by OfflineDownloads.entries.collectAsState()
    val present = wanted.mapNotNull { entries[it.videoId] }
    val pending = present.filter { it.state == OfflineDownloads.State.DOWNLOADING || it.state == OfflineDownloads.State.QUEUED }
    val done = present.count { it.state == OfflineDownloads.State.DONE }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { if (present.isEmpty()) OfflineDownloads.enqueueAll(wanted) else open = true }) {
            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
            Text(
                when {
                    pending.isNotEmpty() -> "$done of ${wanted.size} downloaded"
                    done == wanted.size -> "Downloaded"
                    else -> "Download all"
                },
                Modifier.padding(start = 6.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (pending.size + done < wanted.size) {
                DropdownMenuItem(text = { Text("Download remaining") }, onClick = { open = false; OfflineDownloads.enqueueAll(wanted) })
            }
            if (present.any(OfflineDownloads.Entry::smartDownload)) {
                DropdownMenuItem(text = { Text("Keep all offline") }, onClick = { open = false; OfflineDownloads.enqueueAll(wanted) })
            }
            if (pending.isNotEmpty()) {
                DropdownMenuItem(text = { Text("Cancel downloads") }, onClick = {
                    open = false
                    OfflineDownloads.cancelAll(pending.mapTo(mutableSetOf()) { it.song.videoId })
                })
            }
            if (done > 0) {
                DropdownMenuItem(text = { Text("Remove offline copies") }, onClick = {
                    open = false
                    val downloaded = present.filter { it.state == OfflineDownloads.State.DONE }.mapTo(mutableSetOf()) { it.song.videoId }
                    OfflineDownloads.removeAll(downloaded)
                })
            }
        }
    }
}

@Composable
internal fun DownloadPreferences() {
    val library by AppSettings.library.collectAsState()
    val playback by AppSettings.playback.collectAsState()
    val quality = library.downloadQuality.takeUnless { it == AudioQuality.MAX } ?: AudioQuality.HIGH
    Text("Download quality", style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(AudioQuality.LOW, AudioQuality.NORMAL, AudioQuality.HIGH).forEach { option ->
            FilterChip(
                selected = quality == option,
                onClick = { AppSettings.updateLibrary { it.copy(downloadQuality = option) } },
                label = { Text(option.label) },
            )
        }
    }
    SwitchRow("Stream downloaded songs when online", "The Downloads page always plays the saved copy.", playback.streamDownloadsOnline) { enabled ->
        AppSettings.updatePlayback { it.copy(streamDownloadsOnline = enabled) }
    }
    if (DesktopWindow.isMac) {
        SwitchRow("Smart downloads", "Automatically save your favourites on unmetered Wi-Fi or Ethernet.", library.smartDownloads) { enabled ->
            AppSettings.updateLibrary { it.copy(smartDownloads = enabled) }
        }
        if (library.smartDownloads) {
            SwitchRow("Keep today's mixes offline", "Save the start of today's daylist, daily, genre and Discover mixes.", library.smartDownloadMixes) { enabled ->
                AppSettings.updateLibrary { it.copy(smartDownloadMixes = enabled) }
            }
            SmartDownloadLimit(library.smartDownloadLimit, SMART_SONG_LIMIT, 10, { "Smart download limit · $it songs" }) { limit ->
                AppSettings.updateLibrary { it.copy(smartDownloadLimit = limit) }
            }
            SmartDownloadLimit(library.smartDownloadMinBattery, SMART_BATTERY_LIMIT, 5, { "Minimum battery · $it%" }) { battery ->
                AppSettings.updateLibrary { it.copy(smartDownloadMinBattery = battery) }
            }
        }
    }
}

@Composable
private fun SmartDownloadLimit(saved: Int, range: IntRange, step: Int, label: (Int) -> String, save: (Int) -> Unit) {
    var value by remember(saved) { mutableStateOf(saved.coerceIn(range).toFloat()) }
    Column {
        Text(label(value.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { save(value.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
        )
    }
}
