package one.rarebit.heyarr.mobile.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.mobile.search.FollowedSource
import one.rarebit.heyarr.mobile.search.FollowingClient

/**
 * Drives one work's detail: the header (`GET /works/{id}`), its assets (with sizes),
 * its wants, and the followed source behind it — then the management actions the
 * server supports (cancel / pause / resume / retry a want, remove an asset). Every
 * outcome lands as a per-row notice; a `403` lands as the honest read-only hint, never
 * a faked success. Blocking transport calls run on [io].
 *
 * The clients are injected as factories so tests drive the VM with fakes; the
 * activity builds them over the app's `DeviceAuthTransport`.
 */
class WorkDetailViewModel(
    private val work: Work,
    private val library: LibraryClient,
    private val detail: WorkDetailClient,
    private val following: FollowingClient?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<WorkDetailUiState>(WorkDetailUiState.Loaded(work))
    val state: StateFlow<WorkDetailUiState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init { load() }

    /** (Re)load everything. The tapped row shows immediately; reads fill in as they land. */
    fun load() {
        _refreshing.value = true
        viewModelScope.launch {
            val loaded = withContext(io) {
                val header = runCatching { library.getWork(work.id) }.getOrNull() ?: work
                val assets = runCatching { detail.assetsForWork(work.id) }
                val wants = runCatching { detail.wantsForWork(work.id) }
                val sources: List<FollowedSource> = following?.let { f -> runCatching { f.list() }.getOrDefault(emptyList()) } ?: emptyList()
                val partial = listOfNotNull(
                    assets.exceptionOrNull()?.let { "assets: ${it.message}" },
                    wants.exceptionOrNull()?.let { "wants: ${it.message}" },
                ).joinToString("; ").ifBlank { null }
                WorkDetailUiState.Loaded(
                    work = header,
                    assets = assets.getOrDefault(emptyList()),
                    wants = wants.getOrDefault(emptyList()),
                    source = WorkDetailUiState.sourceFor(work.id, sources),
                    partialError = partial,
                )
            }
            _state.value = loaded
            _refreshing.value = false
        }
    }

    fun cancelWant(want: Want) = act(want.id) { detail.cancelWant(want.id) to { s: WorkDetailUiState.Loaded -> s.wantRemoved(want.id) } }

    fun setMonitor(want: Want, monitor: Boolean) = act(want.id) {
        val outcome = detail.setMonitor(want.id, monitor)
        outcome to { s: WorkDetailUiState.Loaded ->
            val updated = (outcome as? WorkDetailClient.Outcome.Done)?.let { WorkDetailJson.parseWant(it.body) }
                ?: want.copy(monitor = monitor)
            s.wantReplaced(updated)
        }
    }

    fun retry(want: Want) = act(want.id) {
        detail.reconcile(want.id) to { s: WorkDetailUiState.Loaded -> s.noticed(want.id, "Reconciliation queued.") }
    }

    fun searchAgain(want: Want) = act(want.id) {
        detail.searchAgain(want.id) to { s: WorkDetailUiState.Loaded -> s.noticed(want.id, "Search queued.") }
    }

    fun removeAsset(asset: WorkAsset) = act(asset.id) {
        detail.removeAsset(asset.id) to { s: WorkDetailUiState.Loaded -> s.assetRemoved(asset.id) }
    }

    /**
     * Run one management write for [target]: mark it busy, call, then either apply
     * [onDone] to the loaded state or surface the outcome's message as its notice.
     */
    private fun act(target: String, call: () -> Pair<WorkDetailClient.Outcome, (WorkDetailUiState.Loaded) -> WorkDetailUiState.Loaded>) {
        _state.update { (it as? WorkDetailUiState.Loaded)?.starting(target) ?: it }
        viewModelScope.launch {
            val (outcome, onDone) = withContext(io) {
                runCatching { call() }.getOrElse { WorkDetailClient.Outcome.Failed(0, it.message ?: "action failed") to { s: WorkDetailUiState.Loaded -> s } }
            }
            _state.update { current ->
                val loaded = current as? WorkDetailUiState.Loaded ?: return@update current
                when (outcome) {
                    is WorkDetailClient.Outcome.Done -> onDone(loaded)
                    is WorkDetailClient.Outcome.ReadOnly -> loaded.noticed(target, outcome.message)
                    is WorkDetailClient.Outcome.Refused -> loaded.noticed(target, outcome.message)
                    is WorkDetailClient.Outcome.Failed -> loaded.noticed(target, outcome.message)
                }
            }
        }
    }
}
