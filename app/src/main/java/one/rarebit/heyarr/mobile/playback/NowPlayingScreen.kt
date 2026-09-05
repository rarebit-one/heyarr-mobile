package one.rarebit.heyarr.mobile.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.ui.Poster

/** The full audio screen: cover, transport, scrubber, and the queue. */
@Composable
fun NowPlayingScreen(
    state: AudioState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipTo: (Int) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.item ?: return
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            TextButton(onClick = onStop) { Text("Stop") }
        }
        Poster(url = item.artworkUrl, kind = "music", contentDescription = item.album, modifier = Modifier.fillMaxWidth(0.7f).align(Alignment.CenterHorizontally))
        Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
        Text(listOfNotNull(item.artist, item.album).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        state.error?.let { Text("Playback error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Slider(
            value = state.fraction,
            onValueChange = { if (state.durationMs > 0) onSeek((it * state.durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(clock(state.positionMs), style = MaterialTheme.typography.labelSmall)
            Text(clock(state.durationMs), style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            IconButton(onClick = onPrevious) { Text("⏮", style = MaterialTheme.typography.headlineSmall) }
            IconButton(onClick = onTogglePlay) { Text(if (state.playing) "⏸" else "▶", style = MaterialTheme.typography.headlineMedium) }
            IconButton(onClick = onNext, enabled = state.hasNext) { Text("⏭", style = MaterialTheme.typography.headlineSmall) }
        }
        Text("Queue · ${state.queue.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.queue, key = { i, it -> "$i:${it.assetId}" }) { i, track ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSkipTo(i) }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(if (i == state.index) "▶" else "${i + 1}", style = MaterialTheme.typography.bodyMedium)
                    Text(track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }
    }
}

internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
