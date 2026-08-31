package one.rarebit.heyarr.mobile.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
 * The library browse list — heyarr's native `/api/v1/works` rendered as rows. This
 * is the "browse list wired to the contract" the scaffold demonstrates; tapping a row
 * into detail + playback is a follow-up (see playback/).
 */
@Composable
fun LibraryScreen(state: LibraryUiState, modifier: Modifier = Modifier) {
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
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(work.title, style = MaterialTheme.typography.bodyLarge)
                                work.kind?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
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
