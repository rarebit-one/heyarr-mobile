package one.rarebit.heyarr.mobile.acquisition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.library.Want
import one.rarebit.heyarr.mobile.net.Timestamps

/** Every want on the node: what is being sought, and how far along each is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WantsScreen(state: WantsUiState, onBack: () -> Unit, onRefresh: () -> Unit, onOpen: (Want) -> Unit, modifier: Modifier = Modifier) {
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            item {
                TextButton(onClick = onBack) { Text("‹ Library") }
                Text("Wants", style = MaterialTheme.typography.headlineSmall)
                Text("What the node is looking for · pull to refresh", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.loading && state.error == null && state.wants.isEmpty()) Text("Nothing wanted. Search → Get once or Follow.")
            }
            items(state.wants, key = { it.id }) { want ->
                Column(modifier = Modifier.fillMaxWidth().clickable { onOpen(want) }.padding(vertical = 10.dp)) {
                    Text(state.titleOf(want), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(want.status, style = MaterialTheme.typography.bodySmall)
                    val meta = listOfNotNull(
                        if (want.monitor) "monitored" else "paused",
                        want.scope?.takeIf { it != "work" },
                        Timestamps.short(want.updatedAt ?: want.createdAt),
                    ).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
        }
    }
}

/** One want: its state, its actions, and the releases the indexers offered with the profile's verdicts. */
@Composable
fun WantDetailScreen(
    state: WantDetailUiState,
    canWrite: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (Candidate) -> Unit,
    onSearchAgain: () -> Unit,
    onRetry: () -> Unit,
    onSetMonitor: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val want = state.want
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            TextButton(onClick = onBack) { Text("‹ Wants") }
            Text(state.title ?: want?.workId ?: "Want", style = MaterialTheme.typography.headlineSmall)
            want?.workId?.let { TextButton(onClick = { onOpenWork(it) }) { Text("Open work") } }
            when {
                state.loading && want == null -> Text("Loading…")
                state.gone -> Text("This want no longer exists.", color = MaterialTheme.colorScheme.error)
                want != null -> {
                    Text(want.status, style = MaterialTheme.typography.bodyMedium)
                    want.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    want.reason?.takeIf { it.isNotBlank() }?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        Button(onClick = onSearchAgain, enabled = canWrite && !state.busy) { Text("Search again") }
                        OutlinedButton(onClick = onRetry, enabled = canWrite && !state.busy) { Text("Retry") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        OutlinedButton(onClick = { onSetMonitor(!want.monitor) }, enabled = canWrite && !state.busy) { Text(if (want.monitor) "Pause" else "Resume") }
                        OutlinedButton(onClick = onCancel, enabled = canWrite && !state.busy) { Text("Cancel want") }
                    }
                    if (!canWrite) Text("Read-only: an authorised device can act on this.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            state.notice?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Releases", style = MaterialTheme.typography.titleMedium)
                Text("${state.candidates.candidates.size}", style = MaterialTheme.typography.labelMedium)
            }
            if (!state.loading && state.candidates.candidates.isEmpty()) {
                Text("No releases yet — Search again asks the indexers (it takes a while).", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(state.candidates.candidates, key = { it.id }) { c ->
            CandidateRow(c = c, chosen = c.selected || c.id == state.candidates.selectedId, canWrite = canWrite && !state.busy, onSelect = { onSelect(c) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun CandidateRow(c: Candidate, chosen: Boolean, canWrite: Boolean, onSelect: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text((if (chosen) "✓ " else "") + c.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            listOfNotNull(c.provider, if (c.accepted) "accepted · score ${c.score}" else "rejected", if (c.terminal) "terminal" else null).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (c.accepted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        (if (c.accepted) c.reasons else c.rejectedBy.ifEmpty { c.reasons }).take(4).forEach {
            Text("· " + it.line, style = MaterialTheme.typography.labelSmall)
        }
        if (!chosen) {
            OutlinedButton(onClick = onSelect, enabled = canWrite, modifier = Modifier.padding(top = 6.dp)) {
                Text(if (c.accepted) "Choose this" else "Choose anyway")
            }
        }
    }
}
