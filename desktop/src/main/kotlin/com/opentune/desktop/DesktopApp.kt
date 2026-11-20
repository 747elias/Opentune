package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import com.opentune.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import com.opentune.data.model.ShelfItem
import com.opentune.data.ContentFilter
import com.opentune.data.history.DailyMixes
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.subsonic.Subsonic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.focusRequester
import com.opentune.ui.PageMotion
import com.opentune.ui.components.glass
import com.opentune.ui.components.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The sidebar's pages; an opened album, playlist or artist goes over them. */
internal enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    NEW("Explore", Icons.Rounded.GridView, Icons.Rounded.GridView),
    SEARCH("Search", Icons.Rounded.Search, Icons.Rounded.Search),
    LIBRARY("Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    PODCASTS("Podcasts", Icons.Rounded.Podcasts, Icons.Rounded.Podcasts),
    RADIO("Radio", Icons.Rounded.Radio, Icons.Rounded.Radio),
    ROOMS("Listen together", Icons.Rounded.Groups, Icons.Rounded.Groups),
    RECOGNIZE("Recognize music", Icons.Rounded.Mic, Icons.Rounded.Mic),
    QUEUE("Up next", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Rounded.QueueMusic),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

/** A playlist made here, opened from the sidebar or Library. */
internal data class PlaylistKey(val id: String)

private data class PodcastKey(val id: String)

/** What fills the page area: a tab, or something opened over it. */
private data class Page(val tab: Tab, val shelf: Shelf? = null, val opened: Any? = null)

/**
 * The window, as BitChord's desktop lays it out: a sidebar down the left
 * (pages, history, your playlists, Settings), the page with the player bar
 * frosted across its top, and Now playing (or the lyrics, or the queue) in a
 * column on the right when asked ([startPanel] opens with one, for
 * pictures). Clicking the song in the bar opens the full player.
 */
@Composable
internal fun DesktopApp(startPanel: SidePanel? = null, startTab: Tab = Tab.HOME) {
    var page by remember { mutableStateOf(Page(startTab)) }
    // Back goes through what was opened, the way it came.
    val history = remember { mutableStateListOf<Page>() }
    var query by remember { mutableStateOf("") }
    var searches by remember { mutableStateOf(0) }
    var playerOpen by remember { mutableStateOf(false) }
    var openLink by remember { mutableStateOf(false) }
    val notices = remember { androidx.compose.material3.SnackbarHostState() }
    var panel by remember { mutableStateOf(startPanel) }
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val song = queue.getOrNull(index)
    val ui by AppSettings.ui.collectAsState()
    val accountRevision by DesktopAccount.revision.collectAsState()
    fun go(to: Page) {
        if (to == page) return
        history.add(page)
        if (history.size > 30) history.removeAt(0)
        page = to
    }
    val back: () -> Unit = { history.removeLastOrNull()?.let { page = it } }
    LaunchedEffect(Unit) { DesktopLinks.openRequests.collect { openLink = true } }
    LaunchedEffect(Unit) { DesktopRooms.notices.collect { notices.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        hiddenMusicNotices.collect { notice ->
            val action = notice.undo?.let { com.opentune.i18n.Tr.t("Undo") }
            if (notices.showSnackbar(notice.message, actionLabel = action, duration = androidx.compose.material3.SnackbarDuration.Long) == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                notice.undo?.invoke()
            }
        }
    }
    LaunchedEffect(Unit) { DesktopArtistReleases.openings.collect { go(Page(Tab.NEW, opened = it)) } }
    val releaseNotice by DesktopArtistReleases.latest.collectAsState()
    LaunchedEffect(releaseNotice) {
        val release = releaseNotice ?: return@LaunchedEffect
        val message = com.opentune.i18n.Tr.f("New from {0}", release.artistName) + " · " + release.item.title
        if (notices.showSnackbar(message, actionLabel = com.opentune.i18n.Tr.t("Open"), duration = androidx.compose.material3.SnackbarDuration.Long) == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            go(Page(Tab.NEW, opened = release.item))
        }
        DesktopArtistReleases.clearLatest(release)
    }
    LaunchedEffect(Unit) {
        DesktopLinks.links.collect { link ->
            when (link) {
                is DesktopLinks.Link.Track -> {
                    val songs = runCatching {
                        com.opentune.data.innertube.InnertubeParser.parseWatchQueue(com.opentune.data.innertube.Innertube.next(link.id))
                    }.getOrDefault(emptyList())
                    val track = songs.firstOrNull { it.videoId == link.id }
                        ?: Song(link.id, "YouTube Music", "", "https://i.ytimg.com/vi/${link.id}/mqdefault.jpg")
                    Player.playAll(listOf(track), 0)
                }
                is DesktopLinks.Link.Playlist -> go(Page(Tab.LIBRARY, opened = ShelfItem("YouTube Music playlist", "", null, null, link.id)))
                is DesktopLinks.Link.Room -> if (DesktopRooms.showInvite(link.code)) go(Page(Tab.ROOMS))
            }
        }
    }
    val sideSelected: Any? = when {
        page.opened is PlaylistKey -> page.opened
        page.opened != null -> null
        page.tab == Tab.LIBRARY -> page.shelf ?: Tab.LIBRARY
        else -> page.tab
    }

    DesktopTheme(artworkSeed = rememberArtworkSeed(song?.thumbnailUrl)) {
        // Windows 11's Mica or Acrylic behind the window, when it's on: the page lets it through.
        val material by WindowsFrame.activeMaterial.collectAsState()
        val chosenMaterial by WindowsFrame.material.collectAsState()
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        androidx.compose.runtime.LaunchedEffect(dark, chosenMaterial) { WindowsFrame.applyMaterial(dark) }
        val clear = material != WindowsFrame.Material.OFF
        // The ink named outright: Surface works it out from its colour only when that is the
        // theme's own, and the see-through page over a material isn't, which left text black.
        Surface(
            color = MaterialTheme.colorScheme.background.copy(alpha = if (clear) MATERIAL_PAGE_ALPHA else 1f),
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val roomForPanel = maxWidth >= 1100.dp
                val panelShown = panel != null && song != null && roomForPanel
                val folded = maxWidth < 900.dp
                val haze = rememberHazeState()
                // Behind the whole window, so the see-through sidebar and side column take its colour
                // as well as the page; a source for the top bar's blur under the page's own.
                Box(Modifier.fillMaxSize().hazeSource(haze, zIndex = 0f)) { WindowBackdrop(song?.thumbnailUrl, clear) }
                Row(Modifier.fillMaxSize()) {
                    Sidebar(sideSelected, folded = folded) { key ->
                        when (key) {
                            is Tab -> go(Page(key))
                            is Shelf -> go(Page(Tab.LIBRARY, key))
                            is PlaylistKey -> go(Page(Tab.LIBRARY, Shelf.PLAYLISTS, key))
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        // The page is what the frosted player bar blurs; every page starts below it.
                        CompositionLocalProvider(
                            LocalPageTop provides TOP_BAR_HEIGHT + DesktopWindow.topInset,
                            LocalPageBottom provides 40.dp,
                            com.opentune.ui.components.LocalHazeState provides haze.takeIf { !ui.reduceBlur },
                        ) {
                            Box(Modifier.fillMaxSize().hazeSource(haze, zIndex = 1f)) {
                                androidx.compose.runtime.key(accountRevision) { Pages(
                                    page = page,
                                    still = ui.reduceAnimation,
                                    queue = queue,
                                    index = index,
                                    query = query,
                                    onQuery = { query = it },
                                    searches = searches,
                                    onSearch = { if (!DesktopLinks.submit(query)) searches++ },
                                    onOpen = { go(page.copy(opened = it)) },
                                    onOpenAny = { opened ->
                                        if (opened is Tab) go(Page(opened)) else go(page.copy(opened = opened))
                                    },
                                    onShelf = { go(Page(Tab.LIBRARY, it)) },
                                    onPlaylist = { go(Page(Tab.LIBRARY, Shelf.PLAYLISTS, PlaylistKey(it))) },
                                    onBack = { back() },
                                ) }
                            }
                            TopBar(
                                song, canGoBack = history.isNotEmpty(), onBack = back,
                                panel = panel.takeIf { panelShown },
                                onPanel = { p -> panel = if (panel == p && panelShown) null else p },
                                onOpenPlayer = { playerOpen = true },
                                modifier = Modifier.align(Alignment.TopCenter),
                                panelRoom = roomForPanel,
                            )
                        }
                    }
                    val shown = rememberLast(panel)
                    AnimatedVisibility(
                        visible = panelShown,
                        enter = if (ui.reduceAnimation) fadeIn(tween(140)) else expandHorizontally(Motion.soft(), Alignment.Start) + fadeIn(tween(220, 80)),
                        exit = if (ui.reduceAnimation) fadeOut(tween(120)) else shrinkHorizontally(tween(260), Alignment.Start) + fadeOut(tween(160)),
                    ) {
                        if (song != null && shown != null) RightPanel(shown, song, queue, index, onPanel = { panel = it }, onOpenArtist = { go(page.copy(opened = it)) })
                    }
                }

                // The full player rises over everything.
                val full = rememberLast(song)
                AnimatedVisibility(
                    visible = playerOpen && song != null,
                    enter = PageMotion.playerIn(ui.playerMotion, ui.reduceAnimation),
                    exit = PageMotion.playerOut(ui.playerMotion, ui.reduceAnimation),
                ) { full?.let { NowPlaying(it, docked = false, onClose = { playerOpen = false }) } }
                androidx.compose.material3.SnackbarHost(notices, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
        if (openLink) {
            var link by remember { mutableStateOf("") }
            var invalid by remember { mutableStateOf(false) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { openLink = false },
                title = { Text("Open link") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        link, { link = it; invalid = false }, label = { Text("YouTube Music link or room invite") },
                        singleLine = true, isError = invalid, modifier = Modifier.fillMaxWidth(),
                        supportingText = if (invalid) ({ Text("Enter a valid link.") }) else null,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { if (DesktopLinks.submit(link)) openLink = false else invalid = true }) { Text("Open") }
                },
                dismissButton = { TextButton(onClick = { openLink = false }) { Text("Cancel") } },
            )
        }
    }
}

/**
 * The open page: the new one fades in with a short rise (a short drop going
 * back), the old one fades out.
 */
@Composable
private fun Pages(
    page: Page,
    still: Boolean,
    queue: List<Song>,
    index: Int,
    query: String,
    onQuery: (String) -> Unit,
    searches: Int,
    onSearch: () -> Unit,
    onOpen: (ShelfItem) -> Unit,
    onOpenAny: (Any) -> Unit,
    onShelf: (Shelf) -> Unit,
    onPlaylist: (String) -> Unit,
    onBack: () -> Unit,
) {
    val pxPerDp = androidx.compose.ui.platform.LocalDensity.current.density
    AnimatedContent(
        page,
        transitionSpec = {
            // A desktop page changes with a quick fade and a short rise, not the phone's
            // full-page slides and scales: moving a whole window of covers each frame stutters.
            val back = initialState.opened != null && targetState.opened == null
            val rise = (if (still) 0 else if (back) -12 else 16) * pxPerDp
            (fadeIn(tween(if (still) 120 else 200, delayMillis = if (still) 0 else 40)) +
                slideInVertically(tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) { rise.toInt() }) togetherWith
                fadeOut(tween(if (still) 90 else 110))
        },
        label = "page",
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        val opened = shown.opened
        when {
            opened is ShelfItem && opened.browseId?.startsWith("UC") == true -> ArtistView(opened, onOpen)
            opened is ShelfItem && opened.browseId?.startsWith("MPSP") == true -> PodcastShowPage(opened.browseId!!)
            opened is ShelfItem -> CollectionPage(opened)
            opened is PodcastKey -> PodcastShowPage(opened.id)
            opened is AudiobooksKey -> AudiobooksPage { onOpenAny(AudiobookKey(it)) }
            opened is AudiobookKey -> AudiobookPage(opened.id)
            opened is PlaylistKey -> PlaylistPage(opened.id, onBack = onBack)
            opened is MoodKey -> MoodPage(opened, onOpen)
            opened is DailyMixes.Mix -> MixPage(opened)
            opened is AccountLibraryKey -> AccountLibraryPage(onOpenAny, { onOpenAny(Tab.SETTINGS) })
            opened is SpotifyLibraryKey -> SpotifyLibraryPage { onOpenAny(Tab.SETTINGS) }
            opened is AccountPlaylistKey -> AccountPlaylistPage(opened, onBack)
            opened is SmartPlaylistKey -> SmartPlaylistPage(opened.id, onBack, onPlaylist)
            shown.tab == Tab.SEARCH -> SearchPage(query, onQuery, searches, onSearch, onOpenAny)
            shown.tab == Tab.NEW -> NewPage(onOpen)
            shown.tab == Tab.HOME -> HomePage(onOpen = onOpen, onOpenAny = onOpenAny)
            shown.tab == Tab.PODCASTS -> PodcastsPage({ onOpenAny(PodcastKey(it)) }, { onOpenAny(AudiobooksKey) })
            shown.tab == Tab.RADIO -> RadioPage()
            shown.tab == Tab.ROOMS -> RoomsPage()
            shown.tab == Tab.RECOGNIZE -> RecognizePage()
            shown.tab == Tab.LIBRARY -> shown.shelf?.let { LibraryPage(it, onShelf, onPlaylist, onOpenAny) }
                ?: LibraryHome(onShelf = onShelf, onPlaylist = onPlaylist, onOpenAny = onOpenAny)
            shown.tab == Tab.SETTINGS -> SettingsPage()
            else -> SongPage("Up next", queue, "The queue is empty.", current = index, onPlay = { _, i -> Player.playAt(i) })
        }
    }
}

/** The last non-null [value], so something leaving can be drawn as it goes. */
@Composable
internal fun <T> rememberLast(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    if (value != null) last.value = value
    return last.value
}

/** Room under each page's last row, clear of whatever floats over the bottom of the window. */
internal val LocalPageBottom = staticCompositionLocalOf { 32.dp }
/** Room every page keeps clear at its top, under the frosted player bar. */
internal val LocalPageTop = staticCompositionLocalOf { 0.dp }

/** How much of the page colour lies over Windows' material, keeping text on it readable. */
private const val MATERIAL_PAGE_ALPHA = 0.45f

// ---- Pages -------------------------------------------------------------------

/**
 * Search: a big field at the top. Before a search, what you searched for
 * lately as chips and every mood and genre as coloured tiles to browse; after
 * it, the best match as a large card beside the first few songs, then every
 * song found.
 */
@Composable
internal fun SearchPage(query: String, onQuery: (String) -> Unit, searches: Int, onSearch: () -> Unit, onOpenAny: (Any) -> Unit) {
    var request by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val submitted = request?.first
    var filter by remember { mutableStateOf(SearchFilter.ALL) }
    val library by AppSettings.library.collectAsState()
    var results by remember(request, filter, library.hideExplicit) { mutableStateOf<InnertubeParser.SearchPage?>(null) }
    var failed by remember(request, filter, library.hideExplicit) { mutableStateOf(false) }
    var moreFailed by remember(request, filter, library.hideExplicit) { mutableStateOf(false) }
    var loadingMore by remember(request, filter, library.hideExplicit) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val recentSearches by AppSettings.recentSearches.collectAsState()
    val moods by produceState(emptyList<com.opentune.data.model.MoodGenre>()) { value = runCatching { Pages.moods() }.getOrDefault(emptyList()) }
    LaunchedEffect(searches) {
        if (searches == 0) return@LaunchedEffect
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        AppSettings.addRecentSearch(q)
        request = q to searches
    }
    LaunchedEffect(query.isBlank()) { if (query.isBlank()) { request = null; filter = SearchFilter.ALL } }
    LaunchedEffect(request, filter, library.hideExplicit) {
        val q = submitted ?: return@LaunchedEffect
        try {
            results = searchPage(Innertube.search(q, filter.params), filter, library.hideExplicit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val typing = query.trim().takeIf { it.length >= 2 && it != submitted }
    var liveRows by remember(typing) { mutableStateOf(emptyList<SearchResult>()) }
    LaunchedEffect(typing, library.hideExplicit) {
        val q = typing ?: return@LaunchedEffect
        delay(350)
        try {
            liveRows = searchPage(Innertube.searchTypeahead(q), SearchFilter.ALL, library.hideExplicit).rows.take(8)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Search with Enter remains available when typeahead is offline.
        }
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val found = results
    ScrollPage("Search", spacing = 22.dp) {
        item(key = "field") { SearchField(query, onQuery, onSearch, focus) }
        if (submitted != null && typing == null) item(key = "filters") {
            LazyRow(contentPadding = PaddingValues(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchFilter.entries, key = { it.name }) { choice ->
                    FilterChip(selected = choice == filter, onClick = { filter = choice }, label = { Text(choice.label) })
                }
            }
        }
        when {
            typing != null -> if (liveRows.isNotEmpty()) searchRows(liveRows, false, onOpenAny)
            submitted == null -> {
                if (recentSearches.isNotEmpty()) item(key = "recent") { RecentSearches(recentSearches) { onQuery(it); onSearch() } }
                if (moods.isNotEmpty()) item(key = "browse") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Browse all", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = GUTTER))
                        BrowseTiles(moods) { onOpenAny(MoodKey(it.title, it.browseId, it.params)) }
                    }
                }
                if (recentSearches.isEmpty() && moods.isEmpty()) item(key = "hint") { Message("Find something to play", "Type a song or an artist and press Enter.", icon = Icons.Rounded.Search) }
            }
            failed -> item(key = "failed") { Message("Couldn't search", "Check your connection and try again.", action = "Try again" to onSearch) }
            found == null -> item(key = "loading") { Loading() }
            else -> {
                if (found.rows.isEmpty()) item(key = "none") {
                    Message("No results for “$submitted”", "Try different words.", icon = Icons.Rounded.SearchOff)
                }
                searchRows(found.rows, filter == SearchFilter.ALL, onOpenAny, videos = filter == SearchFilter.VIDEOS)
                found.continuation?.let { token ->
                    item(key = "more") {
                        TextButton(modifier = Modifier.padding(horizontal = GUTTER), enabled = !loadingMore, onClick = {
                            loadingMore = true
                            moreFailed = false
                            scope.launch {
                                try {
                                    val next = searchPage(Innertube.searchContinuation(token), filter, library.hideExplicit)
                                    if (results === found) results = InnertubeParser.SearchPage(
                                        (found.rows + next.rows).distinctBy(::searchRowId), next.continuation.takeUnless { it == token },
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    moreFailed = true
                                } finally {
                                    loadingMore = false
                                }
                            }
                        }) {
                            Text(when {
                                loadingMore -> "Loading more…"
                                moreFailed -> "Try again"
                                else -> "Load more"
                            })
                        }
                    }
                }
            }
        }
    }
}

private fun searchPage(response: kotlinx.serialization.json.JsonObject, filter: SearchFilter, hideExplicit: Boolean): InnertubeParser.SearchPage {
    val page = InnertubeParser.parseSearchPage(response, includeVideos = filter == SearchFilter.VIDEOS)
    return page.copy(rows = ContentFilter.results(page.rows, hideExplicit))
}

private fun searchRowId(row: SearchResult): String = when (row) {
    is SearchResult.TopTrack -> "v:${row.song.videoId}"
    is SearchResult.Track -> "v:${row.song.videoId}"
    is SearchResult.Browse -> "b:${row.item.browseId}"
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchRows(
    rows: List<SearchResult>, promoted: Boolean, onOpen: (Any) -> Unit, videos: Boolean = false,
) {
    val songs = Pages.songsIn(rows)
    if (promoted && rows.firstOrNull() is SearchResult.TopTrack && songs.isNotEmpty()) item(key = "top") { TopResult(songs) }
    val grouped = rows.filterIsInstance<SearchResult.Browse>().map { it.item }.groupBy { it.type }
    grouped.forEach { (type, items) ->
        val title = when (type) {
            BrowseType.ALBUM -> "Albums"
            BrowseType.ARTIST -> "Artists"
            BrowseType.PLAYLIST -> "Playlists"
            BrowseType.OTHER -> "More results"
        }
        val cards = items.map { ShelfItem(it.title, it.subtitle, it.thumbnailUrl, null, it.browseId, it.explicit) }
        item(key = "browse-${type.name}") { ShelfRow(Pages.Shelf(title, cards)) { onOpen(it) } }
    }
    if (songs.isNotEmpty()) item(key = "all-head") {
        Text(if (videos) "Videos" else "Songs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = GUTTER, vertical = 4.dp))
    }
    tracks(songs)
}

/** The search box: wide, rounded and frosted, with a clear button once there's something in it. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, focus: androidx.compose.ui.focus.FocusRequester) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.padding(horizontal = GUTTER).widthIn(max = 720.dp).fillMaxWidth().height(56.dp)
            .glass(shape, onSurface.copy(alpha = 0.08f)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = muted, modifier = Modifier.size(22.dp))
        Box(Modifier.weight(1f).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("What do you want to listen to?", color = muted, style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.text.BasicTextField(
                query, onQuery, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                        (e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter)
                    ) { onSearch(); true } else false
                },
            )
        }
        if (query.isNotEmpty()) HoverIcon(Icons.Rounded.Close, "Clear", size = 30.dp, iconSize = 18.dp) { onQuery("") }
    }
}

/** The best match, large, beside the next four songs. */
@Composable
private fun TopResult(found: List<Song>) {
    val top = found.first()
    val base = MaterialTheme.colorScheme.surfaceContainer
    val colour = rememberCoverColor(top.thumbnailUrl, base)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(Modifier.padding(horizontal = GUTTER).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Top result", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Box(
                Modifier.fillMaxWidth().height(232.dp).hoverLift(hover, 1.01f).clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(tint(colour, base, 0.55f), tint(colour, base, 0.2f))))
                    .clickable(interactionSource = hover, indication = null) { Player.playAll(found, 0) }.padding(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Cover(top.thumbnailUrl, 104.dp, RoundedCornerShape(12.dp), Modifier.shadow(16.dp, RoundedCornerShape(12.dp)))
                    Text(top.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(top.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Song", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.3f)).padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    hovered, enter = fadeIn(Motion.fade(160)) + slideInVertically(Motion.soft()) { it / 2 }, exit = fadeOut(Motion.fade(120)),
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    Box(Modifier.size(52.dp).shadow(14.dp, CircleShape).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Songs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            found.drop(1).take(4).forEachIndexed { i, s ->
                val h = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().hoverWash(h, RoundedCornerShape(10.dp)).clickable(interactionSource = h, indication = null) { Player.playAll(found, i + 1) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(s.thumbnailUrl, 44.dp, RoundedCornerShape(8.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(s.durationText.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Every mood and genre as a coloured tile, in a grid that fills the width. */
@Composable
private fun BrowseTiles(moods: List<com.opentune.data.model.MoodGenre>, onOpen: (com.opentune.data.model.MoodGenre) -> Unit) {
    val palette = listOf(0xFF2E7D32, 0xFF1565C0, 0xFF6A1B9A, 0xFFC62828, 0xFFEF6C00, 0xFF00838F, 0xFFAD1457, 0xFF4E342E, 0xFF283593, 0xFF9E9D24)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(horizontal = GUTTER).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        moods.forEach { m ->
            val hover = remember { MutableInteractionSource() }
            val colour = Color(palette[(m.title.hashCode() and 0x7fffffff) % palette.size])
            Box(
                Modifier.width(210.dp).height(110.dp).hoverLift(hover, 1.03f).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(colour, androidx.compose.ui.graphics.lerp(colour, Color.Black, 0.4f))))
                    .clickable(interactionSource = hover, indication = null) { onOpen(m) }.padding(16.dp),
            ) { Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2) }
        }
    }
}

/** What you searched for lately, as chips; each comes off with its cross. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecentSearches(queries: List<String>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = GUTTER), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent searches", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = AppSettings::clearRecentSearches) { Text("Clear") }
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            queries.forEach { q ->
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                val onSurface = MaterialTheme.colorScheme.onSurface
                val fill by animateColorAsState(onSurface.copy(alpha = if (hovered) 0.14f else 0.08f), tween(140), label = "recent chip")
                Row(
                    Modifier.clip(CircleShape).background(fill).hoverable(hover).clickable(interactionSource = hover, indication = null) { onPick(q) }
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Text(q, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
                    HoverIcon(Icons.Rounded.Close, "Remove", size = 24.dp, iconSize = 14.dp) { AppSettings.removeRecentSearch(q) }
                }
            }
        }
    }
}

/**
 * One of the Library's lists: liked songs and what you played as collection
 * pages with a cover of their own, your playlists as tiles, your server.
 */
@Composable
internal fun LibraryPage(shelf: Shelf, onShelf: (Shelf) -> Unit, onPlaylist: (String) -> Unit, onOpenAny: (Any) -> Unit = {}) {
    val liked by DesktopAccount.liked.collectAsState()
    val played by Library.recent.collectAsState()
    val server by Subsonic.server.collectAsState()
    when (shelf) {
        Shelf.LIKED -> CollectionLayout("Liked songs", liked.firstOrNull()?.thumbnailUrl, liked, failed = false, onRemove = { DesktopAccount.setLiked(it, false) }, empty = "Click the heart on a song to keep it here.") {
            CollectionHero(
                cover = { m -> IconCover(Icons.Rounded.Favorite, listOf(Color(0xFF5B3CFF), Color(0xFFB7A6FF)), m) },
                coverUrl = liked.firstOrNull()?.thumbnailUrl, kind = "Playlist", title = "Liked songs", subtitle = "Songs you hearted, newest first", songs = liked,
            )
        }
        Shelf.RECENT -> CollectionLayout("Recently played", played.firstOrNull()?.thumbnailUrl, played, failed = false, empty = "Songs you play show here.") {
            CollectionHero(
                cover = { m -> IconCover(Icons.Rounded.History, listOf(Color(0xFF0F7B6C), Color(0xFF7FE0C8)), m) },
                coverUrl = played.firstOrNull()?.thumbnailUrl, kind = "History", title = "Recently played", subtitle = "The last songs you played here", songs = played,
            )
        }
        Shelf.PLAYLISTS -> Column(Modifier.fillMaxSize().padding(top = LocalPageTop.current + 28.dp)) {
            Text("Playlists", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = GUTTER, vertical = 4.dp))
            PlaylistGrid(onOpen = onPlaylist)
        }
        Shelf.SERVER -> Box(Modifier.fillMaxSize().padding(top = LocalPageTop.current + 12.dp)) {
            if (server != null) ServerShelf() else Message("No music server connected", "Connect one in Settings.")
        }
        Shelf.LOCAL -> LocalMusicPage()
        Shelf.DOWNLOADS -> DownloadsPage()
        Shelf.SMART -> SmartPlaylistsPage { onOpenAny(SmartPlaylistKey(it)) }
        Shelf.REPLAY -> ReplayPage()
    }
}

/** A list's own cover where it has no artwork: its icon on a gradient. */
@Composable
internal fun IconCover(icon: ImageVector, colours: List<Color>, modifier: Modifier) {
    Box(modifier.background(Brush.linearGradient(colours)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(84.dp))
    }
}

internal enum class Shelf(val label: String) {
    LIKED("Liked songs"), PLAYLISTS("Playlists"), SERVER("Your server"), RECENT("Recently played"),
    LOCAL("Local files"), DOWNLOADS("Downloads"), SMART("Smart playlists"), REPLAY("Replay"),
}

/** A plain list of songs under a big title, such as Up next. */
@Composable
private fun SongPage(title: String, songs: List<Song>, empty: String, current: Int = -1, onPlay: (List<Song>, Int) -> Unit) {
    ScrollPage(title, if (songs.isEmpty()) null else lengthLine(songs), spacing = 4.dp) {
        if (songs.isEmpty()) item { Message(empty, null) }
        else {
            item { TrackHeader() }
            tracks(songs)
        }
    }
}

/** An album, playlist or podcast: the head over a blur of its cover, then its tracks. */
@Composable
internal fun CollectionPage(item: ShelfItem) {
    var songs by remember(item) { mutableStateOf<List<Song>?>(null) }
    var failed by remember(item) { mutableStateOf(false) }
    LaunchedEffect(item) {
        runCatching { Pages.songsOf(item.browseId!!) }.onSuccess { songs = it }.onFailure { failed = true }
    }
    val id = item.browseId.orEmpty()
    val kind = when {
        id.startsWith("MPSP") -> "Podcast"
        id.startsWith("MPRE") || id.startsWith("OLAK") -> item.subtitle.substringBefore(" • ").takeIf { it in setOf("Album", "Single", "EP") } ?: "Album"
        else -> "Playlist"
    }
    CollectionLayout(item.title, item.thumbnailUrl, songs, failed) {
        CollectionHero(
            cover = { m -> Cover(item.thumbnailUrl, 228.dp, RoundedCornerShape(14.dp), m) },
            coverUrl = item.thumbnailUrl, kind = kind, title = item.title,
            subtitle = item.subtitle.split(" • ").filter { it !in setOf("Album", "Single", "EP", "Playlist") }.joinToString(" • "),
            songs = songs,
            extra = { songs?.let { DownloadAllButton(it) } },
        )
    }
}

// ---- Rows and cards ----------------------------------------------------------

/** Songs, one to a row: play on click, add to a playlist, like, and [onRemove] where a list can lose them. */
@Composable
internal fun SongList(songs: List<Song>, current: Int = -1, onRemove: ((Song) -> Unit)? = null, onPlay: (List<Song>, Int) -> Unit) {
    val liked by DesktopAccount.liked.collectAsState()
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val playingId = queue.getOrNull(index)?.videoId
    val playingNow by Player.playing.collectAsState()
    // Rows rise in only as the list first shows; scrolled back into view, they're simply there.
    val since = remember(songs) { System.nanoTime() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = LocalPageBottom.current)) {
        itemsIndexed(songs, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
            val isLiked = liked.any { it.videoId == song.videoId }
            val isCurrent = if (current >= 0) i == current else song.videoId == playingId
            var adding by remember { mutableStateOf(false) }
            val hover = remember { MutableInteractionSource() }
            Row(
                Modifier.appear(i, since).padding(horizontal = 8.dp).fillMaxWidth().hoverWash(hover, RoundedCornerShape(12.dp))
                    .clickable(interactionSource = hover, indication = null) { onPlay(songs, i) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Cover(song.thumbnailUrl, 52.dp, MaterialTheme.shapes.small)
                    androidx.compose.animation.AnimatedVisibility(isCurrent, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
                        Box(Modifier.size(52.dp).clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            PlayingBars(playingNow)
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(
                        song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.durationText).joinToString(" • "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DownloadTrackButton(song)
                Box {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AddToPlaylistMenu(song, adding) { adding = false }
                }
                LikeButton(isLiked, { DesktopAccount.setLiked(song, !isLiked) }, size = 24.dp)
                SuggestionActions(song)
                if (onRemove != null) {
                    IconButton(onClick = { onRemove(song) }) {
                        Icon(Icons.Rounded.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * A card: the cover, which eases a little larger inside its frame under the
 * pointer while a shade comes up and a play button rises into its corner;
 * the title and grey line under it. An artist's is round.
 */
@Composable
internal fun Card(item: ShelfItem, onClick: () -> Unit) {
    val round = item.browseId?.startsWith("UC") == true
    val align = if (round) TextAlign.Center else TextAlign.Start
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val zoom by animateFloatAsState(if (hovered) 1.07f else 1f, tween(420, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)), label = "card zoom")
    val shade by animateFloatAsState(if (hovered) 1f else 0f, tween(220), label = "card shade")
    val shape = if (round) CircleShape else RoundedCornerShape(12.dp)
    Column(
        Modifier.width(172.dp).hoverable(hover).clickable(interactionSource = hover, indication = null, onClick = onClick).padding(bottom = 4.dp),
    ) {
        Box(Modifier.size(172.dp).shadow(if (hovered) 18.dp else 6.dp, shape).clip(shape)) {
            Cover(item.thumbnailUrl, 172.dp, shape, Modifier.graphicsLayer { scaleX = zoom; scaleY = zoom })
            Box(Modifier.matchParentSize().graphicsLayer { alpha = shade }.background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
            androidx.compose.animation.AnimatedVisibility(
                hovered, enter = fadeIn(Motion.fade(160)) + slideInVertically(Motion.soft()) { it / 2 }, exit = fadeOut(Motion.fade(120)) + slideOutVertically(tween(160)) { it / 2 },
                modifier = Modifier.align(if (round) Alignment.Center else Alignment.BottomEnd).padding(10.dp),
            ) {
                Box(Modifier.size(44.dp).shadow(12.dp, CircleShape).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(26.dp))
                }
            }
        }
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = align, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Text(
            item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun Cover(url: String?, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    AsyncImage(
        // A song on your own server carries a reference to its cover, without the credentials.
        model = remember(url) { ImageRequest.Builder(context).data(Subsonic.resolveCover(url)).crossfade(260).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).aspectRatio(1f).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** A page's big bold name, as the phone's pages start. */
@Composable
internal fun PageTitle(text: String) {
    Text(text, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

/** A round glass button holding one icon, as the phone's pages put back, menu and settings. */
@Composable
internal fun GlassCircle(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(
        modifier.size(size).pressable(onClick).glass(CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(size * 0.46f), tint = MaterialTheme.colorScheme.onSurface) }
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun Message(title: String, message: String?, icon: ImageVector? = null, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.appear(0).fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon?.let { Icon(it, null, Modifier.size(40.dp).padding(bottom = 8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(title, style = MaterialTheme.typography.titleMedium)
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)) }
        action?.let { (label, run) -> TextButton(onClick = run) { Text(label) } }
    }
}

/** Minutes and seconds, as the bar shows them. */
internal object Time {
    fun format(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
