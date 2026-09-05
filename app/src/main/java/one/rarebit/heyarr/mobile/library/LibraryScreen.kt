package one.rarebit.heyarr.mobile.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.net.Timestamps

/** UI state for the library browse screen. */
sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Loaded(val works: List<Work>) : LibraryUiState
    data class Error(val message: String) : LibraryUiState
}

/**
 * The library browse list — heyarr's native `/api/v1/works` rendered as rows, most
 * recently touched first (no settings; the order is the client's, see
 * [LibraryClient.listWorks]). Pull down to reload. Tapping a row opens its
 * [WorkDetailScreen] via [onOpen], where Play lives per file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (Work) -> Unit,
    modifier: Modifier = Modifier,
    /** A link to the followed-sources list, when this list is the management surface. */
    onFollowing: (() -> Unit)? = null,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text("Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                Text("Recent first · pull to refresh", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
                if (onFollowing != null) {
                    Text(
                        "★ Followed sources ›",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onFollowing).padding(vertical = 8.dp),
                    )
                    HorizontalDivider()
                }
            }
            when (state) {
                is LibraryUiState.Loading -> item { Text("Loading…") }
                is LibraryUiState.Error -> item { Text(state.message, color = MaterialTheme.colorScheme.error) }
                is LibraryUiState.Loaded -> {
                    if (state.works.isEmpty()) {
                        item { Text("No works yet.") }
                    }
                    items(state.works, key = { it.id }) { work ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(work) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(work.title, style = MaterialTheme.typography.bodyLarge)
                            val subtitle = listOfNotNull(
                                work.year?.toString(),
                                work.kind,
                                Timestamps.short(work.recency),
                            ).joinToString(" · ")
                            if (subtitle.isNotEmpty()) {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
