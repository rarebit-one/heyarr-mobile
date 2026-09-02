package one.rarebit.heyarr.mobile.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.net.Timestamps

/**
 * The **Following** list — every source the user is subscribed to (an ongoing
 * `follow_source`), with its archive counters, most recently polled first. Wired to
 * the live `GET /api/v1/followed-sources` list and `DELETE /api/v1/followed-sources/{id}`
 * unfollow via [FollowingClient]. Pull down to reload; tap a row for its detail
 * ([FollowedSourceDetailScreen]). A per-row Unfollow keeps the archive (Phase-1
 * default); a refusal or failure shows inline beneath that row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowingScreen(
    state: FollowingUiState,
    unfollowErrors: Map<String, String>,
    onLoad: () -> Unit,
    onUnfollow: (FollowedSource) -> Unit,
    modifier: Modifier = Modifier,
    authority: SessionAuthority? = null,
    onAuthorityRecheck: () -> Unit = {},
    onOpen: (FollowedSource) -> Unit = {},
) {
    LaunchedEffect(Unit) {
        onLoad()
        onAuthorityRecheck()
    }
    val refreshing = state is FollowingUiState.Loading
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onLoad, modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text("Following", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                Text(
                    "Sources you're subscribed to — heyarr keeps archiving new items from each. Recent first · pull to refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
            }
            if (authority?.isReadOnly == true) {
                item {
                    ReadOnlyAuthorityBanner(
                        deviceKey = authority.deviceKey,
                        isDevice = authority.isDevice,
                        onRecheck = onAuthorityRecheck,
                    )
                }
            }
            when (state) {
                is FollowingUiState.Idle, is FollowingUiState.Loading -> item { Text("Loading…") }
                is FollowingUiState.Error -> item { Text(state.message, color = MaterialTheme.colorScheme.error) }
                is FollowingUiState.Loaded -> {
                    if (state.sources.isEmpty()) {
                        item { Text("Not following anything yet.") }
                    }
                    items(state.sources, key = { it.id }) { source ->
                        FollowedRow(
                            source = source,
                            error = unfollowErrors[source.id],
                            canWrite = authority?.canWrite == true,
                            onOpen = { onOpen(source) },
                            onUnfollow = { onUnfollow(source) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Shown when this is a read-only QR/web-login session (ADR-0061): it can browse and
 * list follows, but Follow / Unfollow will `403` until an operator authorises this
 * device to manage the library. This is a read-only client, so it cannot grant itself
 * write — the grant is an admin action elsewhere — so the banner surfaces the exact
 * `device_key` to authorise and a Re-check that re-reads `GET /session`.
 */
@Composable
fun ReadOnlyAuthorityBanner(deviceKey: String, isDevice: Boolean, onRecheck: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Text(
            "This device is read-only",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            if (isDevice) {
                "This phone is enrolled with its own device certificate, but the node grants a " +
                    "device read scope until an admin authorizes its key " +
                    "(POST /api/v1/session/management-grants {device_key}). Then Re-check."
            } else {
                "You can browse and see what's followed, but managing the library — following, " +
                    "unfollowing, cancelling wants, removing files — needs an operator to authorize " +
                    "this device. Then Re-check."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (deviceKey.isNotBlank()) {
            Text(
                "Device to authorize: $deviceKey",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        OutlinedButton(onClick = onRecheck, modifier = Modifier.padding(top = 8.dp)) {
            Text("Re-check access")
        }
        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun FollowedRow(
    source: FollowedSource,
    error: String?,
    canWrite: Boolean,
    onOpen: () -> Unit,
    onUnfollow: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Text(source.title, style = MaterialTheme.typography.bodyLarge)
            val meta = buildList {
                source.type?.let { add(it) }
                if (source.itemsArchived != null || source.itemsKnown != null) {
                    add("${source.itemsArchived ?: 0}/${source.itemsKnown ?: 0} archived")
                }
                source.health?.let { add(it) }
                Timestamps.short(source.lastPolledAt)?.let { add("polled $it") }
            }.joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall)
            }
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        OutlinedButton(onClick = onUnfollow, enabled = canWrite) { Text("Unfollow") }
    }
}
