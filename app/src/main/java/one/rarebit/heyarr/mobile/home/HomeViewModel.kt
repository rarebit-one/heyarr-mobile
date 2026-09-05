package one.rarebit.heyarr.mobile.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.mobile.catalog.CatalogClient
import one.rarebit.heyarr.mobile.catalog.ContinueClient
import one.rarebit.heyarr.mobile.catalog.ContinueEntry
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.net.Timestamps

/** Loads each hub's recently-added row in parallel; a row's failure is that row's alone. */
class HomeViewModel(
    private val catalog: CatalogClient,
    private val continueClient: ContinueClient? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { load() }

    fun refresh() = load(keepShowing = true)

    private fun load(keepShowing: Boolean = false) {
        _state.value = _state.value.loading(keepShowing)
        viewModelScope.launch {
            val rail = async {
                val row: RowState<ContinueEntry>? = withContext(io) {
                    val client = continueClient ?: return@withContext null
                    runCatching { client.rail() }.fold(
                        onSuccess = { when (it) { is ContinueClient.Outcome.Rail -> RowState.Loaded(it.entries); is ContinueClient.Outcome.Unavailable -> null } },
                        onFailure = { RowState.Failed(it.message ?: "failed to load") },
                    )
                }
                _state.value = _state.value.copy(continueRow = row)
            }
            Route.hubs.map { hub ->
                async {
                    val row = withContext(io) {
                        runCatching { recentForHub(hub) }
                            .map { RowState.Loaded(it) as RowState<Work> }
                            .getOrElse { RowState.Failed(it.message ?: "failed to load") }
                    }
                    _state.value = _state.value.with(hub, row)
                }
            }.awaitAll()
            rail.await()
            _state.value = _state.value.copy(refreshing = false)
        }
    }

    /** A hub spans content types (video = movies + series): fetch each, merge newest-first. */
    private fun recentForHub(hub: String): List<Work> {
        val merged = Route.contentTypesOf(hub).flatMap { catalog.recent(it, ROW_LENGTH) }
        return Timestamps.recentFirst(merged) { it.createdAt ?: it.updatedAt }.take(ROW_LENGTH)
    }

    companion object {
        const val ROW_LENGTH = 12
    }
}
