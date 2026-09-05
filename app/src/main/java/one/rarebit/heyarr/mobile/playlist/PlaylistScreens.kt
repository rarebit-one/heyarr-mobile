package one.rarebit.heyarr.mobile.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.home.WorkRow
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator

/** The playlists list — device-side encrypted state, folded on this device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistsScreen(
    state: PlaylistsViewModel.UiState,
    onOpen: (spaceId: String, name: String) -> Unit,
    onCreate: (name: String?) -> Unit,
    onBack: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Playlists") }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } }) },
        floatingActionButton = {
            if (!state.notEnrolled) FloatingActionButton(onClick = { creating = true }) { Text("＋") }
        },
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            when {
                state.notEnrolled -> Info("Enrol this device to keep playlists — they are encrypted and only readable here.")
                state.loading -> Info("Loading…")
                state.error != null -> Info(state.error, isError = true)
                state.playlists.isEmpty() -> Info("No playlists yet. Tap ＋ to make one, or add from any card's ⋯ menu.")
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.playlists, key = { it.spaceId }) { pl ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(pl.spaceId, pl.name) }.padding(16.dp),
                        ) {
                            Text(pl.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pl.itemIds.size} item${if (pl.itemIds.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (creating) {
        NameDialog(
            title = "New playlist",
            confirm = "Create",
            onConfirm = { creating = false; onCreate(it) },
            onDismiss = { creating = false },
        )
    }
}

/** One playlist's items, resolved to browsable works. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistScreen(
    state: PlaylistViewModel.UiState,
    onBack: () -> Unit,
    onPlayItem: (Work) -> Unit,
    onPlayAll: (List<Work>) -> Unit,
    onOpenWork: (Work) -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.name.ifEmpty { "Playlist" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
                actions = {
                    TextButton(onClick = { renaming = true }) { Text("Rename") }
                    if (state.works.isNotEmpty()) TextButton(onClick = { onPlayAll(state.works) }) { Text("▶ Play all") }
                },
            )
        },
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            when {
                state.loading -> Info("Loading…")
                state.error != null -> Info(state.error, isError = true)
                state.works.isEmpty() -> Info("This playlist is empty. Add from any card's ⋯ menu.")
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.works, key = { it.id }) { work ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                WorkRow(work = work, onOpen = { onOpenWork(work) }, onPlay = { onPlayItem(work) })
                            }
                            TextButton(onClick = { onRemove(work.id) }) { Text("✕") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (renaming) {
        NameDialog(
            title = "Rename playlist",
            confirm = "Rename",
            initial = state.name,
            onConfirm = { renaming = false; it?.let(onRename) },
            onDismiss = { renaming = false },
        )
    }
}

/** A dialog that picks a playlist to add an item to, or makes a new one. */
@Composable
internal fun AddToPlaylistDialog(
    playlists: List<PersonalStateCoordinator.PlaylistView>,
    onPick: (spaceId: String) -> Unit,
    onCreateNew: (name: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameDialog(title = "New playlist", confirm = "Create & add", onConfirm = { naming = false; onCreateNew(it) }, onDismiss = onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { naming = true }) { Text("＋ New playlist") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add to playlist") },
        text = {
            if (playlists.isEmpty()) {
                Text("No playlists yet — create one.")
            } else {
                LazyColumn {
                    items(playlists, key = { it.spaceId }) { pl ->
                        Text(
                            pl.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(pl.spaceId) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun NameDialog(
    title: String,
    confirm: String,
    initial: String = "",
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name (optional)") }) },
    )
}

@Composable
private fun Info(message: String, isError: Boolean = false) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
