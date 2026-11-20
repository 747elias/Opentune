package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.opentune.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opentune.data.listenbrainz.ListenBrainz
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BackupSettings() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun transfer(export: Boolean) {
        busy = true
        scope.launch {
            try {
                val file = if (export) desktopFile("Save OpenTune backup", "OpenTune-backup.json") else desktopFile("Open OpenTune backup")
                if (file != null) {
                    withContext(Dispatchers.IO) { if (export) DesktopBackup.export(file) else DesktopBackup.restore(file) }
                    message = if (export) "Backup saved." else "Backup imported. Playlists and history were merged."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.localizedMessage ?: "Couldn't read or write the backup."
            } finally {
                busy = false
            }
        }
    }
    Text("Save your settings, listening history, likes and playlists to a JSON file. Login credentials stay on this computer.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { transfer(true) }, enabled = !busy) { Text("Export backup") }
        OutlinedButton(onClick = { transfer(false) }, enabled = !busy) { Text("Import backup") }
    }
    message?.let { Text(it) }
}

@Composable
internal fun PlaylistImportSettings() {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    val progress by DesktopPlaylistImport.progress.collectAsState()
    val working = progress?.working == true
    OutlinedTextField(link, { link = it }, label = { Text("YouTube Music or Spotify link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { DesktopPlaylistImport.link(link.trim()) }, enabled = link.isNotBlank() && !working) { Text("Import link") }
        OutlinedButton(onClick = { scope.launch { desktopFile("Import a playlist CSV or ZIP")?.let(DesktopPlaylistImport::file) } }, enabled = !working) {
            Text("Open CSV or ZIP")
        }
        if (working) OutlinedButton(onClick = DesktopPlaylistImport::cancel) { Text("Cancel") }
    }
    progress?.let { item ->
        Text(item.message)
        if (item.working) {
            if (item.total > 0) LinearProgressIndicator(progress = { item.done.toFloat() / item.total }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (item.missed.isNotEmpty()) {
            var expanded by remember(item) { mutableStateOf(false) }
            OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide unmatched tracks" else "Show ${item.missed.size} unmatched tracks") }
            if (expanded) Column { item.missed.forEach { Text(it) } }
        }
    }
}

@Composable
internal fun ListenBrainzSettings() {
    val connected by ListenBrainz.account.collectAsState()
    val queued by ListenBrainz.queued.collectAsState()
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    connected?.let { account ->
        Text("Connected as ${account.user}" + if (queued > 0) " · $queued listens waiting" else "")
        OutlinedButton(onClick = ListenBrainz::signOut) { Text("Disconnect") }
    } ?: run {
        Text("Send plays to your ListenBrainz history. Find your user token in the settings at listenbrainz.org.")
        OutlinedTextField(token, { token = it }, label = { Text("User token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = token.isNotBlank() && !busy, onClick = {
            busy = true
            problem = null
            scope.launch {
                val result = ListenBrainz.signIn(token)
                busy = false
                if (result.isSuccess) token = "" else problem = result.exceptionOrNull()?.message
            }
        }) { Text(if (busy) "Connecting…" else "Connect") }
    }
    problem?.let { Text(it) }
}
