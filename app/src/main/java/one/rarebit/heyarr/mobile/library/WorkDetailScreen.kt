package one.rarebit.heyarr.mobile.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import one.rarebit.heyarr.mobile.ui.Poster
import one.rarebit.heyarr.mobile.nav.Route
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.net.Timestamps
import one.rarebit.heyarr.mobile.search.FollowedSource
import one.rarebit.heyarr.mobile.search.ReadOnlyAuthorityBanner
import one.rarebit.heyarr.mobile.search.SessionAuthority

/**
 * One work: title / year / kind, its identity, its external ids (#431), its files with
 * quality + size and a **Play** per streamable one (`GET /works/{id}/assets`, joined,
 * #429), its wants (with the §64 status and the management actions the server has —
 * cancel, pause/resume, retry, search again), and the followed source it came from
 * (tap → its detail). The work itself can now be **corrected** (`PATCH /works/{id}`)
 * and **removed** (`DELETE /works/{id}`, #428) from here.
 *
 * Every write honours the session's authority: read-only ⇒ the buttons are disabled
 * under the same honest banner the Following screen shows, and the row's notice says
 * why if tapped anyway. A delete a followed source blocks answers 409; the message is
 * surfaced verbatim, never hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkDetailScreen(
    state: WorkDetailUiState,
    refreshing: Boolean,
    authority: SessionAuthority?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onPlay: (Work, WorkAsset) -> Unit,
    onCancelWant: (Want) -> Unit,
    onSetMonitor: (Want, Boolean) -> Unit,
    onRetry: (Want) -> Unit,
    onSearchAgain: (Want) -> Unit,
    onRemoveAsset: (WorkAsset) -> Unit,
    onEditWork: (WorkPatch) -> Unit,
    onDeleteWork: () -> Unit,
    onWorkDeleted: () -> Unit,
    onOpenSource: (FollowedSource) -> Unit,
    onAuthorityRecheck: () -> Unit,
    modifier: Modifier = Modifier,
    /** The poster to draw in the header, when the caller can name one. */
    posterUrl: String? = null,
    /** Open the Manage section from the start (reached from the Library tab). */
    manageMode: Boolean = false,
    /** Open a want's own screen (its releases and verdicts). */
    onOpenWant: ((Want) -> Unit)? = null,
) {
    val canWrite = authority?.canWrite == true
    var showEdit by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var manageOpen by remember { mutableStateOf(manageMode) }

    val loaded = state as? WorkDetailUiState.Loaded
    // Once the delete took, leave the screen — the work is gone from the library.
    LaunchedEffect(loaded?.deleted) { if (loaded?.deleted == true) onWorkDeleted() }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("‹ Back") }
            }
            when (state) {
                is WorkDetailUiState.Loading -> item { Text("Loading…", modifier = Modifier.padding(top = 12.dp)) }
                is WorkDetailUiState.Error -> item {
                    Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                }
                is WorkDetailUiState.Loaded -> {
                    item {
                        Header(state.work, posterUrl)
                        // The one-tap play: the first file that holds bytes. The Files
                        // section below still offers every file.
                        state.assets.firstOrNull { !it.blobHash.isNullOrBlank() }?.let { first ->
                            Button(onClick = { onPlay(state.work, first) }, modifier = Modifier.padding(bottom = 12.dp)) {
                                Text(if (Route.hubFor(state.work.kind) == Route.HUB_BOOKS) "▶ Open" else "▶ Play")
                            }
                        }
                    }
                    if (authority?.isReadOnly == true) {
                        item {
                            ReadOnlyAuthorityBanner(
                                deviceKey = authority.deviceKey,
                                isDevice = authority.isDevice,
                                onRecheck = onAuthorityRecheck,
                            )
                        }
                    }
                    state.partialError?.let { err ->
                        item {
                            Text("Some reads failed — $err", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }

                    item { SectionTitle("Files", "${state.assets.size}") }
                    if (state.assets.isEmpty()) {
                        item { Text("No files in the catalog for this work yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                    }
                    items(state.assets, key = { "asset:" + it.id }) { asset ->
                        AssetRow(
                            asset = asset,
                            notice = state.notices[asset.id],
                            busy = asset.id in state.busy,
                            canWrite = canWrite,
                            onPlay = { onPlay(state.work, asset) },
                            onRemove = { onRemoveAsset(asset) },
                        )
                        HorizontalDivider()
                    }

                    item { SectionTitle("Wants", "${state.wants.size}") }
                    if (state.wants.isEmpty()) {
                        item { Text("Nothing wanted for this work — use Search → Get once / Follow.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                    }
                    items(state.wants, key = { "want:" + it.id }) { want ->
                        if (onOpenWant != null) {
                            TextButton(onClick = { onOpenWant(want) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Releases ›") }
                        }
                        WantRow(
                            want = want,
                            notice = state.notices[want.id],
                            busy = want.id in state.busy,
                            canWrite = canWrite,
                            onCancel = { onCancelWant(want) },
                            onSetMonitor = { onSetMonitor(want, it) },
                            onRetry = { onRetry(want) },
                            onSearchAgain = { onSearchAgain(want) },
                        )
                        HorizontalDivider()
                    }

                    item { SectionTitle("Followed source", null) }
                    item {
                        val source = state.source
                        if (source == null) {
                            Text("Not projected by a followed source.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                        } else {
                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Text(listOfNotNull(source.type, source.feedRef).joinToString(" · ").ifBlank { source.id })
                                Text(
                                    "${source.itemsArchived ?: 0}/${source.itemsKnown ?: 0} archived · ${source.health ?: "unknown"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(onClick = { onOpenSource(source) }, modifier = Modifier.padding(top = 4.dp)) { Text("Open source") }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { manageOpen = !manageOpen },
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            SectionTitle("Manage", null)
                            Text(if (manageOpen) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (manageOpen) item {
                        val busy = WorkDetailUiState.NOTICE_WORK in state.busy
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showEdit = true }, enabled = canWrite && !busy) { Text("Edit metadata") }
                                OutlinedButton(onClick = { showDeleteConfirm = true }, enabled = canWrite && !busy) { Text("Delete work") }
                            }
                            Text(
                                "Deleting removes the work, its editions, files and wants from the catalog — the bytes " +
                                    "stay until garbage collection (ADR-0018). A work you still follow can't be deleted " +
                                    "until you unfollow it.",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            state.notices[WorkDetailUiState.NOTICE_WORK]?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (loaded != null && showEdit) {
        EditWorkDialog(
            work = loaded.work,
            onDismiss = { showEdit = false },
            onSave = { patch -> showEdit = false; onEditWork(patch) },
        )
    }
    if (loaded != null && showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this work?") },
            text = { Text("“${loaded.work.title}” and its ${loaded.assets.size} file(s) leave the catalog. The bytes are reclaimed later by garbage collection.") },
            confirmButton = { TextButton(onClick = { showDeleteConfirm = false; onDeleteWork() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(work: Work, posterUrl: String?) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Poster(url = posterUrl, kind = work.kind, contentDescription = null, modifier = Modifier.width(110.dp))
            Column {
                Text(work.title, style = MaterialTheme.typography.headlineSmall)
                val meta = listOfNotNull(work.year?.toString(), work.kind, work.artist ?: work.author).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("id ${work.id}", style = MaterialTheme.typography.labelSmall)
        work.workKey?.let { Text("key $it", style = MaterialTheme.typography.labelSmall) }
        Timestamps.short(work.updatedAt ?: work.createdAt)?.let {
            Text("updated $it", style = MaterialTheme.typography.labelSmall)
        }
        // External ids (#431), read-only, keyed by source (tmdb/imdb/tvdb).
        if (work.externalIds.isEmpty()) {
            Text("No external ids reconciled yet.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        } else {
            Text("External ids", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            work.externalIds.entries.sortedBy { it.key }.forEach { (source, id) ->
                Text("$source: $id", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Edit a work's title / year / content type — the fields `PATCH /works/{id}` accepts (#428). */
@Composable
private fun EditWorkDialog(work: Work, onDismiss: () -> Unit, onSave: (WorkPatch) -> Unit) {
    var title by remember { mutableStateOf(work.title) }
    var year by remember { mutableStateOf(work.year?.toString() ?: "") }
    var contentType by remember { mutableStateOf(work.kind ?: "") }

    // Only the fields that actually changed; a cleared year sends year:0 (distinct from omit).
    fun patch(): WorkPatch {
        val trimmedTitle = title.trim()
        val yearInput = year.trim()
        val parsedYear = yearInput.toIntOrNull()
        return WorkPatch(
            title = trimmedTitle.takeIf { it.isNotBlank() && it != work.title },
            year = parsedYear?.takeIf { it != work.year },
            clearYear = yearInput.isBlank() && work.year != null,
            contentType = contentType.trim().takeIf { it.isNotBlank() && it != (work.kind ?: "") },
        )
    }

    val p = patch()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit metadata") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it.filter(Char::isDigit) },
                    label = { Text("Year (blank clears it)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(value = contentType, onValueChange = { contentType = it }, label = { Text("Content type") }, singleLine = true)
                if (title.trim().isBlank()) {
                    Text("A blank title is refused by the server.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                } else if (p.isEmpty) {
                    Text("Nothing changed yet.", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(p) }, enabled = title.trim().isNotBlank() && !p.isEmpty) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(title: String, count: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (count != null) Text("  $count", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AssetRow(
    asset: WorkAsset,
    notice: String?,
    busy: Boolean,
    canWrite: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(asset.filename ?: asset.sourcePath?.substringAfterLast('/') ?: asset.id, style = MaterialTheme.typography.bodyLarge)
        val quality = asset.quality
        if (quality.isNotEmpty()) Text(quality, style = MaterialTheme.typography.bodySmall)
        if (asset.isMissing) {
            Text("missing since ${Timestamps.short(asset.missingSince) ?: asset.missingSince}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = onPlay, enabled = asset.isPlayable && !busy) { Text("Play") }
            OutlinedButton(onClick = onRemove, enabled = canWrite && !busy) { Text("Remove file") }
        }
        if (!asset.isPlayable && !asset.isMissing) {
            Text("No blob to stream (a linked asset has none).", style = MaterialTheme.typography.labelSmall)
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun WantRow(
    want: Want,
    notice: String?,
    busy: Boolean,
    canWrite: Boolean,
    onCancel: () -> Unit,
    onSetMonitor: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onSearchAgain: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(want.status, style = MaterialTheme.typography.bodyLarge)
        val meta = listOfNotNull(
            want.scope?.let { "$it scope" },
            want.qualityProfileId?.let { "profile $it" },
            want.content?.let { "content $it" },
            want.placement?.let { "placement $it" },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
        want.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        want.reason?.takeIf { it.isNotBlank() }?.let { Text("“$it”", style = MaterialTheme.typography.labelSmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = onRetry, enabled = canWrite && !busy) { Text("Retry") }
            OutlinedButton(onClick = onSearchAgain, enabled = canWrite && !busy) { Text("Search again") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { onSetMonitor(!want.monitor) }, enabled = canWrite && !busy) {
                Text(if (want.monitor) "Pause" else "Resume")
            }
            OutlinedButton(onClick = onCancel, enabled = canWrite && !busy) { Text("Cancel want") }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}
