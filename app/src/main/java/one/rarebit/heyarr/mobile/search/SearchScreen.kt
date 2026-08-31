package one.rarebit.heyarr.mobile.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The **Search + Subscribe/One-off** screen. A single, source-agnostic content-search
 * box (the user types a show / movie / podcast / channel — never picks an indexer) →
 * a results list. Each result offers two clearly distinct actions:
 *
 * - **Get once** — a one-off acquisition (`POST /desired`, `monitor:false`).
 * - **Follow** — an ongoing subscription that keeps archiving new items (`follow_source`).
 *
 * The distinction is spelled out in the header caption and reinforced per row (a
 * followable result — series/podcast/channel — leads with **Follow**; a one-off leads
 * with **Get once**), so the ongoing-vs-once choice is never ambiguous.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    acquireStates: Map<String, AcquireState>,
    onSearch: (String) -> Unit,
    onGetOnce: (SearchResult) -> Unit,
    onFollow: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Search", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Find anything, then Get once (a single grab) or Follow (keep archiving new items).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("What do you want?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSearch(query) },
            enabled = query.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Search") }

        when (state) {
            is SearchUiState.Idle ->
                Text("Type above to search.", modifier = Modifier.padding(top = 16.dp))
            is SearchUiState.Searching ->
                Text("Searching “${state.query}”…", modifier = Modifier.padding(top = 16.dp))
            is SearchUiState.Empty ->
                Text("No results for “${state.query}”.", modifier = Modifier.padding(top = 16.dp))
            is SearchUiState.Error ->
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp),
                )
            is SearchUiState.Results ->
                LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                    items(state.results) { result ->
                        ResultRow(
                            result = result,
                            acquireState = acquireStates[result.workId] ?: AcquireState.None,
                            onGetOnce = { onGetOnce(result) },
                            onFollow = { onFollow(result) },
                        )
                        HorizontalDivider()
                    }
                }
        }
    }
}

@Composable
private fun ResultRow(
    result: SearchResult,
    acquireState: AcquireState,
    onGetOnce: () -> Unit,
    onFollow: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(result.title, style = MaterialTheme.typography.bodyLarge)
        val subtitle = listOfNotNull(result.type, result.year?.toString()).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }

        when (acquireState) {
            is AcquireState.Wanted ->
                Text("✓ Getting once", style = MaterialTheme.typography.labelLarge)
            is AcquireState.Following ->
                Text("✓ Following", style = MaterialTheme.typography.labelLarge)
            is AcquireState.InFlight ->
                Text("Working…", style = MaterialTheme.typography.labelLarge)
            is AcquireState.Failed ->
                Text(
                    acquireState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            AcquireState.None -> ActionButtons(result.followable, onGetOnce, onFollow)
        }
    }
}

@Composable
private fun ActionButtons(followableFirst: Boolean, onGetOnce: () -> Unit, onFollow: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A followable result (series/podcast/channel) leads with the ongoing action;
        // a one-off (a movie) leads with Get once. Both are always available.
        if (followableFirst) {
            Button(onClick = onFollow) { Text("Follow") }
            OutlinedButton(onClick = onGetOnce) { Text("Get once") }
        } else {
            Button(onClick = onGetOnce) { Text("Get once") }
            OutlinedButton(onClick = onFollow) { Text("Follow") }
        }
    }
}
