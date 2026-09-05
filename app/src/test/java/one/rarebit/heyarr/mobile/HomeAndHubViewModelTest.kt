package one.rarebit.heyarr.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.catalog.CatalogClient
import one.rarebit.heyarr.mobile.catalog.ContinueClient
import one.rarebit.heyarr.mobile.home.HomeViewModel
import one.rarebit.heyarr.mobile.home.RowState
import one.rarebit.heyarr.mobile.hub.HubViewModel
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.net.HttpResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()

    private val base = "https://h.example"
    private val cred = Credential.Session("t")

    @Test fun rowsLoadIndependentlyAndAFailingHubDoesNotBlankTheOthers() {
        val t = SubstringTransport(listOf(
            "GET /works?limit=12&content_type=movie" to HttpResponse(200, worksPage(workJson("m1", "Film", "movie", "2026-08-02T00:00:00Z"))),
            "GET /works?limit=12&content_type=series" to HttpResponse(200, worksPage(workJson("s1", "Show", "series", "2026-08-03T00:00:00Z"))),
            "GET /works?limit=12&content_type=music" to HttpResponse(500, ""),
            "GET /works?limit=12&content_type=book" to HttpResponse(200, worksPage()),
            "GET /consumption/continue" to HttpResponse(404, ""),
        ))
        val vm = HomeViewModel(CatalogClient(t, base, cred), ContinueClient(t, base, cred), dispatcher)
        val s = vm.state.value
        assertFalse(s.refreshing)
        // Video merges movies + series newest-first.
        assertEquals(listOf("s1", "m1"), (s.row(Route.HUB_VIDEO) as RowState.Loaded).items.map { it.id })
        assertTrue(s.row(Route.HUB_MUSIC) is RowState.Failed)
        assertEquals(0, (s.row(Route.HUB_BOOKS) as RowState.Loaded).items.size)
        // An older node has no rail: the row is absent, not an error.
        assertNull(s.continueRow)
    }

    @Test fun theContinueRowLoadsFromTheRail() {
        val rail = """{"items":[{"session":{"id":"s1","state":"paused","progress":{"locator":"10","unit":"seconds"}},
            "work":{"id":"w1","title":"Arrival","content_type":"movie"},"edition":{"id":"e1","label":"x","attributes":{}},
            "asset":{"asset_id":"a1","edition_id":"e1","blob_hash":"blake3:11","mime":"video/mp4","duration_seconds":100}}]}"""
        val t = SubstringTransport(listOf(
            "GET /consumption/continue" to HttpResponse(200, rail),
            "GET /works" to HttpResponse(200, worksPage()),
        ))
        val vm = HomeViewModel(CatalogClient(t, base, cred), ContinueClient(t, base, cred), dispatcher)
        val row = vm.state.value.continueRow as RowState.Loaded
        assertEquals("s1", row.items.single().sessionId)
    }

    @Test fun refreshKeepsLoadedRowsWhileReloading() {
        val t = SubstringTransport(listOf("GET /works" to HttpResponse(200, worksPage(workJson("m1", "Film", "movie"))), "GET /consumption/continue" to HttpResponse(403, "")))
        val vm = HomeViewModel(CatalogClient(t, base, cred), ContinueClient(t, base, cred), dispatcher)
        val before = t.calls.size
        vm.refresh()
        assertTrue(t.calls.size > before)
        assertTrue(vm.state.value.row(Route.HUB_VIDEO) is RowState.Loaded)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HubViewModelTest {
    private val base = "https://h.example"
    private val cred = Credential.Session("t")

    @Test fun pagesAppendAndStopWhenTheCursorEnds() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val t = SubstringTransport(listOf(
                "GET /works?limit=30&content_type=movie&sort=recent&include=artwork%2Cprimary_asset&cursor=c1" to HttpResponse(200, worksPage(workJson("m2", "B", "movie"))),
                "GET /works?limit=30&content_type=movie&sort=recent" to HttpResponse(200, worksPage(workJson("m1", "A", "movie"), next = "c1")),
            ))
            val vm = HubViewModel(Route.HUB_VIDEO, CatalogClient(t, base, cred), UnconfinedTestDispatcher(testScheduler))
            assertEquals(listOf("m1"), vm.state.value.items.map { it.id })
            assertTrue(vm.state.value.canLoadMore)
            vm.loadMore()
            assertEquals(listOf("m1", "m2"), vm.state.value.items.map { it.id })
            assertFalse(vm.state.value.canLoadMore)
            vm.loadMore() // nothing more: no request
            assertEquals(2, t.calls.size)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun aPageThatLandsAfterTheChipChangedIsDropped() = runTest {
        val std = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(std)
        try {
            val t = SubstringTransport(listOf(
                "content_type=movie" to HttpResponse(200, worksPage(workJson("m1", "Film", "movie"))),
                "content_type=series" to HttpResponse(200, worksPage(workJson("s1", "Show", "series"))),
            ))
            val vm = HubViewModel(Route.HUB_VIDEO, CatalogClient(t, base, cred), std)
            // The movie fetch is scheduled but has not run; the user switches to series first.
            vm.selectContentType("series")
            advanceUntilIdle()
            assertEquals("series", vm.state.value.contentType)
            assertEquals(listOf("s1"), vm.state.value.items.map { it.id })
            assertNull(vm.state.value.error)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun sortToggleReloadsAndAFailureIsShown() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val t = SubstringTransport(listOf(
                "sort=title" to HttpResponse(503, ""),
                "sort=recent" to HttpResponse(200, worksPage(workJson("m1", "Film", "movie"))),
            ))
            val vm = HubViewModel(Route.HUB_VIDEO, CatalogClient(t, base, cred), UnconfinedTestDispatcher(testScheduler))
            assertEquals(CatalogClient.Sort.RECENT, vm.state.value.sort)
            vm.toggleSort()
            assertEquals(CatalogClient.Sort.TITLE, vm.state.value.sort)
            assertTrue(vm.state.value.error!!.contains("503"))
            assertFalse(vm.state.value.loading)
        } finally { Dispatchers.resetMain() }
    }
}
