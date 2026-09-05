package one.rarebit.heyarr.mobile.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.catalog.Artwork
import one.rarebit.heyarr.mobile.home.PosterCard
import one.rarebit.heyarr.mobile.home.RowState
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.ui.Poster

@Composable
fun ArtistsScreen(state: RowState<Artist>, baseUrl: String, onBack: () -> Unit, onOpen: (Artist) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) { Text("‹ Music") }
        Text("Artists", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        when (state) {
            is RowState.Loading -> Text("Loading…", modifier = Modifier.padding(16.dp))
            is RowState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            is RowState.Loaded -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
                if (state.items.isEmpty()) item { Text("No artists yet.") }
                items(state.items, key = { it.name }) { artist ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(artist) }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val url = artist.artworkPath?.let { Artwork.posterUrl(baseUrl, artist.artworkWorkId ?: "", it) }
                        Poster(url = url, kind = "music", contentDescription = null, modifier = Modifier.width(56.dp))
                        Column {
                            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${artist.workCount} album" + if (artist.workCount == 1) "" else "s", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(artist: String, state: RowState<Work>, baseUrl: String, onBack: () -> Unit, onOpenAlbum: (Work) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) { Text("‹ Artists") }
        Text(artist, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        when (state) {
            is RowState.Loading -> Text("Loading…", modifier = Modifier.padding(16.dp))
            is RowState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            is RowState.Loaded -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.items, key = { it.id }) { album ->
                    PosterCard(work = album, baseUrl = baseUrl, onOpen = { onOpenAlbum(album) }, onPlay = { onOpenAlbum(album) })
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(
    state: AlbumUiState,
    baseUrl: String,
    nowPlayingAssetId: String?,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onOpenWork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val work = state.work
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            TextButton(onClick = onBack) { Text("‹ Back") }
            if (work != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Poster(url = Artwork.posterUrl(baseUrl, work), kind = "music", contentDescription = null, modifier = Modifier.width(140.dp))
                    Column {
                        Text(work.title, style = MaterialTheme.typography.headlineSmall)
                        listOfNotNull(work.artist, work.year?.toString()).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = onPlayAll, enabled = state.tracks.isNotEmpty(), modifier = Modifier.padding(top = 12.dp)) { Text("▶ Play") }
                        TextButton(onClick = onOpenWork) { Text("Details") }
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Text(
                if (state.loading) "Loading tracks…" else "${state.tracks.size} track" + if (state.tracks.size == 1) "" else "s",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(state.tracks, key = { _, it -> it.id }) { i, track ->
            TrackRow(index = i, track = track, playing = track.id == nowPlayingAssetId, onPlay = { onPlayTrack(i) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun TrackRow(index: Int, track: WorkAsset, playing: Boolean, onPlay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (playing) "▶" else "${index + 1}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(trackTitle(track), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.quality, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The filename without its extension and a leading `01 - ` track prefix. Pure. */
internal fun trackTitle(track: WorkAsset): String {
    val name = track.filename?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: return track.id
    return name.replace(Regex("^\\s*\\d{1,3}\\s*[-._ ]+\\s*"), "").ifBlank { name }
}
