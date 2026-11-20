package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.lyrics.Romanization
import com.opentune.data.lyrics.SongParts
import com.opentune.data.lyrics.VOICE_SECOND
import com.opentune.data.lyrics.activeIndex
import com.opentune.data.lyrics.lyricsOffset
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.LyricsSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.graalvm.shadowed.com.ibm.icu.text.Transliterator

/** What the lyrics pane shows for the song playing. */
internal sealed interface LyricsState {
    data object Loading : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
    data object NotFound : LyricsState
}

/**
 * The song's lyrics from the same sources the phone asks, in the order set
 * in Settings, found again when that order or this song's pick changes.
 */
@Composable
internal fun rememberLyrics(song: Song): LyricsState {
    val settings by AppSettings.lyrics.collectAsState()
    var state by remember(song.videoId) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    val asked = Triple(settings.ordered, settings.preferWordSynced, settings.chosen[song.videoId])
    LaunchedEffect(song.videoId, asked) {
        state = LyricsState.Loading
        // Sources match on the length, and the player's is exact where the row's "3:45" is rounded.
        val duration = withTimeoutOrNull(20_000) {
            Player.durationOf.first { (id, ms) -> id == song.videoId && ms > 0 }.second
        } ?: 0L
        LyricsRepository.lyricsUpdates(song.videoId, song.title, song.artist, duration).collect { found ->
            state = found?.let(LyricsState::Found) ?: LyricsState.NotFound
        }
    }
    return state
}

/**
 * Lyrics beside the cover: the line being sung large and lit word by word,
 * the rest dimmed. Clicking a line plays from it. Above them, where they came
 * from (pick another source for this song), the timing offset, and a jump to
 * the next chorus.
 */
