package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.search.AcquireClient
import one.rarebit.heyarr.mobile.search.AcquireState
import one.rarebit.heyarr.mobile.search.SearchResult
import one.rarebit.heyarr.mobile.search.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchStateTest {

    private val hit = SearchResult(workId = "w1", title = "Dune")

    // ── The search state machine: Idle → (Results | Empty), pure transitions ─────

    @Test fun blankQueryIsIdle() {
        assertEquals(SearchUiState.Idle, SearchUiState.forResults("  ", listOf(hit)))
    }

    @Test fun noResultsIsEmpty() {
        val state = SearchUiState.forResults("dune", emptyList())
        assertTrue(state is SearchUiState.Empty)
        assertEquals("dune", (state as SearchUiState.Empty).query)
    }

    @Test fun someResultsIsResults() {
        val state = SearchUiState.forResults("dune", listOf(hit))
        assertTrue(state is SearchUiState.Results)
        state as SearchUiState.Results
        assertEquals("dune", state.query)
        assertEquals(1, state.results.size)
    }

    // ── The follow-vs-one-off outcome mapping ────────────────────────────────────

    @Test fun wantedResultMapsToWantedState() {
        assertEquals(AcquireState.Wanted, AcquireState.of(AcquireClient.Result.Wanted("d1")))
    }

    @Test fun followingResultMapsToFollowingState() {
        assertEquals(AcquireState.Following, AcquireState.of(AcquireClient.Result.Following("s1")))
    }

    @Test fun failedResultCarriesTheMessage() {
        val state = AcquireState.of(AcquireClient.Result.Failed(404, "follow failed: HTTP 404"))
        assertTrue(state is AcquireState.Failed)
        assertEquals("follow failed: HTTP 404", (state as AcquireState.Failed).message)
    }
}
