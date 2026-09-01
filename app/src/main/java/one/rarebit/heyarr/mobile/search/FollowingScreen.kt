package one.rarebit.heyarr.mobile.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The **Following** list — every source the user is subscribed to (an ongoing
 * `follow_source`), with its archive counters. Wired to the live
 * `GET /api/v1/followed-sources` list and `DELETE /api/v1/followed-sources/{id}`
 * unfollow via [FollowingClient]. A per-row Unfollow keeps the archive (Phase-1
 * default); a refusal or failure shows inline beneath that row.
 */
@Composable
fun FollowingScreen(
    state: FollowingUiState,
    unfollowErrors: Map<String, String>,
    onLoad: () -> Unit,
    onUnfollow: (FollowedSource) -> Unit,
    modifier: Modifier = Modifier,
    authority: SessionAuthority? = null,
    onAuthorityRecheck: () -> Unit = {},
) {
    LaunchedEffect(Unit) {
        onLoad()
        onAuthorityRecheck()
    }
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Following", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sources you're subscribed to — heyarr keeps archiving new items from each.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (authority?.isReadOnlySession == true) {
            ReadOnlyAuthorityBanner(deviceKey = authority.deviceKey, onRecheck = onAuthorityRecheck)
        }
        when (state) {
            is FollowingUiState.Idle, is FollowingUiState.Loading ->
                Text("Loading…", modifier = Modifier.padding(top = 12.dp))
            is FollowingUiState.Error ->
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            is FollowingUiState.Loaded ->
                if (state.sources.isEmpty()) {
                    Text("Not following anything yet.", modifier = Modifier.padding(top = 12.dp))
                } else {
                    LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                        items(state.sources) { source ->
                            FollowedRow(
                                source = source,
                                error = unfollowErrors[source.id],
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
private fun ReadOnlyAuthorityBanner(deviceKey: String, onRecheck: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Text(
            "This device is read-only",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "You can browse and see what's followed, but following and unfollowing need " +
                "an operator to authorize this device to manage the library. Then Re-check.",
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
    onUnfollow: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
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
        OutlinedButton(onClick = onUnfollow) { Text("Unfollow") }
    }
}
