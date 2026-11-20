package com.opentune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.audiobooks.Audiobooks
import com.opentune.data.audiobooks.Audiobooks.toSong
import com.opentune.ui.theme.Text
import kotlinx.coroutines.delay

internal data object AudiobooksKey
internal data class AudiobookKey(val id: String)

@Composable
internal fun AudiobooksPage(onOpenBook: (String) -> Unit) {
    val progress by Audiobooks.progress.collectAsState()
    var query by remember { mutableStateOf("") }
    var request by remember { mutableIntStateOf(0) }
    var books by remember(query) { mutableStateOf<Result<List<Audiobooks.Book>>?>(null) }
    LaunchedEffect(query, request) {
        if (query.isNotBlank()) delay(350)
        books = podcastRequest { Audiobooks.search(query.ifBlank { null }) }
    }
    ScrollPage("Audiobooks", "Public-domain books read by LibriVox volunteers.", spacing = 10.dp) {
        item { DiscoverySearch(query, { query = it }, "Title or author") }
        if (query.isBlank() && progress.isNotEmpty()) {
            item { DiscoveryTitle("Continue listening") }
            items(Audiobooks.inProgress(), key = { "continue:${it.bookId}" }) { item ->
                BookRow(Audiobooks.Book(item.bookId, item.title, item.author, "Left at ${bookClock(item.positionMs)}")) {
                    onOpenBook(item.bookId)
                }
            }
        }
        item { DiscoveryTitle(if (query.isBlank()) "Most listened to" else "Books") }
        val result = books
        when {
            result == null -> item { Loading() }
            result.isFailure -> item { DiscoveryNotice("Couldn't reach the Internet Archive") { request++ } }
            result.getOrThrow().isEmpty() -> item { DiscoveryNotice("No books found") }
            else -> items(result.getOrThrow(), key = { it.id }) { book -> BookRow(book) { onOpenBook(book.id) } }
        }
    }
}

@Composable
private fun BookRow(book: Audiobooks.Book, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(book.coverUrl, 54.dp, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(book.author, book.runtime).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AudiobookPage(id: String) {
    val progress by Audiobooks.progress.collectAsState()
    var request by remember(id) { mutableIntStateOf(0) }
    var loaded by remember(id) { mutableStateOf<Result<Audiobooks.BookPage>?>(null) }
    LaunchedEffect(id, request) { loaded = podcastRequest { Audiobooks.book(id) } }
    val page = loaded?.getOrNull()
    val songs = remember(page) { page?.let { book -> book.chapters.map { it.toSong(book.book) } }.orEmpty() }
    val position = progress[id]
    val resume = position?.let { saved -> songs.indexOfFirst { it.videoId == saved.chapterId }.takeIf { it >= 0 } }
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val current = queue.getOrNull(index)?.videoId
    ScrollPage(page?.book?.title ?: "Audiobook", page?.book?.author, spacing = 10.dp) {
        if (page == null) {
            item {
                if (loaded == null) Loading()
                else DiscoveryNotice("Couldn't load this audiobook") { request++ }
            }
            return@ScrollPage
        }
        item {
            Row(Modifier.padding(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Cover(page.book.coverUrl, 132.dp, RoundedCornerShape(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    page.book.runtime?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (songs.isNotEmpty()) {
                        Button(onClick = { Player.playAll(songs, resume ?: 0) }) {
                            Text(resume?.let { "Continue: ${songs[it].title}" } ?: "Play from the start")
                        }
                    }
                    page.description?.let { text ->
                        var expanded by remember(id) { mutableStateOf(false) }
                        Text(text, Modifier.clickable { expanded = !expanded }, maxLines = if (expanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (songs.isEmpty()) item { DiscoveryNotice("No playable chapters") }
        else item { DiscoveryTitle("Chapters") }
        itemsIndexed(songs, key = { _, song -> song.videoId }) { i, song ->
            val colour = if (current == song.videoId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Row(
                Modifier.fillMaxWidth().clickable { Player.playAll(songs, i) }.padding(horizontal = GUTTER, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(page.chapters[i].number.toString(), Modifier.width(32.dp), color = colour)
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = colour, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (position?.chapterId == song.videoId && position.positionMs > 0) {
                        Text("Left at ${bookClock(position.positionMs)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                song.durationText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun bookClock(ms: Long): String {
    val seconds = ms / 1_000
    return if (seconds >= 3_600) "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
