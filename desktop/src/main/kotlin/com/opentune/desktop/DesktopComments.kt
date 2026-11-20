package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.opentune.data.comments.SongComments
import com.opentune.data.model.Song
import com.opentune.ui.theme.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DesktopComments(song: Song, onClose: () -> Unit) {
    val revision by DesktopAccount.revision.collectAsState()
    val signedIn by DesktopAccount.signedIn.collectAsState()
    var reload by remember(song.videoId, revision) { mutableStateOf(0) }
    val pages = remember(song.videoId, revision, reload) { CommentPages() }
    var thread by remember(song.videoId, revision, reload) { mutableStateOf<SongComments.Thread?>(null) }
    var starting by remember(song.videoId, revision, reload) { mutableStateOf(true) }
    var selected by remember(song.videoId, revision, reload) { mutableStateOf<SongComments.Comment?>(null) }
    LaunchedEffect(song.videoId, revision, reload) {
        thread = SongComments.open(song.videoId)
        thread?.first?.let { pages.fetch(it) }
        starting = false
    }

    DialogWindow(onCloseRequest = onClose, title = "Comments", state = rememberDialogState(width = 680.dp, height = 690.dp)) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(song.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (thread?.fromVideo == true) "Comments from the music video on YouTube" else "Comments from YouTube",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (thread != null && !starting) TextButton(onClick = { reload++ }) { Text("Refresh") }
                    }

                    val parent = selected
                    if (parent != null) {
                        TextButton(onClick = { selected = null }) { Text("Back to comments") }
                        Replies(parent, Modifier.weight(1f).fillMaxWidth())
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                starting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                                thread == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Comments didn't load. Check the connection and try again.")
                                    TextButton(onClick = { reload++ }) { Text("Try again") }
                                }
                                thread?.first == null -> Text("Comments are turned off for this song on YouTube.", Modifier.align(Alignment.Center))
                                else -> CommentList(pages, Modifier.fillMaxSize(), onReplies = { selected = it })
                            }
                        }
                        if (!starting && thread?.first != null && !pages.failed) {
                            when {
                                !signedIn -> Text("Sign in with YouTube Music to add a comment.", style = MaterialTheme.typography.bodySmall)
                                pages.createParams != null -> androidx.compose.runtime.key(song.videoId, revision) {
                                    CommentComposer(pages, revision)
                                }
                            }
                        }
                    }
                    TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                }
            }
        }
    }
}

private class CommentPages(start: String? = null) {
    val comments = mutableStateListOf<SongComments.Comment>()
    var next by mutableStateOf(start)
        private set
    var createParams by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private val seen = HashSet<String>()

    suspend fun fetch(token: String) {
        if (loading || token in seen) return
        loading = true
        failed = false
        next = token
        try {
            val page = SongComments.page(token)
            if (page == null) {
                failed = true
                return
            }
            seen.add(token)
            val have = comments.mapTo(HashSet()) { it.id }
            comments += page.comments.filter { have.add(it.id) }
            next = page.next?.takeUnless { it in seen }
            createParams = createParams ?: page.createParams
        } finally {
            loading = false
        }
    }
}

@Composable
private fun Replies(parent: SongComments.Comment, modifier: Modifier) {
    val start = parent.replyToken ?: return
    val pages = remember(start) { CommentPages(start) }
    LaunchedEffect(start) { pages.fetch(start) }
    CommentList(pages, modifier, parent = parent)
}

@Composable
private fun CommentList(pages: CommentPages, modifier: Modifier, parent: SongComments.Comment? = null, onReplies: (SongComments.Comment) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(15.dp)) {
        if (parent != null) item(key = "parent") { CommentRow(parent, null) }
        items(pages.comments, key = { it.id }) { comment ->
            CommentRow(comment, if (parent == null && comment.replyToken != null) ({ onReplies(comment) }) else null)
        }
        val next = pages.next
        when {
            pages.loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            next != null -> item(key = "next") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pages.failed) Text("Couldn't load comments.")
                    TextButton(onClick = { scope.launch { pages.fetch(next) } }) {
                        Text(if (pages.failed) "Try again" else "Load more")
                    }
                }
            }
            pages.comments.isEmpty() -> item(key = "empty") { Text(if (parent == null) "No comments yet." else "No replies yet.") }
        }
    }
}

@Composable
private fun CommentRow(comment: SongComments.Comment, onReplies: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(comment.avatarUrl, Modifier.size(38.dp), CircleShape)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(comment.author, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = if (comment.byCreator) scheme.primary else scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                comment.published?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant) }
            }
            SelectionContainer { Text(comment.text, style = MaterialTheme.typography.bodyMedium) }
            if (comment.likes != null || onReplies != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (comment.likes != null) {
                    Icon(Icons.Rounded.ThumbUp, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                    Text(comment.likes, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
                if (onReplies != null) TextButton(onClick = onReplies) {
                    Text(comment.replies?.let { count -> com.opentune.i18n.Tr.f(if (count == "1") "{0} reply" else "{0} replies", count) } ?: "View replies")
                }
            }
        }
    }
}

@Composable
private fun CommentComposer(pages: CommentPages, revision: Int) {
    val scope = rememberCoroutineScope()
    val params = pages.createParams ?: return
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(text, { text = it.take(10_000); feedback = null; failed = false }, enabled = !sending,
                modifier = Modifier.weight(1f), label = { Text("Add a comment") }, isError = failed, maxLines = 4)
            Button(enabled = text.isNotBlank() && !sending, onClick = {
                sending = true
                scope.launch {
                    try {
                        val posted = DesktopAccount.writeAsAccount(revision) { SongComments.post(params, text).getOrThrow() }
                        if (posted != null && pages.comments.none { it.id == posted.id }) pages.comments.add(0, posted)
                        text = ""
                        feedback = "Comment posted"
                        failed = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        feedback = "YouTube didn't confirm the comment. Refresh the thread before trying again."
                        failed = true
                    } finally {
                        sending = false
                    }
                }
            }) { Text(if (sending) "Posting…" else "Post comment") }
        }
        Text(feedback ?: "Posted publicly on YouTube, under your account.", style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
