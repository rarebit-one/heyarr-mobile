package one.rarebit.heyarr.mobile.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.catalog.CatalogClient
import one.rarebit.heyarr.mobile.home.PosterCard
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.nav.Route

/**
 * One media hub: a poster grid over one content type at a time (chips), newest or
 * A–Z (toggle), paged from the node as you scroll. Tap plays when the card carries a
 * file, else opens the work; long-press always opens it.
 */
@Composable
fun HubScreen(
    state: HubUiState,
    baseUrl: String,
    onSelectContentType: (String) -> Unit,
    onToggleSort: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenWork: (Work) -> Unit,
    onPlay: (Work) -> Unit,
    modifier: Modifier = Modifier,
    onArtists: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(Route.hubLabel(state.hub), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onToggleSort) {
                Text(if (state.sort == CatalogClient.Sort.RECENT) "Newest ▾" else "A–Z ▾")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            state.chips.forEach { ct ->
                FilterChip(selected = ct == state.contentType, onClick = { onSelectContentType(ct) }, label = { Text(chipLabel(ct)) })
            }
            if (onArtists != null) FilterChip(selected = false, onClick = onArtists, label = { Text("Artists ›") })
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (!state.loading && state.error == null && state.items.isEmpty()) {
            Text("Nothing here yet.", modifier = Modifier.padding(16.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.id }) { work ->
                PosterCard(work = work, baseUrl = baseUrl, onOpen = { onOpenWork(work) }, onPlay = { onPlay(work) })
            }
            if (state.nextCursor != null || state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // Reaching the tail asks for the next page; the node answers with a cursor
                    // or without one, and the sentinel leaves when there is nothing more.
                    LaunchedEffect(state.nextCursor, state.loading) { if (state.canLoadMore) onLoadMore() }
                    Text(if (state.loading) "Loading…" else "More…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

internal fun chipLabel(contentType: String): String = when (contentType) {
    "movie" -> "Movies"
    "series" -> "Series"
    "music" -> "Albums"
    "book" -> "Books"
    else -> contentType.replaceFirstChar { it.uppercase() }
}
