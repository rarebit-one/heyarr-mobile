package one.rarebit.heyarr.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import one.rarebit.heyarr.mobile.library.LibraryScreen
import one.rarebit.heyarr.mobile.login.LoginScreen
import one.rarebit.heyarr.mobile.login.LoginUiState
import one.rarebit.heyarr.mobile.playback.PlayerScreen
import one.rarebit.heyarr.mobile.search.FollowingScreen
import one.rarebit.heyarr.mobile.search.SearchScreen
import one.rarebit.heyarr.mobile.search.SearchViewModel
import one.rarebit.heyarr.mobile.search.SessionAuthority
import one.rarebit.heyarr.mobile.settings.PrefsSettingsStore
import one.rarebit.heyarr.mobile.settings.SettingsScreen

/** The post-login tabs. */
private enum class Tab(val label: String, val glyph: String) {
    Library("Library", "▤"),
    Search("Search", "⌕"),
    Following("Following", "★"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        setContent {
            MaterialTheme {
                val vm: AppViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { AppViewModel(settings = PrefsSettingsStore(appContext)) }
                    },
                )
                val loginState by vm.loginState.collectAsStateWithLifecycle()
                val config by vm.configState.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }

                if (showSettings) {
                    BackHandler { showSettings = false }
                    Scaffold(topBar = { HeyarrTopBar(subtitle = config.baseUrl, onSettings = null) }) { padding ->
                        SettingsScreen(
                            config = config,
                            onSave = { url, profile -> vm.updateSettings(url, profile); showSettings = false },
                            onReset = { vm.resetSettings(); showSettings = false },
                            onClose = { showSettings = false },
                            modifier = Modifier.padding(padding),
                        )
                    }
                } else if (loginState is LoginUiState.Approved) {
                    SignedInScaffold(vm, onSettings = { showSettings = true })
                } else {
                    Scaffold(topBar = { HeyarrTopBar(subtitle = config.baseUrl, onSettings = { showSettings = true }) }) { padding ->
                        LoginScreen(
                            state = loginState,
                            onSignIn = vm::signIn,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The app bar: title, a one-line subtitle (the node we point at, or the signed-in
 * identity + scope), and a gear that opens Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeyarrTopBar(subtitle: String, onSettings: (() -> Unit)?) {
    TopAppBar(
        title = {
            Column {
                Text("heyarr", style = MaterialTheme.typography.titleLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            if (onSettings != null) {
                IconButton(onClick = onSettings) { Text("⚙") }
            }
        },
    )
}

/** "Signed in as … · read-only" from the login result + `GET /api/v1/session`. */
internal fun sessionSubtitle(user: String?, authority: SessionAuthority?, baseUrl: String): String {
    val who = user?.takeIf { it.isNotBlank() }
        ?: authority?.principalId?.takeIf { it.isNotBlank() }?.let { shortPrincipal(it) }
    val scope = when {
        authority == null -> "read-only (session unverified)"
        authority.canWrite -> "can write"
        else -> "read-only"
    }
    val subject = if (who != null) "Signed in as $who" else "Signed in"
    return "$subject · $scope · $baseUrl"
}

/** `ed25519:<hex>` → `ed25519:<first 8>…` so it fits a subtitle line. */
internal fun shortPrincipal(principal: String): String {
    val sep = principal.indexOf(':')
    if (sep < 0) return principal.take(12)
    val prefix = principal.substring(0, sep + 1)
    val body = principal.substring(sep + 1)
    return if (body.length <= 8) principal else "$prefix${body.take(8)}…"
}

@UnstableApi
@Composable
private fun SignedInScaffold(vm: AppViewModel, onSettings: () -> Unit) {
    val libraryState by vm.libraryState.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val playbackNotice by vm.playbackNotice.collectAsStateWithLifecycle()
    val loginState by vm.loginState.collectAsStateWithLifecycle()
    val authority by vm.sessionAuthority.collectAsStateWithLifecycle()
    val config by vm.configState.collectAsStateWithLifecycle()

    // Surface a "cannot stream directly" notice, then clear it so it fires once.
    val context = LocalContext.current
    LaunchedEffect(playbackNotice) {
        playbackNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearPlaybackNotice()
        }
    }

    // The player is a full-screen overlay over the tabs while an item is playing.
    nowPlaying?.let { playing ->
        PlayerScreen(
            target = playing.target,
            title = playing.title,
            onBack = vm::stopPlayback,
            client = vm.httpClient,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // The search/acquire/following features run with the SAME authenticated identity
    // as the library browse — built once the credential exists, and rebuilt when the
    // config it was built against changes (the key).
    val credential = vm.credentialOrNull()
    val searchVm: SearchViewModel = viewModel(
        key = "search:${config.baseUrl}:${config.defaultQualityProfile}",
        factory = viewModelFactory {
            initializer { SearchViewModel(config, credential!!) }
        },
    )
    val searchState by searchVm.searchState.collectAsStateWithLifecycle()
    val acquireStates by searchVm.acquireStates.collectAsStateWithLifecycle()
    val followingState by searchVm.followingState.collectAsStateWithLifecycle()
    val unfollowErrors by searchVm.unfollowErrors.collectAsStateWithLifecycle()
    val searchAuthority by searchVm.authority.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.Library) }
    val user = (loginState as? LoginUiState.Approved)?.user

    Scaffold(
        topBar = {
            HeyarrTopBar(subtitle = sessionSubtitle(user, authority, config.baseUrl), onSettings = onSettings)
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Text(entry.glyph) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding)
        when (tab) {
            Tab.Library -> LibraryScreen(state = libraryState, onPlay = vm::play, modifier = content)
            Tab.Search -> SearchScreen(
                state = searchState,
                acquireStates = acquireStates,
                onSearch = searchVm::onSearch,
                onGetOnce = searchVm::onGetOnce,
                onFollow = searchVm::onFollow,
                modifier = content,
            )
            Tab.Following -> FollowingScreen(
                state = followingState,
                unfollowErrors = unfollowErrors,
                authority = searchAuthority,
                onLoad = searchVm::loadFollowing,
                onAuthorityRecheck = searchVm::loadAuthority,
                onUnfollow = searchVm::onUnfollow,
                modifier = content,
            )
        }
    }
}
