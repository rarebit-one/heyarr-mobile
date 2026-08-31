package one.rarebit.heyarr.mobile.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** UI state for the library browse screen. */
sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Loaded(val works: List<Work>) : LibraryUiState
    data class Error(val message: String) : LibraryUiState
}

/**
 * The library browse list — heyarr's native `/api/v1/works` rendered as rows. Tapping
 * a row hands the work to [onPlay], which streams it in the M10 player (a directly
 * streamable row shows a "Tap to play" affordance; one whose asset must be negotiated
 * says so rather than silently doing nothing).
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onPlay: (Work) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Library", style = MaterialTheme.typography.headlineSmall)
        when (state) {
            is LibraryUiState.Loading ->
                Text("Loading…", modifier = Modifier.padding(top = 12.dp))
            is LibraryUiState.Error ->
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            is LibraryUiState.Loaded -> {
                if (state.works.isEmpty()) {
                    Text("No works yet.", modifier = Modifier.padding(top = 12.dp))
                } else {
                    LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                        items(state.works) { work ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPlay(work) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(work.title, style = MaterialTheme.typography.bodyLarge)
                                val subtitle = buildString {
                                    work.kind?.let { append(it) }
                                    if (work.isPlayable) {
                                        if (isNotEmpty()) append(" · ")
                                        append("Tap to play")
                                    }
                                }
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
}
