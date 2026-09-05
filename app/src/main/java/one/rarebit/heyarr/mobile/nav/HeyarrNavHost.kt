package one.rarebit.heyarr.mobile.nav

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import okhttp3.OkHttpClient
import one.rarebit.heyarr.mobile.AppViewModel
import one.rarebit.heyarr.mobile.HeyarrTopBar
import one.rarebit.heyarr.mobile.device.EnrolScreen
import one.rarebit.heyarr.mobile.acquisition.WantDetailScreen
import one.rarebit.heyarr.mobile.acquisition.WantDetailViewModel
import one.rarebit.heyarr.mobile.acquisition.WantsClient
import one.rarebit.heyarr.mobile.acquisition.WantsScreen
import one.rarebit.heyarr.mobile.acquisition.WantsViewModel
import one.rarebit.heyarr.mobile.catalog.Artwork
import one.rarebit.heyarr.mobile.catalog.CatalogClient
import one.rarebit.heyarr.mobile.catalog.ContinueClient
import one.rarebit.heyarr.mobile.home.HomeScreen
import one.rarebit.heyarr.mobile.home.HomeViewModel
import one.rarebit.heyarr.mobile.hub.HubScreen
import one.rarebit.heyarr.mobile.hub.HubViewModel
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.LibraryScreen
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkDetailClient
import one.rarebit.heyarr.mobile.library.WorkDetailScreen
import one.rarebit.heyarr.mobile.library.WorkDetailUiState
import one.rarebit.heyarr.mobile.library.WorkDetailViewModel
import one.rarebit.heyarr.mobile.login.LoginUiState
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.music.AlbumScreen
import one.rarebit.heyarr.mobile.music.AlbumViewModel
import one.rarebit.heyarr.mobile.music.ArtistScreen
import one.rarebit.heyarr.mobile.music.ArtistViewModel
import one.rarebit.heyarr.mobile.music.ArtistsScreen
import one.rarebit.heyarr.mobile.music.ArtistsViewModel
import one.rarebit.heyarr.mobile.music.MusicClient
import one.rarebit.heyarr.mobile.music.trackTitle
import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.AudioPlayer
import one.rarebit.heyarr.mobile.playback.MediaMime
import one.rarebit.heyarr.mobile.playback.MiniPlayer
import one.rarebit.heyarr.mobile.playback.NowPlayingScreen
import one.rarebit.heyarr.mobile.playback.PlaybackClient
import one.rarebit.heyarr.mobile.playback.PlaybackProgress
import one.rarebit.heyarr.mobile.playback.PlayerScreen
import one.rarebit.heyarr.mobile.reader.ReaderActivity
import one.rarebit.heyarr.mobile.reader.ReaderEntryScreen
import one.rarebit.heyarr.mobile.search.FollowedSourceDetailScreen
import one.rarebit.heyarr.mobile.search.FollowingClient
import one.rarebit.heyarr.mobile.search.FollowingScreen
import one.rarebit.heyarr.mobile.search.SearchScreen
import one.rarebit.heyarr.mobile.search.SearchViewModel
import one.rarebit.heyarr.mobile.sessionSubtitle

/** The bottom bar, in order. `Manage` is labelled "Library": that is what it manages. */
private data class Tab(val route: Route, val label: String, val glyph: String)

private val tabs = listOf(
    Tab(Route.Home, "Home", "⌂"),
    Tab(Route.Search, "Search", "⌕"),
    Tab(Route.Manage, "Library", "▤"),
    Tab(Route.Device, "Device", "⚿"),
)

/**
 * The signed-in app: a bottom bar over a typed navigation graph ([Route]), with the
 * player as a full-screen destination rather than an overlay that pre-empts the tabs.
 *
 * Every screen's clients are built from one [ApiEnv] snapshot and its ViewModel keyed
 * on it, so a node or credential-shape change rebuilds them (the discipline the
 * Search ViewModel already followed by hand). Nothing here touches auth or enrolment:
 * those screens are re-homed as routes and take the same props they always did.
 */
