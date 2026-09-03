package one.rarebit.heyarr.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.search.FollowedSourcesJson
import one.rarebit.heyarr.mobile.search.FollowingClient
import one.rarebit.heyarr.mobile.search.FollowingUiState
import one.rarebit.heyarr.mobile.search.SearchViewModel
import one.rarebit.heyarr.mobile.search.SourceDetailUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FollowedSourceDetailTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()

    private val list = """{"followed_sources":[
        {"id":"s1","work_id":"w1","type":"tv_series","feed_ref":"tvdb:424242","quality_profile_id":"qp","monitor":true,"backfill":"from_now","reason":"weekly","items_known":10,"items_archived":8,"health":"healthy","created_at":"2026-08-01T00:00:00Z","last_polled_at":"2026-09-01T00:00:00Z","next_poll_at":"2026-09-02T00:00:00Z"},
        {"id":"s2","work_id":"w2","type":"youtube_channel","feed_ref":"UCabc","quality_profile_id":"qp","monitor":true,"backfill":"full","items_known":0,"items_archived":0,"health":"unknown","created_at":"2026-08-15T00:00:00Z"}
    ]}"""

    @Test fun parsesTheFullFollowedSourceView() {
        val s = FollowedSourcesJson.parse(list)
        assertEquals("tvdb:424242", s[0].feedRef)
        assertEquals("qp", s[0].qualityProfileId)
        assertEquals(true, s[0].monitor)
        assertEquals("from_now", s[0].backfill)
        assertEquals("weekly", s[0].reason)
        assertEquals("2026-09-01T00:00:00Z", s[0].lastPolledAt)
        assertEquals("2026-09-02T00:00:00Z", s[0].nextPollAt)
        assertNull(s[1].lastPolledAt)
        assertEquals("2026-08-15T00:00:00Z", s[1].recency)
        // s1 polled on 1 Sep beats s2 never-polled-but-created 15 Aug.
        assertEquals(listOf("s1", "s2"), FollowedSourcesJson.recentFirst(s.reversed()).map { it.id })
    }

    // One archived episode with a projected want, and one back-catalogue item the source
    // only knows about (want:null) — the #430 items shape.
    private val items = """{"items":[
        {"id":"i1","work_id":"w1","edition_id":"e1","item_key":"S01E01","title":"Pilot","published_at":"2026-08-20T00:00:00Z","archived":true,"want":{"desired_item_id":"d1","phase":"complete","content":"satisfied","placement":"satisfied"},"created_at":"x"},
        {"id":"i2","work_id":"w1","item_key":"S00E00","title":"Old special","archived":false,"want":null,"created_at":"x"}
    ]}"""

    private fun vm(vararg extra: Pair<String, HttpResponse>): Pair<SearchViewModel, RoutedTransport> {
        val t = RoutedTransport(
            mapOf(
                "GET /followed-sources" to HttpResponse(200, list),
                "GET /followed-sources/s1" to HttpResponse(200, """{"id":"s1","work_id":"w1","title":"Some Show","type":"tv_series","feed_ref":"tvdb:424242","quality_profile_id":"qp","monitor":true,"backfill":"from_now","reason":"weekly","items_known":10,"items_archived":8,"health":"healthy","created_at":"2026-08-01T00:00:00Z","last_polled_at":"2026-09-01T00:00:00Z","next_poll_at":"2026-09-02T00:00:00Z"}"""),
                "GET /followed-sources/s1/items?limit=200" to HttpResponse(200, items),
                *extra,
            ),
        )
        val vm = SearchViewModel(HeyarrConfig(baseUrl = "https://h"), Credential.Session("tok"), t, io = dispatcher)
        return vm to t
    }

    @Test fun listIsRecentFirstAndOpenSourceLoadsItsArchive() {
        val (vm, _) = vm()
        vm.loadFollowing()
        assertEquals(listOf("s1", "s2"), (vm.followingState.value as FollowingUiState.Loaded).sources.map { it.id })
        vm.openSource("s1")
        val d = vm.sourceDetail.value as SourceDetailUiState.Loaded
        assertEquals("tvdb:424242", d.source.feedRef)
        assertEquals(listOf("i1", "i2"), d.items.map { it.id })
        assertEquals(true, d.items[0].archived)
        assertEquals("d1", d.items[0].want?.desiredItemId)
        assertNull(d.items[1].want)
        assertNull(d.error)
        vm.closeSource()
        assertNull(vm.sourceDetail.value)
    }

    @Test fun unfollowKeepArchiveTrueMarksGoneAndReloadsTheList() {
        val (vm, t) = vm("DELETE /followed-sources/s1?keep_archive=true" to HttpResponse(204, ""))
        vm.loadFollowing()
        vm.openSource("s1")
        vm.unfollowFromDetail((vm.sourceDetail.value as SourceDetailUiState.Loaded).source, keepArchive = true)
        val d = vm.sourceDetail.value as SourceDetailUiState.Loaded
        assertTrue(d.gone)
        assertNull(d.error)
        assertTrue(t.calls.any { it.first == "DELETE" && it.second.endsWith("/followed-sources/s1?keep_archive=true") })
    }

    @Test fun unfollowRemoveArchiveSurfacesThePhase1Refusal() {
        val detail = "removing the archive is not implemented yet — Phase 1 unfollow stops polling and keeps what was archived"
        val (vm, _) = vm("DELETE /followed-sources/s1?keep_archive=false" to HttpResponse(400, """{"status":400,"detail":"$detail"}"""))
        vm.loadFollowing()
        vm.openSource("s1")
        vm.unfollowFromDetail((vm.sourceDetail.value as SourceDetailUiState.Loaded).source, keepArchive = false)
        val d = vm.sourceDetail.value as SourceDetailUiState.Loaded
        assertTrue(!d.gone)
        assertEquals(detail, d.error)
    }

    @Test fun unfollowOn403IsTheHonestReadOnlyHint() {
        val (vm, _) = vm("DELETE /followed-sources/s1?keep_archive=true" to HttpResponse(403, """{"status":403,"detail":"scope"}"""))
        vm.loadFollowing()
        vm.openSource("s1")
        vm.unfollowFromDetail((vm.sourceDetail.value as SourceDetailUiState.Loaded).source, keepArchive = true)
        assertEquals(FollowingClient.READ_ONLY_UNFOLLOW_HINT, (vm.sourceDetail.value as SourceDetailUiState.Loaded).error)
    }

    @Test fun openingAnUnknownSourceReportsItGone() {
        val (vm, _) = vm()
        vm.openSource("nope")
        assertTrue(vm.sourceDetail.value is SourceDetailUiState.Error)
    }
}
