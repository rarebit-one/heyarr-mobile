package one.rarebit.heyarr.mobile.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.net.Timestamps

/** UI state for one followed source's detail. */
sealed interface SourceDetailUiState {
    data object Loading : SourceDetailUiState

    /**
     * [source] is the subscription (`GET /followed-sources/{id}`, #430). [items] are
     * what the source's feed has yielded and what heyarr did about it
     * (`GET /followed-sources/{id}/items`) — the archive as heyarr tracks it, `item_key`
     * order. [error] is the last unfollow refusal/failure, [gone] true once the unfollow
     * took.
     */
    data class Loaded(
        val source: FollowedSource,
        val items: List<FollowedItem> = emptyList(),
        val itemsError: String? = null,
        val error: String? = null,
        val busy: Boolean = false,
        val gone: Boolean = false,
    ) : SourceDetailUiState

    data class Error(val message: String) : SourceDetailUiState
}

/**
 * One followed source: its feed identity and type, how it was set up, when it was
 * last / will next be polled, the items it has archived, and **Unfollow** with the
 * `keep_archive` choice the API takes (`DELETE /followed-sources/{id}?keep_archive=`).
 * Phase 1 refuses `keep_archive=false` with a `400` — the checkbox is offered and the
 * server's `detail` is surfaced, not pre-filtered. Read-only ⇒ Unfollow is disabled
 * under the honest banner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowedSourceDetailScreen(
    state: SourceDetailUiState,
    refreshing: Boolean,
    authority: SessionAuthority?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onUnfollow: (FollowedSource, keepArchive: Boolean) -> Unit,
    onAuthorityRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canWrite = authority?.canWrite == true
    var keepArchive by rememberSaveable { mutableStateOf(true) }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item { TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ Following") } }
            when (state) {
                is SourceDetailUiState.Loading -> item { Text("Loading…") }
                is SourceDetailUiState.Error -> item { Text(state.message, color = MaterialTheme.colorScheme.error) }
                is SourceDetailUiState.Loaded -> {
                    val s = state.source
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Text(s.title, style = MaterialTheme.typography.headlineSmall)
                            Text(listOfNotNull(s.type, s.health).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                            s.feedRef?.let { Text("feed $it", style = MaterialTheme.typography.bodySmall) }
                            s.workId?.let { Text("work $it", style = MaterialTheme.typography.labelSmall) }
                            Text("source ${s.id}", style = MaterialTheme.typography.labelSmall)
                            if (state.gone) {
                                Text("Unfollowed. Polling has stopped.", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                    if (authority?.isReadOnly == true) {
                        item { ReadOnlyAuthorityBanner(deviceKey = authority.deviceKey, isDevice = authority.isDevice, onRecheck = onAuthorityRecheck) }
                    }
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Text("Polling", style = MaterialTheme.typography.titleMedium)
                            Text("Last poll: ${Timestamps.short(s.lastPolledAt) ?: "never"}", style = MaterialTheme.typography.bodySmall)
                            Text("Next poll: ${Timestamps.short(s.nextPollAt) ?: "not scheduled"}", style = MaterialTheme.typography.bodySmall)
                            Text("Since: ${Timestamps.short(s.createdAt) ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                listOfNotNull(
                                    s.qualityProfileId?.let { "profile $it" },
                                    s.backfill?.let { "backfill $it" },
                                    s.monitor?.let { if (it) "monitored" else "not monitored" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            s.reason?.takeIf { it.isNotBlank() }?.let { Text("“$it”", style = MaterialTheme.typography.labelSmall) }
                            Text("${s.itemsArchived ?: 0} of ${s.itemsKnown ?: 0} known items archived", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Text("Unfollow", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = keepArchive, onCheckedChange = { keepArchive = it }, enabled = canWrite && !state.busy && !state.gone)
                                Text("Keep what's archived (stop polling only)", style = MaterialTheme.typography.bodySmall)
                            }
                            if (!keepArchive) {
                                Text("Removing the archive is a Phase-1 gap server-side; the node will refuse and say so.", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                OutlinedButton(onClick = { onUnfollow(s, keepArchive) }, enabled = canWrite && !state.busy && !state.gone) {
                                    Text(if (keepArchive) "Unfollow, keep archive" else "Unfollow, remove archive")
                                }
                            }
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
                        }
                    }
                    item {
                        val archivedCount = state.items.count { it.archived }
                        Text(
                            "Archive  $archivedCount/${state.items.size}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        )
                        state.itemsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (state.items.isEmpty() && state.itemsError == null) {
                            Text("Nothing archived or known yet — the first poll may not have run.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(state.items, key = { it.id }) { item ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (item.archived) "✓ " else "· ", style = MaterialTheme.typography.bodyMedium)
                                Text(item.title, style = MaterialTheme.typography.bodyMedium)
                            }
                            val sub = listOfNotNull(
                                item.subtitle.takeIf { it.isNotBlank() },
                                Timestamps.short(item.publishedAt),
                            ).joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall)
                            item.want?.summary?.takeIf { it.isNotBlank() }?.let {
                                Text("want: $it", style = MaterialTheme.typography.labelSmall)
                            } ?: Text("not wanted (back-catalogue the source only knows about)", style = MaterialTheme.typography.labelSmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
