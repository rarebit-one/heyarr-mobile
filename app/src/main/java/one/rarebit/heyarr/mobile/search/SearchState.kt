package one.rarebit.heyarr.mobile.search

/**
 * The search screen's state machine: `Idle → Searching → (Results | Empty | Error)`.
 * The transitions are pure functions ([forResults]) so the machine is unit-tested on
 * plain JVM without Compose or coroutines — the same testability stance as the rest of
 * the scaffold (clients + parsers, no Android runtime in CI).
 */
sealed interface SearchUiState {
    /** Nothing searched yet (or the query was cleared). */
    data object Idle : SearchUiState

    /** A search is in flight for [query]. */
    data class Searching(val query: String) : SearchUiState

    /** [query] returned [results]. */
    data class Results(val query: String, val results: List<SearchResult>) : SearchUiState

    /** [query] returned nothing. */
    data class Empty(val query: String) : SearchUiState

    /** The search failed. */
    data class Error(val message: String) : SearchUiState

    companion object {
        /**
         * The single meaningful state transition: a completed search over [query]
         * lands on [Empty] when nothing came back, else [Results]. A blank query is
         * [Idle] (there is nothing to show). Pure — the unit test drives it directly.
         */
        fun forResults(query: String, results: List<SearchResult>): SearchUiState = when {
            query.isBlank() -> Idle
            results.isEmpty() -> Empty(query)
            else -> Results(query, results)
        }
    }
}

/**
 * Per-result acquire status, tracked independently of the list so one row's action
 * does not re-render or reset another's. This is the UI memory of "I already asked to
 * get this / follow this."
 */
sealed interface AcquireState {
    /** No action taken on this result. */
    data object None : AcquireState

    /** A getOnce/follow request is in flight. */
    data object InFlight : AcquireState

    /** A one-off want was created. */
    data object Wanted : AcquireState

    /** An ongoing follow was created. */
    data object Following : AcquireState

    /** The last action on this result failed. */
    data class Failed(val message: String) : AcquireState

    companion object {
        /** Map an [AcquireClient.Result] onto the per-row [AcquireState]. Pure — unit-tested. */
        fun of(result: AcquireClient.Result): AcquireState = when (result) {
            is AcquireClient.Result.Wanted -> Wanted
            is AcquireClient.Result.Following -> Following
            is AcquireClient.Result.Failed -> Failed(result.message)
        }
    }
}
