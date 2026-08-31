package one.rarebit.heyarr.mobile.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The **Following** list — every source the user is subscribed to (an ongoing
 * `follow_source`), with its archive counters. SEAM: wired to the `list_followed`
 * route via [FollowingClient]; until that REST route lands it will surface the
 * server's status (e.g. an empty list, or an error if the route is not up yet).
 */
@Composable
fun FollowingScreen(
    state: FollowingUiState,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onLoad() }
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Following", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sources you're subscribed to — heyarr keeps archiving new items from each.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
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
                            Column(modifier = Modifier.padding(vertical = 10.dp)) {
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
                            }
                            HorizontalDivider()
                        }
                    }
                }
        }
    }
}
