package one.rarebit.heyarr.mobile.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.mobile.catalog.CatalogClient
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.nav.Route

/** One hub's grid: a content-type chip, a sort, the rows so far and the cursor to the next page. */
data class HubUiState(
    val hub: String,
    val contentType: String,
    val sort: CatalogClient.Sort = CatalogClient.Sort.RECENT,
    val items: List<Work> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val chips: List<String> get() = Route.contentTypesOf(hub)
    val canLoadMore: Boolean get() = nextCursor != null && !loading
}

/** Pages one (content type, sort) query; switching either starts the grid over. */
class HubViewModel(
    hub: String,
    private val catalog: CatalogClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(HubUiState(hub = hub, contentType = Route.contentTypesOf(hub).first()))
    val state: StateFlow<HubUiState> = _state.asStateFlow()

    init { reload() }

    fun selectContentType(contentType: String) {
        if (contentType == _state.value.contentType) return
        _state.value = _state.value.copy(contentType = contentType)
        reload()
    }

    fun toggleSort() {
        val next = if (_state.value.sort == CatalogClient.Sort.RECENT) CatalogClient.Sort.TITLE else CatalogClient.Sort.RECENT
        _state.value = _state.value.copy(sort = next)
        reload()
    }

    fun reload() {
        _state.value = _state.value.copy(items = emptyList(), nextCursor = null, loading = true, error = null)
        fetch(cursor = null)
    }

    fun loadMore() {
        val s = _state.value
        if (!s.canLoadMore) return
        _state.value = s.copy(loading = true)
        fetch(cursor = s.nextCursor)
    }

    private fun fetch(cursor: String?) {
        val asked = _state.value
        viewModelScope.launch {
            val result = withContext(io) { runCatching { catalog.page(asked.contentType, asked.sort, PAGE, cursor) } }
            val now = _state.value
            // The query moved on while this page was in flight: drop it.
            if (now.contentType != asked.contentType || now.sort != asked.sort) return@launch
            _state.value = result.fold(
                onSuccess = { page ->
                    val items = if (cursor == null) page.items else now.items + page.items
                    now.copy(items = items, nextCursor = page.nextCursor, loading = false, error = null)
                },
                onFailure = { now.copy(loading = false, error = it.message ?: "failed to load") },
            )
        }
    }

    companion object {
        const val PAGE = 30
    }
}
