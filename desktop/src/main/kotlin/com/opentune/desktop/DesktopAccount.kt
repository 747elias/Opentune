package com.opentune.desktop

import android.content.Context
import com.opentune.data.account.AccountStore
import com.opentune.data.isYouTubeId
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.AccountChannel
import com.opentune.data.model.LikeStatus
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** The active YouTube Music library stays in memory and is cleared on every account or channel switch. */
internal object DesktopAccount {
    data class LibraryData(
        val liked: List<Song> = emptyList(),
        val playlists: List<ShelfItem> = emptyList(),
        val loading: Boolean = false,
        val problem: String? = null,
    )

    data class Playlist(
        val title: String,
        val cover: String?,
        val songs: List<Song>,
        val editable: Boolean,
        val privacy: PlaylistPrivacy?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val writes = Mutex()
    private var loading: Job? = null
    private val _revision = MutableStateFlow(0)
    val revision = _revision.asStateFlow()
    private val _library = MutableStateFlow(LibraryData())
    val library = _library.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    val signedIn = AccountStore.signedIn
    val liked = combine(Library.liked, library) { local, remote ->
        (remote.liked + local).distinctBy { it.videoId }
    }.stateIn(scope, SharingStarted.Eagerly, Library.liked.value)

    fun init(context: Context) {
        AccountStore.init(context) {
            _library.value = LibraryData()
            _notice.value = null
            _revision.value++
        }
        refresh()
        DesktopYouTubeHistory.start()
    }

    suspend fun reportHistory(report: suspend () -> Unit) = writes.withLock { report() }

    suspend fun acceptSession(cookie: String) {
        loading?.cancel()
        writes.withLock { AccountStore.signIn(cookie) }
        refresh()
    }

    fun switchTo(id: String) = changeSession { AccountStore.switchTo(id) }
    fun signOut() = changeSession(AccountStore::signOut)
    fun removeAccount(id: String) = changeSession { AccountStore.remove(id) }
    fun listenAs(profile: AccountChannel, own: Boolean) = changeSession { AccountStore.listenAs(profile, own) }

    private fun changeSession(change: () -> Unit) {
        loading?.cancel()
        scope.launch {
            writes.withLock { change() }
            refresh()
        }
    }

    fun refresh() {
        loading?.cancel()
        loading = scope.launch {
            writes.withLock {
                if (!signedIn.value) return@withLock
                _library.value = _library.value.copy(loading = true, problem = null)
                coroutineScope {
                    val likes = async { request(::loadLikes) }
                    val playlists = async { request(::loadPlaylists) }
                    val likedSongs = likes.await()
                    val savedPlaylists = playlists.await()
                    val failures = listOfNotNull(likedSongs.exceptionOrNull(), savedPlaylists.exceptionOrNull())
                    _library.value = LibraryData(
                        liked = likedSongs.getOrDefault(_library.value.liked),
                        playlists = savedPlaylists.getOrDefault(_library.value.playlists),
                        problem = when {
                            failures.any { it is ExpiredSession } -> "Sign in again from your phone to refresh this account."
                            failures.isNotEmpty() -> "Couldn't refresh your YouTube Music library. Try again."
                            else -> null
                        },
                    )
                }
            }
        }
    }

    fun clearNotice() { _notice.value = null }

    /** Hearts work locally as a guest and on songs outside YouTube Music. */
    fun setLiked(song: Song, liked: Boolean) {
        if (!signedIn.value || !isYouTubeId(song.videoId)) {
            Library.setLiked(song, liked)
            return
        }
        val revision = revision.value
        scope.launch {
            request {
                writes.withLock {
                    check(signedIn.value && revision == DesktopAccount.revision.value)
                    Innertube.rate(song.videoId, if (liked) LikeStatus.LIKE else LikeStatus.INDIFFERENT)
                    if (!liked && Library.isLiked(song.videoId)) Library.setLiked(song, false)
                    val songs = _library.value.liked.filterNot { it.videoId == song.videoId }
                    _library.value = _library.value.copy(liked = if (liked) listOf(song) + songs else songs)
                }
            }.onFailure { _notice.value = "Couldn't change the like on YouTube Music. Try again." }
        }
    }

    /** Edits are serialized with session changes so a request cannot start under another account. */
    fun <T> editPlaylist(
        error: String,
        edit: suspend () -> T,
        done: (T) -> Unit = {},
    ) {
        val revision = revision.value
        scope.launch {
            request {
                writes.withLock {
                    check(signedIn.value && revision == DesktopAccount.revision.value)
                    edit()
                }
            }.onSuccess {
                refresh()
                done(it)
            }.onFailure {
                _notice.value = error
            }
        }
    }

    suspend fun playlist(browseId: String): Playlist {
        val session = revision.value
        check(signedIn.value)
        val page = signedBrowse(browseId)
        val first = InnertubeParser.parsePlaylistShelf(page)
        val songs = first?.let { it.songs + songContinuations(it.continuation) }
            ?: InnertubeParser.collectSongsDeep(page)
        check(session == revision.value)
        val header = InnertubeParser.parseBrowseHeader(page)
        val editable = InnertubeParser.isEditablePlaylist(browseId) && InnertubeParser.parsePlaylistOwned(page) == true
        return Playlist(header?.title.orEmpty(), header?.thumbnailUrl, songs, editable, InnertubeParser.parsePlaylistPrivacy(page))
    }

    suspend fun history(): List<Pair<String, List<Song>>> {
        val session = revision.value
        val first = InnertubeParser.parseHistoryPage(signedBrowse("FEmusic_history")) ?: error("no listening history")
        val sections = first.sections.toMutableList()
        readContinuations(first.continuation) {
            val next = InnertubeParser.parseHistoryPage(it) ?: return@readContinuations null
            sections += next.sections
            next.continuation
        }
        check(session == revision.value)
        return sections.map { it.title to it.songs }
    }

    private suspend fun loadLikes(): List<Song> {
        val response = signedBrowse("VLLM")
        val page = InnertubeParser.parsePlaylistShelf(response)
        val first = page?.songs?.ifEmpty { InnertubeParser.collectSongsDeep(response) }
            ?: InnertubeParser.collectSongsDeep(response).also { check(it.isNotEmpty()) }
        return (first + songContinuations(page?.continuation)).distinctBy { it.videoId }
    }

    private suspend fun loadPlaylists(): List<ShelfItem> {
        val response = signedBrowse("FEmusic_liked_playlists")
        val page = InnertubeParser.parseLibraryItemPage(response)
        val items = page.items.toMutableList()
        readContinuations(page.continuation) {
            val next = InnertubeParser.parseLibraryItemPage(it)
            items += next.items
            next.continuation
        }
        if (items.isEmpty()) items += InnertubeParser.looseLibraryPlaylists(response)
        if (items.isEmpty()) {
            val landing = signedBrowse("FEmusic_library_landing")
            items += InnertubeParser.parseLibraryItems(landing).ifEmpty { InnertubeParser.looseLibraryPlaylists(landing) }
        }
        return items.filter { it.browseId?.let(InnertubeParser::isEditablePlaylist) == true }.distinctBy { it.browseId }
    }

    private suspend fun songContinuations(token: String?): List<Song> {
        val songs = mutableListOf<Song>()
        readContinuations(token) {
            val page = InnertubeParser.parsePlaylistContinuation(it)
            songs += page.songs
            page.continuation
        }
        return songs
    }

    private suspend fun readContinuations(first: String?, add: (JsonObject) -> String?) {
        val seen = HashSet<String>()
        var token = first
        while (token != null && seen.size < 80 && seen.add(token)) {
            token = add(Innertube.browseContinuation(token).verifiedSession())
        }
    }

    private suspend fun signedBrowse(id: String): JsonObject = Innertube.browse(id).verifiedSession()

    private fun JsonObject.verifiedSession(): JsonObject {
        if (InnertubeParser.answeredSignedOut(this)) throw ExpiredSession()
        return this
    }

    private class ExpiredSession : IllegalStateException()

    private suspend fun <T> request(action: suspend () -> T): Result<T> = try {
        Result.success(action())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
