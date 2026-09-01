package one.rarebit.heyarr.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/** The post-login tabs. */
private enum class Tab(val label: String, val glyph: String) {
    Library("Library", "▤"),
    Search("Search", "⌕"),
    Following("Following", "★"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: AppViewModel = viewModel()
                val loginState by vm.loginState.collectAsStateWithLifecycle()
                if (loginState is LoginUiState.Approved) {
                    SignedInScaffold(vm)
                } else {
                    Scaffold { padding ->
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

@UnstableApi
@Composable
private fun SignedInScaffold(vm: AppViewModel) {
    val libraryState by vm.libraryState.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val playbackNotice by vm.playbackNotice.collectAsStateWithLifecycle()

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
    // as the library browse — built once the credential exists.
    val credential = vm.credentialOrNull()
    val searchVm: SearchViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SearchViewModel(vm.config, credential!!) }
        },
    )
    val searchState by searchVm.searchState.collectAsStateWithLifecycle()
    val acquireStates by searchVm.acquireStates.collectAsStateWithLifecycle()
    val followingState by searchVm.followingState.collectAsStateWithLifecycle()
    val unfollowErrors by searchVm.unfollowErrors.collectAsStateWithLifecycle()
    val authority by searchVm.authority.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.Library) }

    Scaffold(
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
                authority = authority,
                onLoad = searchVm::loadFollowing,
                onAuthorityRecheck = searchVm::loadAuthority,
                onUnfollow = searchVm::onUnfollow,
                modifier = content,
            )
        }
    }
}