@UnstableApi
@Composable
fun HeyarrNavHost(
    vm: AppViewModel,
    httpClient: OkHttpClient,
    audio: AudioPlayer,
    focusDevice: Int,
    onSettings: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val config by vm.configState.collectAsStateWithLifecycle()
    val loginState by vm.loginState.collectAsStateWithLifecycle()
    val authority by vm.sessionAuthority.collectAsStateWithLifecycle()
    val libraryState by vm.libraryState.collectAsStateWithLifecycle()
    val libraryRefreshing by vm.libraryRefreshing.collectAsStateWithLifecycle()
    val enrolState by vm.enrolState.collectAsStateWithLifecycle()
    val parkedInvite by vm.parkedInvite.collectAsStateWithLifecycle()
    val nowPlaying by vm.playback.nowPlaying.collectAsStateWithLifecycle()
    val playbackNotice by vm.playback.notice.collectAsStateWithLifecycle()
    val audioState by audio.state.collectAsStateWithLifecycle()

    val credential = vm.credentialOrNull() ?: return
    val env = ApiEnv(config.baseUrl, config.defaultQualityProfile, credential, vm.transport)

    // Surface a "cannot stream" notice once, then clear it.
    val context = LocalContext.current
    LaunchedEffect(playbackNotice) {
        playbackNotice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.playback.clearNotice() }
    }
    // Something started playing: put the player in front. It pops itself when stopped.
    // Video and the audio queue are exclusive: starting one stops the other.
    LaunchedEffect(nowPlaying != null) {
        if (nowPlaying != null) { audio.stop(); navController.navigate(Route.Player) { launchSingleTop = true } }
    }
    // A deep-linked invite (each one bumps focusDevice) opens the Device tab.
    LaunchedEffect(focusDevice) { if (focusDevice > 0) navController.navigateTab(Route.Device) }

    // The search/acquire/following features share one ViewModel across their routes.
    val searchVm: SearchViewModel = viewModel(
        key = "search:${env.key}",
        factory = viewModelFactory { initializer { SearchViewModel(config, env.credential, env.transport) } },
    )

    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val fullScreen = destination?.hasRoute(Route.Player::class) == true
    val user = (loginState as? LoginUiState.Approved)?.user

    Scaffold(
        topBar = {
            if (!fullScreen) HeyarrTopBar(subtitle = sessionSubtitle(user, authority, config.baseUrl), onSettings = onSettings)
        },
        bottomBar = {
            if (!fullScreen) {
                Column {
                    if (Decisions.showMiniPlayer(fullScreen, audioState.item)) {
                        MiniPlayer(state = audioState, onOpen = { navController.navigate(Route.Player) { launchSingleTop = true } },
                            onTogglePlay = audio::togglePlayPause, onNext = audio::next)
                    }
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTab(tab.route) },
                            icon = { Text(tab.glyph) },
                            label = { Text(tab.label) },
                        )
                    }
                }
                }
            }
        },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding)
        val openWork: (Work) -> Unit = { navController.navigate(Route.WorkDetail(it.id, it.title)) }
        // A tap on a card means different things per hub: a film plays, an album opens
        // its tracks (the queue is the music experience), a book opens the reader entry.
        val playOrOpen: (Work) -> Unit = { work ->
            when (Decisions.tapFor(work)) {
                Decisions.Tap.OPEN_ALBUM -> navController.navigate(Route.Album(work.id, work.title))
                Decisions.Tap.OPEN_READER -> navController.navigate(Route.Reader(work.id, work.title))
                Decisions.Tap.PLAY -> { vm.playback.stop(); vm.playback.play(work) }
            }
        }
        val listen: (Work, List<WorkAsset>, Int) -> Unit = { work, tracks, start ->
            vm.playback.stop()
            val (items, index) = Decisions.queueFor(env.baseUrl, work, tracks, start)
            if (items.isNotEmpty()) audio.playQueue(items, index)
        }

        NavHost(navController = navController, startDestination = Route.Home) {
            composable<Route.Home> {
                val homeVm: HomeViewModel = viewModel(
                    key = "home:${env.key}",
                    factory = viewModelFactory {
                        initializer {
                            HomeViewModel(
                                CatalogClient(env.transport, env.baseUrl, env.credential),
                                ContinueClient(env.transport, env.baseUrl, env.credential),
                            )
                        }
                    },
                )
                val home by homeVm.state.collectAsStateWithLifecycle()
                HomeScreen(
                    state = home, baseUrl = env.baseUrl, onRefresh = homeVm::refresh,
                    onOpenHub = { navController.navigate(Route.Hub(it)) },
                    onOpenWork = openWork, onPlay = playOrOpen, modifier = content,
                    onContinue = { e -> e.blobHash?.let { vm.playback.playFile(e.workTitle, e.assetId, it, e.mime, e.contentType, startSeconds = e.positionSeconds) } },
                    onOpenContinue = { navController.navigate(Route.WorkDetail(it.workId, it.workTitle)) },
                )
            }
            composable<Route.Hub> { entry ->
                val hub = entry.toRoute<Route.Hub>().kind
                val hubVm: HubViewModel = viewModel(
                    key = "hub:$hub:${env.key}",
                    factory = viewModelFactory { initializer { HubViewModel(hub, CatalogClient(env.transport, env.baseUrl, env.credential)) } },
                )
                val hubState by hubVm.state.collectAsStateWithLifecycle()
                HubScreen(
                    state = hubState, baseUrl = env.baseUrl,
                    onSelectContentType = hubVm::selectContentType, onToggleSort = hubVm::toggleSort, onLoadMore = hubVm::loadMore,
                    onOpenWork = openWork, onPlay = playOrOpen, modifier = content,
                    onArtists = if (hub == Route.HUB_MUSIC) ({ navController.navigate(Route.Artists) }) else null,
                )
            }
            composable<Route.Search> {
                val searchState by searchVm.searchState.collectAsStateWithLifecycle()
                val discoverState by searchVm.discoverState.collectAsStateWithLifecycle()
                val acquireStates by searchVm.acquireStates.collectAsStateWithLifecycle()
                SearchScreen(
                    state = searchState, discover = discoverState, acquireStates = acquireStates, baseUrl = env.baseUrl,
                    onSearch = searchVm::onSearch, onDiscover = searchVm::onDiscover,
                    onGetOnce = searchVm::onGetOnce, onFollow = searchVm::onFollow, onFollowDiscovered = searchVm::onFollowDiscovered,
                    onOpenWork = { navController.navigate(Route.WorkDetail(it.workId, it.title)) },
                    onPlayEpisode = { ep -> ep.blobHash?.let { vm.playback.playFile("${ep.workTitle} — ${ep.title}", ep.assetId ?: ep.id, it, ep.mime, ep.contentType) } },
                    onOpenEpisodeWork = { navController.navigate(Route.WorkDetail(it.workId, it.workTitle)) },
                    onFollowing = { navController.navigate(Route.Following) },
                    modifier = content,
                )
            }
            composable<Route.Manage> {
                LibraryScreen(
                    state = libraryState, refreshing = libraryRefreshing, onRefresh = vm::refreshLibrary,
                    onOpen = { navController.navigate(Route.WorkDetail(it.id, it.title, manage = true)) },
                    onFollowing = { navController.navigate(Route.Following) },
                    onWants = { navController.navigate(Route.Wants) },
                    modifier = content,
                )
            }
            composable<Route.WorkDetail> { entry ->
                val route = entry.toRoute<Route.WorkDetail>()
                val detailVm: WorkDetailViewModel = viewModel(
                    key = "work:${route.id}:${env.key}",
                    factory = viewModelFactory {
                        initializer {
                            WorkDetailViewModel(
                                work = Work(id = route.id, title = route.title ?: route.id),
                                library = LibraryClient(env.transport, env.baseUrl, env.credential),
                                detail = WorkDetailClient(env.transport, env.baseUrl, env.credential),
                                following = FollowingClient(env.transport, env.baseUrl, env.credential),
                            )
                        }
                    },
                )
                val detail by detailVm.state.collectAsStateWithLifecycle()
                val detailRefreshing by detailVm.refreshing.collectAsStateWithLifecycle()
                WorkDetailScreen(
                    state = detail, refreshing = detailRefreshing, authority = authority,
                    onRefresh = detailVm::load, onBack = { navController.popBackStack() },
                    onPlay = { work, asset ->
                        if (MediaMime.isAudio(asset.mime, asset.filename)) listen(work, listOf(asset), 0)
                        else { audio.stop(); vm.playback.playAsset(work, asset) }
                    },
                    onCancelWant = detailVm::cancelWant, onSetMonitor = detailVm::setMonitor,
                    onRetry = detailVm::retry, onSearchAgain = detailVm::searchAgain,
                    onRemoveAsset = detailVm::removeAsset, onEditWork = detailVm::editWork,
                    onDeleteWork = detailVm::deleteWork,
                    // The work is gone: pop back and refresh so the row disappears.
                    onWorkDeleted = { navController.popBackStack(); vm.refreshLibrary() },
                    onOpenSource = { navController.navigate(Route.SourceDetail(it.id)) },
                    onAuthorityRecheck = vm::loadSessionAuthority,
                    modifier = content,
                    posterUrl = (detail as? WorkDetailUiState.Loaded)?.work?.let { Artwork.posterUrl(env.baseUrl, it) },
                    manageMode = route.manage,
                    onOpenWant = { navController.navigate(Route.WantDetail(it.id)) },
                )
            }
            composable<Route.Wants> {
                val wantsVm: WantsViewModel = viewModel(
                    key = "wants:${env.key}",
                    factory = viewModelFactory {
                        initializer { WantsViewModel(WantsClient(env.transport, env.baseUrl, env.credential), LibraryClient(env.transport, env.baseUrl, env.credential)) }
                    },
                )
                val wants by wantsVm.state.collectAsStateWithLifecycle()
                WantsScreen(state = wants, onBack = { navController.popBackStack() }, onRefresh = wantsVm::load,
                    onOpen = { navController.navigate(Route.WantDetail(it.id)) }, modifier = content)
            }
            composable<Route.WantDetail> { entry ->
                val id = entry.toRoute<Route.WantDetail>().id
                val wantVm: WantDetailViewModel = viewModel(
                    key = "want:$id:${env.key}",
                    factory = viewModelFactory {
                        initializer {
                            WantDetailViewModel(id, WantsClient(env.transport, env.baseUrl, env.credential),
                                WorkDetailClient(env.transport, env.baseUrl, env.credential), LibraryClient(env.transport, env.baseUrl, env.credential))
                        }
                    },
                )
                val want by wantVm.state.collectAsStateWithLifecycle()
                WantDetailScreen(
                    state = want, canWrite = authority?.canWrite == true,
                    onBack = { navController.popBackStack() }, onRefresh = wantVm::load,
                    onSelect = wantVm::select, onSearchAgain = wantVm::searchAgain, onRetry = wantVm::retry,
                    onSetMonitor = wantVm::setMonitor, onCancel = wantVm::cancel,
                    onOpenWork = { navController.navigate(Route.WorkDetail(it)) },
                    modifier = content,
                )
            }
            composable<Route.Artists> {
                val artistsVm: ArtistsViewModel = viewModel(
                    key = "artists:${env.key}",
                    factory = viewModelFactory { initializer { ArtistsViewModel(MusicClient(env.transport, env.baseUrl, env.credential)) } },
                )
                val artists by artistsVm.state.collectAsStateWithLifecycle()
                ArtistsScreen(state = artists, baseUrl = env.baseUrl, onBack = { navController.popBackStack() },
                    onOpen = { navController.navigate(Route.Artist(it.name)) }, modifier = content)
            }
            composable<Route.Artist> { entry ->
                val name = entry.toRoute<Route.Artist>().name
                val artistVm: ArtistViewModel = viewModel(
                    key = "artist:$name:${env.key}",
                    factory = viewModelFactory { initializer { ArtistViewModel(name, MusicClient(env.transport, env.baseUrl, env.credential)) } },
                )
                val albums by artistVm.state.collectAsStateWithLifecycle()
                ArtistScreen(artist = name, state = albums, baseUrl = env.baseUrl, onBack = { navController.popBackStack() },
                    onOpenAlbum = { navController.navigate(Route.Album(it.id, it.title)) }, modifier = content)
            }
            composable<Route.Album> { entry ->
                val route = entry.toRoute<Route.Album>()
                val albumVm: AlbumViewModel = viewModel(
                    key = "album:${route.workId}:${env.key}",
                    factory = viewModelFactory {
                        initializer {
                            AlbumViewModel(route.workId, route.title,
                                LibraryClient(env.transport, env.baseUrl, env.credential), WorkDetailClient(env.transport, env.baseUrl, env.credential))
                        }
                    },
                )
                val album by albumVm.state.collectAsStateWithLifecycle()
                AlbumScreen(
                    state = album, baseUrl = env.baseUrl, nowPlayingAssetId = audioState.item?.assetId,
                    onBack = { navController.popBackStack() },
                    onPlayAll = { album.work?.let { listen(it, album.tracks, 0) } },
                    onPlayTrack = { i -> album.work?.let { listen(it, album.tracks, i) } },
                    onOpenWork = { navController.navigate(Route.WorkDetail(route.workId, route.title)) },
                    modifier = content,
                )
            }
            composable<Route.Reader> { entry ->
                val route = entry.toRoute<Route.Reader>()
                val readerVm: WorkDetailViewModel = viewModel(
                    key = "reader:${route.workId}:${env.key}",
                    factory = viewModelFactory {
                        initializer {
                            WorkDetailViewModel(
                                work = Work(id = route.workId, title = route.title ?: route.workId, kind = "book"),
                                library = LibraryClient(env.transport, env.baseUrl, env.credential),
                                detail = WorkDetailClient(env.transport, env.baseUrl, env.credential),
                                following = null,
                            )
                        }
                    },
                )
                val book by readerVm.state.collectAsStateWithLifecycle()
                ReaderEntryScreen(
                    state = book, baseUrl = env.baseUrl, onBack = { navController.popBackStack() },
                    onListen = { asset -> (book as? WorkDetailUiState.Loaded)?.let { listen(it.work, listOf(asset), 0) } },
                    onOpenWork = { navController.navigate(Route.WorkDetail(route.workId, route.title)) },
                    onRead = { asset ->
                        asset.blobHash?.let { hash ->
                            audio.stop()
                            context.startActivity(ReaderActivity.intent(context, asset.id, PlaybackClient.blobContentUrl(env.baseUrl, hash), route.title ?: asset.filename ?: ""))
                        }
                    },
                    modifier = content,
                )
            }
            composable<Route.Following> {
                val followingState by searchVm.followingState.collectAsStateWithLifecycle()
                val unfollowErrors by searchVm.unfollowErrors.collectAsStateWithLifecycle()
                val searchAuthority by searchVm.authority.collectAsStateWithLifecycle()
                FollowingScreen(
                    state = followingState, unfollowErrors = unfollowErrors, authority = searchAuthority,
                    onLoad = searchVm::loadFollowing, onAuthorityRecheck = searchVm::loadAuthority,
                    onUnfollow = searchVm::onUnfollow,
                    onOpen = { navController.navigate(Route.SourceDetail(it.id)) },
                    modifier = content,
                )
            }
            composable<Route.SourceDetail> { entry ->
                val id = entry.toRoute<Route.SourceDetail>().id
                LaunchedEffect(id) { searchVm.openSource(id) }
                val detail by searchVm.sourceDetail.collectAsStateWithLifecycle()
                val detailRefreshing by searchVm.sourceDetailRefreshing.collectAsStateWithLifecycle()
                val searchAuthority by searchVm.authority.collectAsStateWithLifecycle()
                val current = detail
                if (current == null) {
                    Text("Loading…", modifier = content.padding(16.dp))
                } else {
                    FollowedSourceDetailScreen(
                        state = current, refreshing = detailRefreshing, authority = searchAuthority,
                        onRefresh = searchVm::reloadSource,
                        onBack = { searchVm.closeSource(); navController.popBackStack() },
                        onUnfollow = searchVm::unfollowFromDetail,
                        onAuthorityRecheck = searchVm::loadAuthority,
                        modifier = content,
                    )
                }
            }
            composable<Route.Device> {
                EnrolScreen(
                    state = enrolState,
                    onCreateKey = vm::provisionDevice, onJoinInvite = vm::joinPairing,
                    onSasMatches = vm::confirmSas, onSasMismatch = vm::rejectSas,
                    onRetry = vm::retryEnrol, onForget = vm::forgetDevice,
                    onDone = { vm.useDeviceCredential(); navController.navigateTab(Route.Home) },
                    modifier = content,
                    parkedInvite = parkedInvite, onDiscardParked = vm::discardParkedInvite,
                    onCancelPairing = vm::cancelPairing, onRegister = vm::registerDevice,
                )
            }
            composable<Route.Player> {
                val playing = nowPlaying
                when (Decisions.playerContent(playing, audioState.item)) {
                    Decisions.PlayerContent.AUDIO -> NowPlayingScreen(
                        state = audioState, onBack = { navController.popBackStack() },
                        onTogglePlay = audio::togglePlayPause, onNext = audio::next, onPrevious = audio::previous,
                        onSeek = audio::seekTo, onSkipTo = audio::skipTo,
                        onStop = { audio.stop(); navController.popBackStack() },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Nothing to show: the item was stopped (or never existed). Leave.
                    Decisions.PlayerContent.NONE -> LaunchedEffect(Unit) { navController.popBackStack() }
                    Decisions.PlayerContent.VIDEO -> PlayerScreen(
                        target = playing!!.target, title = playing.title,
                        onBack = { vm.playback.stop(); navController.popBackStack() },
                        banner = playing.banner, onIssue = vm.playback::onIssue,
                        client = httpClient, modifier = Modifier.fillMaxSize(),
                        startSeconds = playing.startSeconds,
                        onProgress = { p ->
                            when (p.event) {
                                PlaybackProgress.Event.TICK -> vm.playback.reportProgress(p.seconds)
                                PlaybackProgress.Event.PAUSED -> vm.playback.reportPause(p.seconds)
                                PlaybackProgress.Event.RESUMED -> vm.playback.reportResume(p.seconds)
                                PlaybackProgress.Event.ENDED -> vm.playback.reportEnded(p.seconds, completed = true)
                                PlaybackProgress.Event.LEFT -> vm.playback.reportEnded(p.seconds, completed = false)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Switch bottom-bar tabs the Material way: one back stack per tab, restored on return. */
private fun NavHostController.navigateTab(route: Route) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
