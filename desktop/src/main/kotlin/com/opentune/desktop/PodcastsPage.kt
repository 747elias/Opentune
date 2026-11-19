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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.podcasts.PodcastEpisode
import com.opentune.data.podcasts.PodcastShow
import com.opentune.data.podcasts.PodcastShowPage
import com.opentune.data.podcasts.Podcasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private enum class PodcastSearchKind(val title: String) { SHOWS("Shows"), EPISODES("Episodes") }

private sealed interface PodcastMatches {
    data class Shows(val values: List<PodcastShow>) : PodcastMatches
    data class Episodes(val values: List<PodcastEpisode>) : PodcastMatches
}

@Composable
internal fun PodcastsPage(onOpenShow: (String) -> Unit) {
    val subscriptions by Podcasts.subscriptions.collectAsState()
    val progress by Podcasts.progress.collectAsState()
    val inProgress = remember(progress) { Podcasts.inProgress(progress).take(10) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(PodcastSearchKind.SHOWS) }
    var home by remember { mutableStateOf<Result<Podcasts.Home>?>(null) }
    var latest by remember { mutableStateOf<List<PodcastEpisode>>(emptyList()) }
    var matches by remember { mutableStateOf<Result<PodcastMatches>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var retrySearch by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        home = podcastRequest { Podcasts.home() }
    }
    LaunchedEffect(subscriptions, refresh) {
        latest = Podcasts.latestFromSubscriptions().distinctBy { it.videoId }
    }
    LaunchedEffect(query, kind, retrySearch) {
        matches = null
        val text = query.trim()
        if (text.isEmpty()) return@LaunchedEffect
        delay(300)
        matches = podcastRequest {
            when (kind) {
                PodcastSearchKind.SHOWS -> PodcastMatches.Shows(Podcasts.searchShows(text))
                PodcastSearchKind.EPISODES -> PodcastMatches.Episodes(Podcasts.searchEpisodes(text))
            }
        }
    }

    ScrollPage("Podcasts", spacing = 10.dp, trailing = {
        if (query.isBlank()) TextButton(onClick = { refresh++ }) { Text("Refresh") }
    }) {
        item(key = "podcast-search") { DiscoverySearch(query, { query = it }, "Search shows and episodes") }
        item(key = "podcast-topics") {
            HRow(8.dp) {
                items(Podcasts.TOPICS) { topic ->
                    FilterChip(query == topic && kind == PodcastSearchKind.SHOWS, onClick = {
                        query = if (query == topic && kind == PodcastSearchKind.SHOWS) "" else topic
                        kind = PodcastSearchKind.SHOWS
                    }, label = { Text(topic) })
                }
            }
        }
        if (query.isNotBlank()) {
            item(key = "podcast-search-kind") {
                Row(Modifier.padding(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PodcastSearchKind.entries.forEach { option ->
                        FilterChip(kind == option, { kind = option }, { Text(option.title) })
                    }
                }
            }
            val result = matches
            if (result == null) item { Loading() }
            else if (result.isFailure) item { DiscoveryNotice("Podcast search failed", retry = { retrySearch++ }) }
            else when (val data = result.getOrThrow()) {
                is PodcastMatches.Shows -> {
                    if (data.values.isEmpty()) item { DiscoveryNotice("No shows found") }
                    items(data.values, key = { "search:${it.browseId}" }) { show -> PodcastShowRow(show) { onOpenShow(show.browseId) } }
                }
                is PodcastMatches.Episodes -> {
                    if (data.values.isEmpty()) item { DiscoveryNotice("No episodes found") }
                    items(data.values, key = { "search:${it.videoId}" }) { episode ->
                        PodcastEpisodeRow(episode, progress[episode.videoId], onOpenShow) { playEpisodes(listOf(episode), 0) }
                    }
                }
            }
        } else {
            if (inProgress.isNotEmpty()) {
                item { DiscoveryTitle("Continue listening") }
                items(inProgress, key = { "resume:${it.episode.videoId}" }) { p ->
                    PodcastEpisodeRow(p.episode, p, onOpenShow) { playEpisodes(listOf(p.episode), 0) }
                }
            }
            if (subscriptions.isNotEmpty()) {
                item(key = "your-shows") { PodcastCarousel("Your shows", subscriptions, onOpenShow) }
                if (latest.isNotEmpty()) item { DiscoveryTitle("New from your shows") }
                items(latest.take(8), key = { "new:${it.videoId}" }) { episode ->
                    PodcastEpisodeRow(episode, progress[episode.videoId], onOpenShow) { playEpisodes(listOf(episode), 0) }
                }
            }
            val result = home
            if (result == null) item { Loading() }
            else if (result.isFailure) item { DiscoveryNotice("Couldn't load podcast recommendations", retry = { refresh++ }) }
            else {
                val feed = result.getOrThrow()
                if (feed.shows.isNotEmpty()) item(key = "recommended-shows") { PodcastCarousel("Shows to try", feed.shows, onOpenShow) }
                if (feed.popular.isNotEmpty()) item { DiscoveryTitle("Popular episodes") }
                items(feed.popular, key = { "popular:${it.videoId}" }) { episode ->
                    PodcastEpisodeRow(episode, progress[episode.videoId], onOpenShow) { playEpisodes(listOf(episode), 0) }
                }
                feed.more.forEach { (topic, shows) ->
                    item(key = "topic:$topic") { PodcastCarousel(topic, shows, onOpenShow) }
                }
            }
        }
    }
}

@Composable
internal fun PodcastShowPage(browseId: String) {
    val subscriptions by Podcasts.subscriptions.collectAsState()
    val progress by Podcasts.progress.collectAsState()
    var result by remember(browseId) { mutableStateOf<Result<PodcastShowPage>?>(null) }
    var episodes by remember(browseId) { mutableStateOf<List<PodcastEpisode>>(emptyList()) }
    var continuation by remember(browseId) { mutableStateOf<String?>(null) }
    var visited by remember(browseId) { mutableStateOf<Set<String>>(emptySet()) }
    var reload by remember(browseId) { mutableIntStateOf(0) }
    var moreRequest by remember(browseId) { mutableIntStateOf(0) }
    var loadingMore by remember(browseId) { mutableStateOf(false) }
    var moreFailed by remember(browseId) { mutableStateOf(false) }

    LaunchedEffect(browseId, reload) {
        val loaded = podcastRequest { Podcasts.show(browseId) }
        result = loaded
        loaded.getOrNull()?.let { page ->
            episodes = page.episodes.distinctBy { it.videoId }
            continuation = page.continuation
            visited = emptySet()
        }
    }
    LaunchedEffect(browseId, moreRequest) {
        if (moreRequest == 0) return@LaunchedEffect
        val show = result?.getOrNull()?.show ?: return@LaunchedEffect
        val token = continuation ?: return@LaunchedEffect
        loadingMore = true
        moreFailed = false
        try {
            val (page, next) = Podcasts.moreEpisodes(show, token)
            episodes = (episodes + page).distinctBy { it.videoId }
            visited = visited + token
            continuation = next?.takeUnless { it in visited }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            moreFailed = true
        } finally {
            loadingMore = false
        }
    }

    val loaded = result
    val show = loaded?.getOrNull()?.show
    val subscribed = subscriptions.any { it.browseId == browseId }
    ScrollPage(show?.title ?: "Podcast", subtitle = show?.author?.takeIf { it.isNotBlank() }, spacing = 10.dp, trailing = {
        if (show != null) Button(onClick = { Podcasts.setSubscribed(show, !subscribed) }) {
            Text(if (subscribed) "Unsubscribe" else "Subscribe")
        }
    }) {
        if (loaded == null) item { Loading() }
        else if (show == null) item { DiscoveryNotice("Couldn't load this show", retry = { reload++ }) }
        else {
            item(key = "show-description") {
                Row(Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cover(show.thumbnailUrl, 108.dp, RoundedCornerShape(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        show.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (episodes.isNotEmpty()) PillButton("Play newest", Icons.Rounded.PlayArrow) { playEpisodes(episodes, 0) }
                            if (continuation == null) DownloadAllButton(episodes.map { it.toSong() })
                        }
                    }
                }
            }
            if (episodes.isEmpty()) item { DiscoveryNotice("No episodes found") }
            else item { DiscoveryTitle("Episodes") }
            itemsIndexed(episodes, key = { _, episode -> episode.videoId }) { index, episode ->
                PodcastEpisodeRow(episode, progress[episode.videoId]) { playEpisodes(episodes, index) }
            }
            if (continuation != null) item(key = "more-episodes") {
                Row(Modifier.padding(horizontal = GUTTER), verticalAlignment = Alignment.CenterVertically) {
                    if (moreFailed) Text("Couldn't load more episodes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { moreRequest++ }, enabled = !loadingMore) {
                        Text(if (loadingMore) "Loading episodes…" else if (moreFailed) "Try again" else "Load more episodes")
                    }
                }
            }
        }
    }
}

@Composable
private fun PodcastCarousel(title: String, shows: List<PodcastShow>, onOpenShow: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        DiscoveryTitle(title)
        HRow(12.dp) {
            items(shows, key = { it.browseId }) { show ->
                Column(Modifier.width(144.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpenShow(show.browseId) }) {
                    Cover(show.thumbnailUrl, 144.dp, RoundedCornerShape(12.dp))
                    Text(show.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text(show.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PodcastShowRow(show: PodcastShow, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Cover(show.thumbnailUrl, 54.dp, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f)) {
            Text(show.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(show.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PodcastEpisodeRow(
    episode: PodcastEpisode,
    status: Podcasts.Progress?,
    onOpenShow: ((String) -> Unit)? = null,
    onPlay: () -> Unit,
) {
    val fraction = status?.fraction ?: (episode.playedPercent / 100f).coerceIn(0f, 1f)
    val finished = status?.finished ?: (episode.playedPercent == 100)
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Cover(episode.thumbnailUrl, 54.dp, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f)) {
            Text(episode.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val showId = episode.showBrowseId
            if (showId != null && onOpenShow != null) {
                Text(episode.showTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onOpenShow(showId) })
            }
            val details = listOfNotNull(episode.published, episode.durationText, "Played".takeIf { finished }).joinToString(" · ")
            if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!finished && fraction > 0) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.width(146.dp).padding(top = 6.dp))
        }
        TextButton(onClick = {
            Podcasts.starting(episode)
            Podcasts.markPlayed(episode.videoId, !finished)
        }) { Text(if (finished) "Mark as unplayed" else "Mark as played") }
    }
}

private fun playEpisodes(episodes: List<PodcastEpisode>, index: Int) {
    Podcasts.starting(episodes)
    Player.playAll(episodes.map { it.toSong() }, index)
}

private suspend fun <T> podcastRequest(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
