package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.heyarr.DesiredItem
import one.rarebit.heyarr.mobile.heyarr.seriesWantState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0089: a work-scoped want on a series establishes a follow whose poll enumerates
 * episodes as item-scoped wants. [seriesWantState] reads that from a work's wants exactly
 * as heyarr-desktop does, so the "Follow the whole series" door shows the right state.
 */
class SeriesWantTest {

    private fun want(scope: String, state: String = "SELECTED") = DesiredItem(
        id = "d-$scope", workId = "w1", scope = scope, qualityProfileId = "everyday",
        monitor = true, reason = null, state = state, phase = null, content = null,
        placement = null, detail = null, updatedAt = null,
    )

    @Test fun aFollowIsSignalledByItemScopedWants() {
        val s = seriesWantState(listOf(want("item"), want("item")))
        assertTrue("item-scoped wants mean the series is followed", s.following)
        assertTrue(s.everythingCovered)
    }

    @Test fun aBareWorkWantCoversTheSeriesBeforeTheFollowCatchesUp() {
        // No metadata provider yet: ADR-0089 §2 leaves a bare work want. The door is
        // still "covered" so we don't offer it twice, even though following is not true.
        val s = seriesWantState(listOf(want("work")))
        assertFalse(s.following)
        assertTrue(s.wholeSeriesWanted)
        assertTrue(s.everythingCovered)
    }

    @Test fun onlySeasonWantsLeaveTheWholeSeriesDoorOpen() {
        // An edition-scope (season) want is a genuine one-off; the whole-series door
        // stays available.
        val s = seriesWantState(listOf(want("edition")))
        assertFalse(s.following)
        assertFalse(s.wholeSeriesWanted)
        assertFalse(s.everythingCovered)
    }

    @Test fun noWantsIsUncovered() {
        val s = seriesWantState(emptyList())
        assertFalse(s.everythingCovered)
    }
}
