package one.rarebit.heyarr.mobile.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.net.Timestamps
import one.rarebit.heyarr.mobile.search.FollowedSource
import one.rarebit.heyarr.mobile.search.ReadOnlyAuthorityBanner
import one.rarebit.heyarr.mobile.search.SessionAuthority

/**
 * One work: title / year / kind, its identity, its files (with quality + size and a
 * **Play** per streamable one), its wants (with the §64 status and the management
 * actions the server has — cancel, pause/resume, retry, search again), and the
 * followed source it came from (tap → its detail). Every write honours the
 * session's authority: read-only ⇒ the buttons are disabled under the same honest
 * banner the Following screen shows, and the row's notice says why if tapped anyway.
 *
 * Things the server cannot do are said, not hidden: there is no delete-a-work route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkDetailScreen(
    state: WorkDetailUiState,
    refreshing: Boolean,
    authority: SessionAuthority?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onPlay: (Work, WorkAsset) -> Unit,
    onCancelWant: (Want) -> Unit,
    onSetMonitor: (Want, Boolean) -> Unit,
    onRetry: (Want) -> Unit,
    onSearchAgain: (Want) -> Unit,
    onRemoveAsset: (WorkAsset) -> Unit,
    onOpenSource: (FollowedSource) -> Unit,
    onAuthorityRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canWrite = authority?.canWrite == true
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("‹ Library") }
            }
            when (state) {
                is WorkDetailUiState.Loading -> item { Text("Loading…", modifier = Modifier.padding(top = 12.dp)) }
                is WorkDetailUiState.Error -> item {
                    Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                }
                is WorkDetailUiState.Loaded -> {
                    item { Header(state.work) }
                    if (authority?.isReadOnly == true) {
                        item {
                            ReadOnlyAuthorityBanner(
                                deviceKey = authority.deviceKey,
                                isDevice = authority.isDevice,
                                onRecheck = onAuthorityRecheck,
                            )
                        }
                    }
                    state.partialError?.let { err ->
                        item {
                            Text("Some reads failed — $err", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }

                    item { SectionTitle("Files", "${state.assets.size}") }
                    if (state.assets.isEmpty()) {
                        item { Text("No files in the catalog for this work yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                    }
                    items(state.assets, key = { "asset:" + it.id }) { asset ->
                        AssetRow(
                            asset = asset,
                            notice = state.notices[asset.id],
                            busy = asset.id in state.busy,
                            canWrite = canWrite,
                            onPlay = { onPlay(state.work, asset) },
                            onRemove = { onRemoveAsset(asset) },
                        )
                        HorizontalDivider()
                    }

                    item { SectionTitle("Wants", "${state.wants.size}") }
                    if (state.wants.isEmpty()) {
                        item { Text("Nothing wanted for this work — use Search → Get once / Follow.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                    }
                    items(state.wants, key = { "want:" + it.id }) { want ->
                        WantRow(
                            want = want,
                            notice = state.notices[want.id],
                            busy = want.id in state.busy,
                            canWrite = canWrite,
                            onCancel = { onCancelWant(want) },
                            onSetMonitor = { onSetMonitor(want, it) },
                            onRetry = { onRetry(want) },
                            onSearchAgain = { onSearchAgain(want) },
                        )
                        HorizontalDivider()
                    }

                    item { SectionTitle("Followed source", null) }
                    item {
                        val source = state.source
                        if (source == null) {
                            Text("Not projected by a followed source.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                        } else {
                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Text(listOfNotNull(source.type, source.feedRef).joinToString(" · ").ifBlank { source.id })
                                Text(
                                    "${source.itemsArchived ?: 0}/${source.itemsKnown ?: 0} archived · ${source.health ?: "unknown"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(onClick = { onOpenSource(source) }, modifier = Modifier.padding(top = 4.dp)) { Text("Open source") }
                            }
                        }
                    }

                    item {
                        Text(
                            "heyarr has no route to delete a work itself — remove its files and cancel its wants instead " +
                                "(a removed file's bytes are reclaimed later by garbage collection).",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(work: Work) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(work.title, style = MaterialTheme.typography.headlineSmall)
        val meta = listOfNotNull(work.year?.toString(), work.kind).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium)
        Text("id ${work.id}", style = MaterialTheme.typography.labelSmall)
        work.workKey?.let { Text("key $it", style = MaterialTheme.typography.labelSmall) }
        Timestamps.short(work.updatedAt ?: work.createdAt)?.let {
            Text("updated $it", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "External ids (tmdb/imdb/tvdb) are not readable over REST yet (ADR-0050 is MCP-only).",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SectionTitle(title: String, count: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (count != null) Text("  $count", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AssetRow(
    asset: WorkAsset,
    notice: String?,
    busy: Boolean,
    canWrite: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(asset.filename ?: asset.sourcePath?.substringAfterLast('/') ?: asset.id, style = MaterialTheme.typography.bodyLarge)
        val quality = asset.quality
        if (quality.isNotEmpty()) Text(quality, style = MaterialTheme.typography.bodySmall)
        if (asset.isMissing) {
            Text("missing since ${Timestamps.short(asset.missingSince) ?: asset.missingSince}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = onPlay, enabled = asset.isPlayable && !busy) { Text("Play") }
            OutlinedButton(onClick = onRemove, enabled = canWrite && !busy) { Text("Remove file") }
        }
        if (!asset.isPlayable && !asset.isMissing) {
            Text("No blob to stream (a linked asset has none).", style = MaterialTheme.typography.labelSmall)
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun WantRow(
    want: Want,
    notice: String?,
    busy: Boolean,
    canWrite: Boolean,
    onCancel: () -> Unit,
    onSetMonitor: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onSearchAgain: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(want.status, style = MaterialTheme.typography.bodyLarge)
        val meta = listOfNotNull(
            want.scope?.let { "$it scope" },
            want.qualityProfileId?.let { "profile $it" },
            want.content?.let { "content $it" },
            want.placement?.let { "placement $it" },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
        want.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        want.reason?.takeIf { it.isNotBlank() }?.let { Text("“$it”", style = MaterialTheme.typography.labelSmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = onRetry, enabled = canWrite && !busy) { Text("Retry") }
            OutlinedButton(onClick = onSearchAgain, enabled = canWrite && !busy) { Text("Search again") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { onSetMonitor(!want.monitor) }, enabled = canWrite && !busy) {
                Text(if (want.monitor) "Pause" else "Resume")
            }
            OutlinedButton(onClick = onCancel, enabled = canWrite && !busy) { Text("Cancel want") }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}
