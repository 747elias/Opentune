package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LocalMusicPage() {
    val songs by Library.localTracks.collectAsState()
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun add(folders: Boolean) {
        scanning = true
        scope.launch {
            try {
                val selected = DesktopLocalMusic.chooseFiles(folders)
                if (selected.isEmpty()) return@launch
                status = "Reading audio files…"
                val scan = DesktopLocalMusic.scan(selected)
                if (scan.songs.isNotEmpty()) {
                    withContext(Dispatchers.IO) { Library.addLocalTracks(scan.songs) }
                }
                status = when {
                    scan.songs.isEmpty() && scan.skipped == 0 -> "No supported audio files found."
                    scan.songs.isEmpty() -> "The selected audio files couldn’t be read."
                    scan.skipped > 0 -> "Couldn’t read ${scan.skipped} files or folders."
                    else -> null
                }
            } finally {
                scanning = false
            }
        }
    }

    ScrollPage("Local files", lengthLine(songs).takeIf { songs.isNotEmpty() }, spacing = 8.dp) {
        item(key = "local-actions") {
            Row(Modifier.padding(horizontal = GUTTER, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { add(folders = false) }, enabled = !scanning) { Text("Add files") }
                OutlinedButton(onClick = { add(folders = true) }, enabled = !scanning) { Text("Add folder") }
                if (songs.isNotEmpty()) Button(onClick = { Player.playAll(songs, 0) }) { Text("Play all") }
            }
        }
        status?.let { message ->
            item(key = "local-status") { Text(message, Modifier.padding(horizontal = GUTTER), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (songs.isEmpty()) {
            item(key = "local-empty") {
                Message("No local music yet", "Add audio files or a folder to play music already on this computer.", Icons.Rounded.Folder)
            }
        } else {
            item(key = "local-track-head") { TrackHeader() }
            tracks(songs) { Library.removeLocalTrack(it.videoId) }
        }
    }
}
