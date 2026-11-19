package com.opentune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.opentune.data.history.History
import com.opentune.data.history.ReplaySummary
import com.opentune.data.history.Wrapped
import com.opentune.ui.theme.Text
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ReplayPeriod(val label: String) { MONTH("Last 30 days"), YEAR("This year"), ALL("All time") }

@Composable
internal fun ReplayPage() {
    var period by remember { mutableStateOf(ReplayPeriod.YEAR) }
    val revision by Library.playHistoryRevision.collectAsState()
    val summaries by produceState<Pair<Wrapped.Summary, ReplaySummary>?>(null, period, revision) {
        value = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val since = when (period) {
                ReplayPeriod.MONTH -> now - 30 * 86_400_000L
                ReplayPeriod.YEAR -> LocalDate.now().withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                ReplayPeriod.ALL -> 0
            }
            val history = Library.history()
            Wrapped.summarize(history, since, now) to History.replay(history, since)
        }
    }
    val (wrapped, replay) = summaries ?: (null to null)
    ScrollPage("Replay", "Your listening history on this computer.", spacing = 16.dp) {
        item {
            Row(Modifier.padding(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplayPeriod.entries.forEach { range -> FilterChip(range == period, { period = range }, { Text(range.label) }) }
            }
        }
        if (wrapped == null || replay == null) item { Loading() }
        else if (wrapped.isEmpty) item { Message("Nothing played in this period", "Songs you listen to here appear in Replay.") }
        else {
            val songs = wrapped.topSongs.mapNotNull { it.song }
            item {
                Column(Modifier.padding(horizontal = GUTTER), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${wrapped.minutes} minutes · ${wrapped.plays} plays", style = MaterialTheme.typography.headlineMedium)
                    Text("${wrapped.songs} songs · ${wrapped.artists} artists · ${replay.albumCount} albums")
                    Text("${wrapped.clock.title} · ${wrapped.clock.line}")
                    Text("Longest streak: ${wrapped.streakDays} days · Busiest day: ${wrapped.busiestDayMinutes} minutes")
                    wrapped.first?.let { Text("First track: ${it.title} · ${it.subtitle}") }
                    wrapped.onRepeat?.let { Text("Most repeated: ${it.title} · ${it.plays} plays in one day") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = songs.isNotEmpty(), onClick = { Player.playAll(songs, 0) }) { Text("Play top tracks") }
                        OutlinedButton(enabled = songs.isNotEmpty(), onClick = { Player.playAll(songs.shuffled(), 0) }) { Text("Shuffle") }
                        OutlinedButton(onClick = {
                            val summary = "OpenTune Replay · ${period.label}\n${wrapped.minutes} minutes · ${wrapped.plays} plays\n" +
                                wrapped.topSongs.take(5).joinToString("\n") { "${it.title} — ${it.subtitle}" }
                            runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(summary), null) }
                        }) { Text("Copy summary") }
                    }
                }
            }
            item {
                val peak = wrapped.hours.maxOrNull()?.coerceAtLeast(1) ?: 1
                Column(Modifier.fillMaxWidth().padding(horizontal = GUTTER)) {
                    Text("Listening by hour", style = MaterialTheme.typography.titleLarge)
                    val accent = MaterialTheme.colorScheme.primary
                    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                        wrapped.hours.forEach { minutes ->
                            Box(Modifier.weight(1f).height((minutes * 90.0 / peak).toFloat().coerceAtLeast(2f).dp)
                                .background(accent.copy(alpha = if (minutes > 0) 1f else 0.15f), RoundedCornerShape(3.dp)))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("00:00", "06:00", "12:00", "18:00", "23:00").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            item { Text("Top songs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = GUTTER)) }
            tracks(songs)
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = GUTTER), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Top artists", style = MaterialTheme.typography.titleLarge)
                    replay.topArtists.take(10).forEach { Text("${it.title} · ${it.plays} plays") }
                    Text("Top albums", style = MaterialTheme.typography.titleLarge)
                    replay.topAlbums.take(10).forEach { Text("${it.title} · ${it.subtitle} · ${it.plays} plays") }
                }
            }
        }
    }
}
