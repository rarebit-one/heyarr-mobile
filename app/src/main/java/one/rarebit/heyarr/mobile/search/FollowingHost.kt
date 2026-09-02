package one.rarebit.heyarr.mobile.search

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Following tab's two screens — the list and one source's detail — with the
 * navigation kept here so `MainActivity` stays a thin tab switch. [openSourceId] lets
 * another tab (a work's "Open source") deep-link into a detail; [onClearOpen] resets
 * it once shown.
 */
@Composable
fun FollowingHost(
    vm: SearchViewModel,
    openSourceId: String?,
    onClearOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val followingState by vm.followingState.collectAsStateWithLifecycle()
    val unfollowErrors by vm.unfollowErrors.collectAsStateWithLifecycle()
    val authority by vm.authority.collectAsStateWithLifecycle()
    val detail by vm.sourceDetail.collectAsStateWithLifecycle()
    val detailRefreshing by vm.sourceDetailRefreshing.collectAsStateWithLifecycle()

    LaunchedEffect(openSourceId) {
        if (openSourceId != null) {
            vm.openSource(openSourceId)
            onClearOpen()
        }
    }

    val current = detail
    if (current == null) {
        FollowingScreen(
            state = followingState,
            unfollowErrors = unfollowErrors,
            authority = authority,
            onLoad = vm::loadFollowing,
            onAuthorityRecheck = vm::loadAuthority,
            onUnfollow = vm::onUnfollow,
            onOpen = { vm.openSource(it.id) },
            modifier = modifier,
        )
        return
    }
    BackHandler { vm.closeSource() }
    FollowedSourceDetailScreen(
        state = current,
        refreshing = detailRefreshing,
        authority = authority,
        onRefresh = vm::reloadSource,
        onBack = vm::closeSource,
        onUnfollow = vm::unfollowFromDetail,
        onAuthorityRecheck = vm::loadAuthority,
        modifier = modifier,
    )
}
