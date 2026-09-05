package one.rarebit.heyarr.mobile.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.ui.Poster

/** The strip above the bottom bar while audio plays: cover, title, play/pause, next. Tap opens now-playing. */
@Composable
fun MiniPlayer(state: AudioState, onOpen: () -> Unit, onTogglePlay: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val item = state.item ?: return
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column {
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Poster(url = item.artworkUrl, kind = "music", contentDescription = null, modifier = Modifier.width(40.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(item.artist, item.album).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onTogglePlay) { Text(if (state.playing) "⏸" else "▶", style = MaterialTheme.typography.titleLarge) }
                IconButton(onClick = onNext, enabled = state.hasNext) { Text("⏭", style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}