@Composable
internal fun LyricsPane(song: Song, modifier: Modifier = Modifier) {
    val state = rememberLyrics(song)
    val settings by AppSettings.lyrics.collectAsState()
    val lyrics = (state as? LyricsState.Found)?.lyrics
    val offset = lyricsOffset(settings.offsets[song.videoId], lyrics)
    val quoteLines = remember(lyrics) {
        when (lyrics) {
            is Lyrics.Synced -> lyrics.lines.map(::lyricDisplayText)
            is Lyrics.Plain -> lyrics.text.lines().filter(String::isNotBlank)
            null -> emptyList()
        }
    }
    var quoteAt by remember(song.videoId, lyrics) { mutableStateOf<Int?>(null) }
    val texts = remember(lyrics, quoteLines) {
        (lyrics as? Lyrics.Plain)?.text?.lines() ?: quoteLines
    }
    val romanized by produceState<List<String?>>(emptyList(), texts, settings.romanize) {
        value = emptyList()
        if (settings.romanize && texts.isNotEmpty()) {
            value = withContext(Dispatchers.Default) { romanizeLyrics(texts) }
        }
    }
    Column(modifier) {
        LyricsHeader(song, lyrics, offset, quoteLines.isNotEmpty()) {
            quoteAt = (lyrics as? Lyrics.Synced)?.lines?.activeIndex(Player.positionMs() + offset)?.coerceAtLeast(0) ?: 0
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state is LyricsState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                lyrics is Lyrics.Synced -> SyncedLyrics(song, lyrics, offset, romanized)
                lyrics is Lyrics.Plain -> PlainLyrics(texts, romanized)
                else -> Text(
                    "No lyrics for this song.",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
    quoteAt?.let { DesktopLyricCard(song, quoteLines, it) { quoteAt = null } }
}

@Composable
private fun LyricsHeader(song: Song, lyrics: Lyrics?, offset: Long, canQuote: Boolean, onQuote: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val settings by AppSettings.lyrics.collectAsState()
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { picking = true }) {
                Text(lyrics?.source?.let { "From $it" } ?: "Lyrics source", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            }
            DropdownMenu(picking, onDismissRequest = { picking = false }) {
                val chosen = settings.chosen[song.videoId]
                DropdownMenuItem(
                    text = { Text("Best match") },
                    leadingIcon = { if (chosen == null) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { AppSettings.setLyricsSource(song.videoId, null); picking = false },
                )
                LyricsSource.entries.forEach { source ->
                    DropdownMenuItem(
                        text = { Text(source.label) },
                        leadingIcon = { if (chosen == source) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                        onClick = { AppSettings.setLyricsSource(song.videoId, source); picking = false },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (lyrics != null) IconButton(onClick = { AppSettings.updateLyrics { it.copy(romanize = !it.romanize) } }) {
            Icon(Icons.Rounded.Translate, if (settings.romanize) "Turn off romanization" else "Show romanized lyrics",
                Modifier.size(20.dp), tint = if (settings.romanize) scheme.primary else scheme.onSurfaceVariant)
        }
        if (canQuote) IconButton(onClick = onQuote) { Icon(Icons.Rounded.FormatQuote, "Share lyrics", Modifier.size(20.dp)) }
        if (lyrics is Lyrics.Synced) {
            val choruses = remember(lyrics) { SongParts.choruses(lyrics) }
            if (choruses.isNotEmpty()) {
                TextButton(onClick = {
                    SongParts.nextChorus(choruses, Player.positionMs() + offset)?.let { Player.seekTo((it - offset).coerceAtLeast(0)) }
                }) { Text("Skip to chorus", style = MaterialTheme.typography.labelLarge) }
            }
            // Earlier or later by a tenth of a second, kept for this song, as the phone's "Lyrics offset":
            // a positive shift shows the words earlier. A 0 set here is kept when it overrides the automatic shift.
            fun set(ms: Long) = AppSettings.setLyricsOffset(song.videoId, ms.coerceIn(-10_000, 10_000), keepZero = lyrics.autoOffsetMs != 0L)
            IconButton(onClick = { set(offset - 100) }) {
                Icon(Icons.Rounded.Remove, "Lyrics later", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Text(
                when {
                    offset == 0L -> "In step"
                    offset > 0 -> "%.1f s earlier".format(offset / 1000f)
                    else -> "%.1f s later".format(-offset / 1000f)
                },
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
                // Clicking it goes back to the automatic shift.
                modifier = Modifier.width(96.dp).clickable { AppSettings.setLyricsOffset(song.videoId, 0) },
            )
            IconButton(onClick = { set(offset + 100) }) {
                Icon(Icons.Rounded.Add, "Lyrics earlier", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SyncedLyrics(song: Song, lyrics: Lyrics.Synced, offset: Long, romanized: List<String?>) {
    var position by remember(song.videoId) { mutableLongStateOf(0L) }
    LaunchedEffect(song.videoId) {
        while (true) {
            position = Player.positionMs()
            delay(40)
        }
    }
    val at = position + offset
    val active = lyrics.lines.activeIndex(at)
    val list = rememberLazyListState()
    LaunchedEffect(active) {
        if (active < 0) return@LaunchedEffect
        // The line sung keeps a third of the way down.
        val third = list.layoutInfo.viewportSize.height / 3
        list.animateScrollToItem(active, -third)
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(top = 24.dp, bottom = 240.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(lyrics.lines, key = { i, l -> "$i:${l.startMs}" }) { i, line ->
            // Only the line being sung follows the clock; the rest stay as they are, so the
            // 25-a-second position updates redraw one row rather than the whole list.
            LyricRow(line, i == active, if (i == active) at else 0L, romanized.getOrNull(i)) {
                Player.seekTo((line.startMs - offset).coerceAtLeast(0))
            }
        }
    }
}

@Composable
private fun LyricRow(line: LyricLine, active: Boolean, at: Long, romanized: String?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val lit = scheme.onSurface
    val dim = scheme.onSurface.copy(alpha = 0.32f)
    val ink by animateColorAsState(if (active) lit else dim)
    val scale by animateFloatAsState(if (active) 1f else 0.94f, androidx.compose.animation.core.spring(dampingRatio = 1f))
    val second = line.voice == VOICE_SECOND
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = if (second) Alignment.End else Alignment.Start,
    ) {
        val style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        val text = if (active && line.wordSynced && line.words.isNotEmpty()) {
            // Each word fills in as it's sung.
            buildAnnotatedString {
                line.words.forEach { w ->
                    val f = when {
                        at >= w.endMs -> 1f
                        at <= w.startMs -> 0f
                        else -> (at - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1)
                    }
                    withStyle(SpanStyle(color = lerp(dim, lit, f))) { append(w.text) }
                }
            }
        } else {
            buildAnnotatedString { withStyle(SpanStyle(color = ink)) { append(lyricDisplayText(line)) } }
        }
        Text(
            text, style = style,
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                scaleX = scale; scaleY = scale
                transformOrigin = TransformOrigin(if (second) 1f else 0f, 0.5f)
            },
            textAlign = if (second) TextAlign.End else TextAlign.Start,
        )
        romanized?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = ink.copy(alpha = ink.alpha * 0.7f),
                modifier = Modifier.padding(top = 2.dp))
        }
        line.background?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = if (active) lit.copy(alpha = 0.7f) else dim, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PlainLyrics(lines: List<String>, romanized: List<String?>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
        Text(
            remember(lines, romanized) { buildAnnotatedString {
                lines.forEachIndexed { index, line ->
                    if (index > 0) append('\n')
                    append(line)
                    romanized.getOrNull(index)?.let { reading ->
                        append('\n')
                        withStyle(SpanStyle(fontSize = 15.sp)) { append(reading) }
                    }
                }
            } },
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            lineHeight = MaterialTheme.typography.titleLarge.lineHeight * 1.2f,
        )
        Text(
            "These lyrics aren't timed to the song.", style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            modifier = Modifier.padding(top = 24.dp, bottom = 120.dp),
        )
    }
}

private val desktopRomanizer by lazy {
    val any = Transliterator.getInstance("Any-Latin; Latin-ASCII")
    val han = Transliterator.getInstance("Han-Latin; Latin-ASCII")
    Romanization({ synchronized(any) { any.transliterate(it) } }, { synchronized(han) { han.transliterate(it) } })
}

internal fun romanizeLyrics(lines: List<String>): List<String?> = desktopRomanizer.lines(lines)

/** Timed syllables already contain their intended spacing; never insert extra spaces. */
internal fun lyricDisplayText(line: LyricLine): String =
    if (line.words.isEmpty()) line.text else line.words.joinToString("") { it.text }
