package one.rarebit.heyarr.mobile.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LinearProgressIndicator
import one.rarebit.heyarr.mobile.catalog.Artwork
import one.rarebit.heyarr.mobile.catalog.ContinueEntry
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.net.Timestamps
import one.rarebit.heyarr.mobile.ui.Poster

/**
 * The shelf you land on: one card per media hub, then a "recently added" poster row
 * per hub. Tapping a poster plays it when the row carries a playable file, else opens
 * the work; a long-press always opens the work. The continue row arrives with the
 * consumption rail (heyarr-core ADR-0075, A2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    baseUrl: String,
    onRefresh: () -> Unit,
    onOpenHub: (String) -> Unit,
    onOpenWork: (Work) -> Unit,
    onPlay: (Work) -> Unit,
    modifier: Modifier = Modifier,
    onContinue: (ContinueEntry) -> Unit = {},
    onOpenContinue: (ContinueEntry) -> Unit = {},
    starredWorks: List<Work> = emptyList(),
    recentWorks: List<Work> = emptyList(),
    starredIds: Set<String> = emptySet(),
    onOpenPlaylists: (() -> Unit)? = null,
    onToggleStar: ((Work) -> Unit)? = null,
    onAddToPlaylist: ((Work) -> Unit)? = null,
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text("Home", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Route.hubs.forEach { hub ->
                        Card(modifier = Modifier.weight(1f).clickable { onOpenHub(hub) }) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(hubGlyph(hub), style = MaterialTheme.typography.headlineMedium)
                                Text(Route.hubLabel(hub), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                if (onOpenPlaylists != null) {
                    Text(
                        "Your playlists →",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onOpenPlaylists() }.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
            }
            if (starredWorks.isNotEmpty()) {
                item(key = "row:starred") {
                    Text("Starred", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
                    PosterRow(
                        works = starredWorks, baseUrl = baseUrl, onOpen = onOpenWork, onPlay = onPlay,
                        starredIds = starredIds, onToggleStar = onToggleStar, onAddToPlaylist = onAddToPlaylist,
                    )
                }
            }
            if (recentWorks.isNotEmpty()) {
                item(key = "row:recent") {
                    Text("Recently played", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
                    PosterRow(
                        works = recentWorks, baseUrl = baseUrl, onOpen = onOpenWork, onPlay = onPlay,
                        starredIds = starredIds, onToggleStar = onToggleStar, onAddToPlaylist = onAddToPlaylist,
                    )
                }
            }
            val rail = state.continueRow
            if (rail != null && !(rail is RowState.Loaded && rail.items.isEmpty())) {
                item(key = "row:continue") {
                    Text("Continue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
                    when (rail) {
                        is RowState.Loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                        is RowState.Failed -> Text(rail.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                        is RowState.Loaded -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                            items(rail.items, key = { it.sessionId }) { entry ->
                                ContinueCard(entry = entry, baseUrl = baseUrl, onPlay = { onContinue(entry) }, onOpen = { onOpenContinue(entry) }, modifier = Modifier.width(150.dp))
                            }
                        }
                    }
                }
            }
            Route.hubs.forEach { hub ->
                item(key = "row:$hub") {
                    Text(
                        "Recently added · ${Route.hubLabel(hub)}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp).clickable { onOpenHub(hub) },
                    )
                    when (val row = state.row(hub)) {
                        is RowState.Loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                        is RowState.Failed -> Text(row.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                        is RowState.Loaded -> if (row.items.isEmpty()) {
                            Text("Nothing here yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                        } else {
                            PosterRow(
                                works = row.items, baseUrl = baseUrl, onOpen = onOpenWork, onPlay = onPlay,
                                starredIds = starredIds, onToggleStar = onToggleStar, onAddToPlaylist = onAddToPlaylist,
                            )
                        }
                    }
                }
            }
            item { Column(modifier = Modifier.padding(bottom = 24.dp)) {} }
        }
    }
}

@Composable
private fun PosterRow(
    works: List<Work>,
    baseUrl: String,
    onOpen: (Work) -> Unit,
    onPlay: (Work) -> Unit,
    starredIds: Set<String> = emptySet(),
    onToggleStar: ((Work) -> Unit)? = null,
    onAddToPlaylist: ((Work) -> Unit)? = null,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
        items(works, key = { it.id }) { work ->
            PosterCard(
                work = work, baseUrl = baseUrl, onOpen = { onOpen(work) }, onPlay = { onPlay(work) },
                modifier = Modifier.width(120.dp),
                starred = work.id in starredIds,
                onToggleStar = onToggleStar?.let { { it(work) } },
                onAddToPlaylist = onAddToPlaylist?.let { { it(work) } },
            )
        }
    }
}

/**
 * A poster with its title beneath. Tap plays when the work carries a playable file
 * (the `primary_asset` embed), else opens it; long-press always opens it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    work: Work,
    baseUrl: String,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    starred: Boolean = false,
    onToggleStar: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
) {
    val hasActions = onToggleStar != null || onAddToPlaylist != null
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.combinedClickable(
            onClick = if (work.isPlayable) onPlay else onOpen,
            onLongClick = { if (hasActions) menuOpen = true else onOpen() },
        ),
    ) {
        Poster(url = Artwork.posterUrl(baseUrl, work), kind = work.kind, contentDescription = work.title, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(work.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (starred) Text("★", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
        }
        val sub = listOfNotNull(work.year?.toString(), work.artist ?: work.author).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hasActions) {
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("Open") }, onClick = { menuOpen = false; onOpen() })
                onToggleStar?.let { star ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (starred) "Unstar" else "★ Star") },
                        onClick = { menuOpen = false; star() },
                    )
                }
                onAddToPlaylist?.let { add ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { menuOpen = false; add() })
                }
            }
        }
    }
}

/** A continue card: the poster, a progress bar, the title and where it was left. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ContinueCard(entry: ContinueEntry, baseUrl: String, onPlay: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.combinedClickable(onClick = if (entry.isPlayable) onPlay else onOpen, onLongClick = onOpen)) {
        Poster(
            url = Artwork.posterUrl(baseUrl, entry.workId, entry.artworkPath), kind = entry.contentType,
            contentDescription = entry.workTitle, modifier = Modifier.fillMaxWidth(),
        )
        entry.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
        Text(entry.workTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        val sub = listOfNotNull(entry.subtitle, entry.positionSeconds?.let { "at " + clock(it) }).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `1:23:45` / `12:34` from seconds. */
internal fun clock(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** A one-line work row: title, `year · kind · when`, and Play when the row can stream. */
@Composable
fun WorkRow(work: Work, onOpen: () -> Unit, onPlay: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(work.title, style = MaterialTheme.typography.bodyLarge)
            val subtitle = listOfNotNull(work.year?.toString(), work.kind, Timestamps.short(work.recency)).joinToString(" · ")
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (onPlay != null) {
            Text("▶", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable(onClick = onPlay).padding(start = 12.dp))
        }
    }
}

internal fun hubGlyph(hub: String): String = when (hub) {
    Route.HUB_MUSIC -> "♫"
    Route.HUB_BOOKS -> "📖"
    else -> "🎬"
}
