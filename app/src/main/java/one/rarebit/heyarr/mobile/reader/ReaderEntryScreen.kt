package one.rarebit.heyarr.mobile.reader

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.catalog.Artwork
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.library.WorkDetailUiState
import one.rarebit.heyarr.mobile.ui.Poster

/**
 * The reading entry point: the book's cover and each readable file with its format.
 * The reader itself (Readium: EPUB/PDF/CBZ with locators the server's `cfi`/`page`
 * progress units map onto) is the next slice; an audiobook already plays through the
 * audio queue. This screen is the seam it plugs into.
 */
@Composable
fun ReaderEntryScreen(
    state: WorkDetailUiState,
    baseUrl: String,
    onBack: () -> Unit,
    onListen: (WorkAsset) -> Unit,
    onOpenWork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item { TextButton(onClick = onBack) { Text("‹ Back") } }
        when (state) {
            is WorkDetailUiState.Loading -> item { Text("Loading…") }
            is WorkDetailUiState.Error -> item { Text(state.message, color = MaterialTheme.colorScheme.error) }
            is WorkDetailUiState.Loaded -> {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Poster(url = Artwork.posterUrl(baseUrl, state.work), kind = "book", contentDescription = null, modifier = Modifier.width(120.dp))
                        Column {
                            Text(state.work.title, style = MaterialTheme.typography.headlineSmall)
                            listOfNotNull(state.work.author, state.work.year?.toString()).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(onClick = onOpenWork) { Text("Details") }
                        }
                    }
                    Text("Files", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                }
                val readable = state.assets.filter { it.isPlayable }.map { it to ReaderFormat.of(it.mime, it.filename) }
                if (readable.isEmpty()) item { Text("No readable file in the catalog yet.", style = MaterialTheme.typography.bodySmall) }
                items(readable, key = { it.first.id }) { (asset, format) ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(asset.filename ?: asset.id, style = MaterialTheme.typography.bodyLarge)
                        Text(listOfNotNull(format?.label, asset.quality.takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            when (format) {
                                ReaderFormat.AUDIOBOOK -> Button(onClick = { onListen(asset) }) { Text("▶ Listen") }
                                null -> OutlinedButton(onClick = {}, enabled = false) { Text("Unsupported") }
                                else -> OutlinedButton(onClick = {}, enabled = false) { Text("Open in reader · coming soon") }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
