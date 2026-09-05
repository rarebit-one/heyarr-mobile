package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.home.HomeUiState
import one.rarebit.heyarr.mobile.home.RowState
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStateTest {

    private val film = Work(id = "w1", title = "Arrival", kind = "movie")

    @Test fun startsWithEveryRowLoading() {
        val s = HomeUiState()
        Route.hubs.forEach { assertEquals(RowState.Loading, s.row(it)) }
        assertFalse(s.anyLoaded)
    }

    @Test fun rowsAreIndependent() {
        val s = HomeUiState()
            .with(Route.HUB_VIDEO, RowState.Loaded(listOf(film)))
            .with(Route.HUB_MUSIC, RowState.Failed("boom"))
        assertEquals(RowState.Loaded(listOf(film)), s.row(Route.HUB_VIDEO))
        assertEquals(RowState.Failed("boom"), s.row(Route.HUB_MUSIC))
        assertEquals(RowState.Loading, s.row(Route.HUB_BOOKS))
        assertTrue(s.anyLoaded)
    }

    @Test fun refreshKeepsLoadedRowsOnScreen() {
        val s = HomeUiState().with(Route.HUB_VIDEO, RowState.Loaded(listOf(film))).with(Route.HUB_MUSIC, RowState.Failed("x"))
        val kept = s.loading(keepShowing = true)
        assertTrue(kept.refreshing)
        assertEquals(RowState.Loaded(listOf(film)), kept.row(Route.HUB_VIDEO))
        assertEquals(RowState.Loading, kept.row(Route.HUB_MUSIC))
        val cleared = s.loading(keepShowing = false)
        assertEquals(RowState.Loading, cleared.row(Route.HUB_VIDEO))
    }
}
