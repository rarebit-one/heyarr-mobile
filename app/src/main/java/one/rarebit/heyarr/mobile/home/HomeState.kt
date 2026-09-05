package one.rarebit.heyarr.mobile.home

import one.rarebit.heyarr.mobile.catalog.ContinueEntry
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.nav.Route

/** One independently-loading row of the Home shelf. */
sealed interface RowState<out T> {
    data object Loading : RowState<Nothing>
    data class Loaded<T>(val items: List<T>) : RowState<T>
    data class Failed(val message: String) : RowState<Nothing>
}

/**
 * The Home shelf: one "recently added" row per hub. Rows are independent so one hub's
 * failing read never blanks the others — a music library that 500s still leaves the
 * films on screen. Pure transitions, unit-tested.
 */
data class HomeUiState(
    val rows: Map<String, RowState<Work>> = Route.hubs.associateWith { RowState.Loading },
    /** The continue rail; null when this node/session has none (the row is simply absent). */
    val continueRow: RowState<ContinueEntry>? = RowState.Loading,
    val refreshing: Boolean = false,
) {
    fun row(hub: String): RowState<Work> = rows[hub] ?: RowState.Loading

    fun with(hub: String, state: RowState<Work>): HomeUiState = copy(rows = rows + (hub to state))

    /** Everything in flight: loading rows keep their old items when [keepShowing]. */
    fun loading(keepShowing: Boolean): HomeUiState = copy(
        refreshing = true,
        rows = rows.mapValues { (_, r) -> if (keepShowing && r is RowState.Loaded) r else RowState.Loading },
        continueRow = if (keepShowing && continueRow is RowState.Loaded) continueRow else RowState.Loading,
    )

    val anyLoaded: Boolean get() = rows.values.any { it is RowState.Loaded }
}
