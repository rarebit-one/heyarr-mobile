package one.rarebit.heyarr.mobile.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.catalog.Artwork
import one.rarebit.heyarr.mobile.discover.DiscoverResult
import one.rarebit.heyarr.mobile.ui.Poster

/**
 * **Universal search.** One box; three answers beneath it:
 *
 * 1. **In your library** — works (with posters) and the episodes that matched by their
 *    own title (`POST /search`, ADR-0075). Tap a work to open it; ▶ plays an episode
 *    that holds a file. Get once / Follow remain per work.
 * 2. **Find more** — the metadata provider's candidates (`POST /discover`), asked
 *    automatically when the library came back empty and on demand otherwise, because
 *    it reaches out over the network where the library search does not. Each carries
 *    the id a one-tap **Follow** needs.
 * 3. A link to **Followed sources**.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    discover: DiscoverUiState,
    acquireStates: Map<String, AcquireState>,
    baseUrl: String,
    onSearch: (String) -> Unit,
    onDiscover: (String) -> Unit,
    onGetOnce: (SearchResult) -> Unit,
    onFollow: (SearchResult) -> Unit,
    onFollowDiscovered: (DiscoverResult) -> Unit,
    onOpenWork: (SearchResult) -> Unit,
    onPlayEpisode: (EpisodeResult) -> Unit,
    onOpenEpisodeWork: (EpisodeResult) -> Unit,
    onFollowing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text("Search", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Shows, films, albums, books, episodes…") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { onSearch(query) }, enabled = query.isNotBlank()) { Text("Search") }
                OutlinedButton(onClick = onFollowing) { Text("★ Followed sources") }
            }
        }

        // ── In your library ────────────────────────────────────────────────────────
        when (state) {
            is SearchUiState.Idle -> item { Text("Type above to search your library, then find more online.", modifier = Modifier.padding(top = 16.dp)) }
            is SearchUiState.Searching -> item { Text("Searching “${state.query}”…", modifier = Modifier.padding(top = 16.dp)) }
            is SearchUiState.Empty -> item { Text("Nothing in your library for “${state.query}”.", modifier = Modifier.padding(top = 16.dp)) }
            is SearchUiState.Error -> item { Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
            is SearchUiState.Results -> {
                if (state.results.isNotEmpty()) {
                    item { SectionHeader("In your library", "${state.results.size}") }
                    items(state.results, key = { "w:" + it.workId }) { result ->
                        WorkResultRow(
                            result = result, baseUrl = baseUrl,
                            acquireState = acquireStates[result.workId] ?: AcquireState.None,
                            onOpen = { onOpenWork(result) }, onGetOnce = { onGetOnce(result) }, onFollow = { onFollow(result) },
                        )
                        HorizontalDivider()
                    }
                }
                if (state.episodes.isNotEmpty()) {
                    item { SectionHeader("Episodes", "${state.episodes.size}") }
                    items(state.episodes, key = { "e:" + it.kind + ":" + it.id }) { ep ->
                        EpisodeRow(ep = ep, onPlay = { onPlayEpisode(ep) }, onOpen = { onOpenEpisodeWork(ep) })
                        HorizontalDivider()
                    }
                }
            }
        }

        // ── Find more ──────────────────────────────────────────────────────────────
        val searched = (state as? SearchUiState.Results)?.query ?: (state as? SearchUiState.Empty)?.query
        when (discover) {
            is DiscoverUiState.Idle -> if (searched != null) item {
                OutlinedButton(onClick = { onDiscover(searched) }, modifier = Modifier.padding(top = 16.dp)) { Text("Find more online") }
            }
            is DiscoverUiState.Searching -> item { Text("Looking online for “${discover.query}”…", modifier = Modifier.padding(top = 16.dp)) }
            is DiscoverUiState.Empty -> item { Text("Nothing found online for “${discover.query}”.", modifier = Modifier.padding(top = 16.dp)) }
            is DiscoverUiState.Unavailable -> item { Text(discover.why, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp)) }
            is DiscoverUiState.Error -> item { Text(discover.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
            is DiscoverUiState.Results -> {
                item { SectionHeader("Find more", "${discover.results.size}") }
                items(discover.results, key = { it.key }) { result ->
                    DiscoverRow(result = result, acquireState = acquireStates[result.key] ?: AcquireState.None, onFollow = { onFollowDiscovered(result) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        count?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun WorkResultRow(
    result: SearchResult,
    baseUrl: String,
    acquireState: AcquireState,
    onOpen: () -> Unit,
    onGetOnce: () -> Unit,
    onFollow: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Poster(url = Artwork.posterUrl(baseUrl, result.workId, result.artworkPath), kind = result.type, contentDescription = null, modifier = Modifier.width(56.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(result.type, result.year?.toString()).joinToString(" · ")
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
            AcquireLine(acquireState) { ActionButtons(result.followable, onGetOnce, onFollow) }
        }
    }
}

@Composable
private fun EpisodeRow(ep: EpisodeResult, onPlay: () -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(listOfNotNull(ep.code, ep.title).joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
            Text(
                ep.workTitle + if (ep.kind == "item") " · not yet archived" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (ep.isPlayable) {
            Text("▶", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable(onClick = onPlay).padding(start = 12.dp))
        }
    }
}

@Composable
private fun DiscoverRow(result: DiscoverResult, acquireState: AcquireState, onFollow: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(result.title, style = MaterialTheme.typography.bodyLarge)
        val subtitle = listOfNotNull(result.type, result.year?.toString(), result.tvdbId?.let { "tvdb $it" }).joinToString(" · ")
        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        result.overview?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        AcquireLine(acquireState) {
            Button(onClick = onFollow, enabled = !result.tvdbId.isNullOrBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Follow") }
        }
    }
}

@Composable
private fun AcquireLine(acquireState: AcquireState, actions: @Composable () -> Unit) {
    when (acquireState) {
        is AcquireState.Wanted -> Text("✓ Getting once", style = MaterialTheme.typography.labelLarge)
        is AcquireState.Following -> Text("✓ Following", style = MaterialTheme.typography.labelLarge)
        is AcquireState.InFlight -> Text("Working…", style = MaterialTheme.typography.labelLarge)
        is AcquireState.Failed -> Text(acquireState.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        AcquireState.None -> actions()
    }
}

@Composable
private fun ActionButtons(followableFirst: Boolean, onGetOnce: () -> Unit, onFollow: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
