package one.rarebit.heyarr.mobile.library

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.search.FollowedSource
import one.rarebit.heyarr.mobile.search.FollowingClient
import one.rarebit.heyarr.mobile.search.SessionAuthority

/**
 * The Library tab's two screens — the list and one work's detail — with the
 * list→detail→back navigation kept here so `MainActivity` stays a thin tab switch.
 * The detail's clients are built over the app's [transport] (the `Device`-credential
 * path) with the same [credential] the list uses.
 */
@Composable
fun LibraryHost(
    state: LibraryUiState,
    refreshing: Boolean,
    authority: SessionAuthority?,
    baseUrl: String,
    credential: Credential,
    transport: HttpTransport,
    onRefresh: () -> Unit,
    onPlay: (Work, WorkAsset) -> Unit,
    onOpenSource: (FollowedSource) -> Unit,
    onAuthorityRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<Work?>(null) }
    val work = selected
    if (work == null) {
        LibraryScreen(state = state, refreshing = refreshing, onRefresh = onRefresh, onOpen = { selected = it }, modifier = modifier)
        return
    }
    BackHandler { selected = null }
    val vm: WorkDetailViewModel = viewModel(
        key = "work:${work.id}:$baseUrl:${credential.javaClass.simpleName}",
        factory = viewModelFactory {
            initializer {
                WorkDetailViewModel(
                    work = work,
                    library = LibraryClient(transport, baseUrl, credential),
                    detail = WorkDetailClient(transport, baseUrl, credential),
                    following = FollowingClient(transport, baseUrl, credential),
                )
            }
        },
    )
    val detail by vm.state.collectAsStateWithLifecycle()
    val detailRefreshing by vm.refreshing.collectAsStateWithLifecycle()
    WorkDetailScreen(
        state = detail,
        refreshing = detailRefreshing,
        authority = authority,
        onRefresh = vm::load,
        onBack = { selected = null },
        onPlay = onPlay,
        onCancelWant = vm::cancelWant,
        onSetMonitor = vm::setMonitor,
        onRetry = vm::retry,
        onSearchAgain = vm::searchAgain,
        onRemoveAsset = vm::removeAsset,
        onEditWork = vm::editWork,
        onDeleteWork = vm::deleteWork,
        // The work is gone: pop back to the list and refresh it so the row disappears.
        onWorkDeleted = { selected = null; onRefresh() },
        onOpenSource = onOpenSource,
        onAuthorityRecheck = onAuthorityRecheck,
        modifier = modifier,
    )
}
