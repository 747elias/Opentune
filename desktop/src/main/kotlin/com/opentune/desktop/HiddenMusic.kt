package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.library.HiddenList
import com.opentune.data.model.Song
import com.opentune.i18n.Tr
import com.opentune.ui.theme.Text
import kotlinx.coroutines.flow.MutableSharedFlow

internal data class HiddenMusicNotice(val message: String, val undo: (() -> Unit)? = null)
internal val hiddenMusicNotices = MutableSharedFlow<HiddenMusicNotice>(extraBufferCapacity = 8)

@Composable
internal fun SuggestionActions(song: Song) {
    var open by remember(song.videoId) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.MoreHoriz, Tr.t("More actions"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            val hidden by DesktopHidden.list.collectAsState()
            val dislikes by DesktopHidden.dislikes.collectAsState()
            val hiddenSong = hidden.hidesSong(song.videoId)
            val artist = HiddenList.mainArtist(song.artist)
            val key = HiddenList.normalize(artist)
            val hiddenArtist = hidden.artists.any { it.key == key }
            val disliked = song.videoId in dislikes
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(if (disliked) "Remove dislike" else "Not for me (dislike)") }, onClick = {
                    open = false
                    DesktopAccount.setDisliked(song, !disliked)
                })
                DropdownMenuItem(text = { Text(if (hiddenSong) "Unhide song" else "Not interested") }, onClick = {
                    open = false
                    if (hiddenSong) {
                        if (!DesktopHidden.unhideSong(song.videoId)) showSaveFailure()
                    } else if (DesktopHidden.hideSong(song)) {
                        hiddenMusicNotices.tryEmit(HiddenMusicNotice(Tr.f("\"{0}\" won't be suggested", song.title)) { DesktopHidden.unhideSong(song.videoId) })
                    } else showSaveFailure()
                })
                if (artist.isNotBlank()) DropdownMenuItem(text = { Text(if (hiddenArtist) "Unhide artist" else "Don't play this artist") }, onClick = {
                    open = false
                    if (hiddenArtist) {
                        if (!DesktopHidden.unhideArtist(key)) showSaveFailure()
                    } else if (DesktopHidden.hideArtist(song)) {
                        hiddenMusicNotices.tryEmit(HiddenMusicNotice(Tr.f("Songs by {0} won't be suggested", artist)) { DesktopHidden.unhideArtist(key) })
                    } else showSaveFailure()
                })
            }
        }
    }
}

private fun showSaveFailure() { hiddenMusicNotices.tryEmit(HiddenMusicNotice(Tr.t("Couldn't save hidden songs and artists"))) }

@Composable
internal fun HiddenMusicSettings() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Manage hidden songs and artists") }
    if (!open) return
    val hidden by DesktopHidden.list.collectAsState()
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Hidden songs and artists") },
        text = {
            if (hidden.isEmpty) {
                Text("Use \"Not interested\" or \"Don't play this artist\" in a song's menu to change your suggestions.")
            } else {
                LazyColumn(Modifier.heightIn(max = 390.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(hidden.artists, key = { "artist:${it.key}" }) { artist ->
                        HiddenEntry(artist.name) { DesktopHidden.unhideArtist(artist.key) }
                    }
                    items(hidden.songs, key = { "song:${it.videoId}" }) { song ->
                        HiddenEntry(listOf(song.title, song.artist).filter(String::isNotBlank).joinToString(" · ")) {
                            DesktopHidden.unhideSong(song.videoId)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
    )
}

@Composable
private fun HiddenEntry(title: String, unhide: () -> Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { if (!unhide()) showSaveFailure() }) { Text("Unhide") }
    }
}
