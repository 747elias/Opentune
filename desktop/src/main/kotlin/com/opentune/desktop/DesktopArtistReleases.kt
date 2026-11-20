package com.opentune.desktop

import com.opentune.data.SavedFile
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.ShelfItem
import com.opentune.data.releases.ArtistReleaseRules
import com.opentune.data.settings.AppSettings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stores the artists and release inbox on this computer, independent of the signed-in account. */
internal object DesktopArtistReleases {
    @Serializable
    data class Artist(
        val browseId: String,
        val name: String,
        val thumbnailUrl: String?,
        val known: Set<String>,
    )

    @Serializable
    data class Release(val artistId: String, val artistName: String, val item: ShelfItem)

    @Serializable
    data class State(val artists: List<Artist> = emptyList(), val inbox: List<Release> = emptyList())

    private const val CHECK_EVERY = 12 * 60 * 60 * 1000L
    private const val MAX_INBOX = 100
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val disk = SavedFile(File(appContext.filesDir, "followed-artists.json"), "ArtistReleases")
    private val json = Json { ignoreUnknownKeys = true }
    private val gate = Any()
    private val checkingGate = Mutex()
    private val _state = MutableStateFlow(disk.read(State()) { json.decodeFromString<State>(it) })
    val state = _state.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking = _checking.asStateFlow()
    private val _latest = MutableStateFlow<Release?>(null)
    val latest = _latest.asStateFlow()
    private val open = Channel<ShelfItem>(Channel.UNLIMITED)
    val openings = open.receiveAsFlow()

    fun installNotifications(show: () -> Unit) = MacMediaControls.installReleaseNotices { id ->
        state.value.inbox.firstOrNull { it.item.browseId == id }?.let {
            show()
            open.trySend(it.item)
        }
    }

    fun start() {
        scope.launch {
            while (true) {
                if (AppSettings.library.value.releaseAlerts) check()
                delay(CHECK_EVERY)
            }
        }
    }

    fun toggle(id: String, name: String, art: String?, page: ArtistPage): Boolean {
        val following = synchronized(gate) {
            val now = state.value
            val next = if (now.artists.any { it.browseId == id }) {
                now.copy(artists = now.artists.filterNot { it.browseId == id }, inbox = now.inbox.filterNot { it.artistId == id })
            } else {
                val known = ArtistReleaseRules.releasesOf(page).mapNotNullTo(LinkedHashSet()) { it.browseId }
                now.copy(artists = listOf(Artist(id, name, art, known)) + now.artists)
            }
            runCatching { save(next) }.map { next.artists.any { it.browseId == id } }
        }
        if (following.getOrNull() == true && AppSettings.library.value.releaseAlerts) MacMediaControls.authorizeReleaseNotices()
        return following.isSuccess
    }

    fun refresh() { scope.launch { check() } }

    fun clearLatest(release: Release) { _latest.compareAndSet(release, null) }

    private fun save(next: State) {
        disk.write(json.encodeToString(next))
        _state.value = next
    }

    private suspend fun check() = checkingGate.withLock {
        val snapshot = state.value.artists
        if (snapshot.isEmpty()) return@withLock
        _checking.value = true
        try {
            val changes = snapshot.mapNotNull { artist ->
                val page = try {
                    InnertubeParser.parseArtistPage(Innertube.browse(artist.browseId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                val (fresh, known) = ArtistReleaseRules.diff(artist.known, ArtistReleaseRules.releasesOf(page))
                val updated = artist.copy(known = known, thumbnailUrl = page.thumbnailUrl ?: artist.thumbnailUrl)
                Triple(artist, updated, fresh.map { Release(artist.browseId, artist.name, it) })
            }
            val released = synchronized(gate) {
                val now = state.value
                // A follow toggled during the request has its own baseline, even if the id matches.
                val valid = changes.filter { (before, _, _) -> now.artists.any { it === before } }
                val byId = valid.associateBy { it.first.browseId }
                val fresh = valid.flatMap { it.third }.distinctBy { it.item.browseId }
                val updated = now.copy(
                    artists = now.artists.map { byId[it.browseId]?.second ?: it },
                    inbox = (fresh + now.inbox).distinctBy { it.item.browseId }.take(MAX_INBOX),
                )
                if (updated != now) save(updated)
                fresh
            }
            if (AppSettings.library.value.releaseAlerts) {
                val visible = released.filterNot { AppSettings.library.value.hideExplicit && it.item.explicit }
                visible.firstOrNull()?.let { _latest.value = it }
                visible.take(8).forEach { entry ->
                    entry.item.browseId?.let { MacMediaControls.releaseNotice(it, entry.artistName, entry.item.title) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            com.opentune.data.DebugLog.w("ArtistReleases", "Couldn't save the release check", failure)
        } finally {
            _checking.value = false
        }
    }
}
