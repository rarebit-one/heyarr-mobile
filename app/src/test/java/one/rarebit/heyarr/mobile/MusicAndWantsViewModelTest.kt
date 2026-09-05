package one.rarebit.heyarr.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import one.rarebit.heyarr.mobile.acquisition.WantDetailViewModel
import one.rarebit.heyarr.mobile.acquisition.WantsClient
import one.rarebit.heyarr.mobile.acquisition.WantsViewModel
import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.home.RowState
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.WorkDetailClient
import one.rarebit.heyarr.mobile.music.AlbumViewModel
import one.rarebit.heyarr.mobile.music.ArtistViewModel
import one.rarebit.heyarr.mobile.music.ArtistsViewModel
import one.rarebit.heyarr.mobile.music.MusicClient
import one.rarebit.heyarr.mobile.net.HttpResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()
    private val base = "https://h.example"
    private val cred = Credential.Session("t")

    @Test fun artistsFallBackToClientGroupingOnAnOlderNode() {
        val t = SubstringTransport(listOf(
            "GET /artists" to HttpResponse(404, ""),
            "GET /works?limit=200&content_type=music" to HttpResponse(200, worksPage(
                workJson("w4", "Album One", "music", extra = ""","attributes":{"artist":"Artist A"}"""),
                workJson("w5", "Album Two", "music", extra = ""","attributes":{"artist":"Artist A"}"""),
                workJson("w6", "Album Three", "music", extra = ""","attributes":{"artist":"Artist B"}"""),
            )),
        ))
        val vm = ArtistsViewModel(MusicClient(t, base, cred), dispatcher)
        val artists = (vm.state.value as RowState.Loaded).items
        assertEquals(listOf("Artist A" to 2, "Artist B" to 1), artists.map { it.name to it.workCount })
    }

    @Test fun artistsReadTheServersGrouping() {
        val t = SubstringTransport(listOf("GET /artists" to HttpResponse(200, """{"items":[{"name":"Artist Z","work_count":3,"artwork":null}]}""")))
        val vm = ArtistsViewModel(MusicClient(t, base, cred), dispatcher)
        assertEquals("Artist Z", (vm.state.value as RowState.Loaded).items.single().name)
        assertEquals(1, t.calls.size)
    }

    @Test fun anArtistsAlbumsAreFilteredEvenWhenTheNodeIgnoresTheFacet() {
        val t = SubstringTransport(listOf(
            "content_type=music" to HttpResponse(200, worksPage(
                workJson("w4", "Mine", "music", extra = ""","attributes":{"artist":"Artist A"}"""),
                workJson("w6", "Theirs", "music", extra = ""","attributes":{"artist":"Artist B"}"""),
            )),
        ))
        val vm = ArtistViewModel("Artist A", MusicClient(t, base, cred), dispatcher)
        assertEquals(listOf("w4"), (vm.state.value as RowState.Loaded).items.map { it.id })
        assertTrue(t.calls.single().contains("artist=Artist+A"))
    }

    @Test fun anAlbumLoadsItsWorkAndPlayableTracks() {
        val t = SubstringTransport(listOf(
            "GET /works/w4/assets" to HttpResponse(200, """{"items":[
                {"id":"a2","edition_id":"e","filename":"02 - Two.flac","mime":"audio/flac","blob_hash":"blake3:2","edition_label":"flac"},
                {"id":"a1","edition_id":"e","filename":"01 - One.flac","mime":"audio/flac","blob_hash":"blake3:1","edition_label":"flac"},
                {"id":"a3","edition_id":"e","filename":"cover.jpg","mime":"image/jpeg","blob_hash":"blake3:3","role":"artwork"}]}"""),
            "GET /works/w4" to HttpResponse(200, workJson("w4", "Album One", "music", extra = ""","attributes":{"artist":"Artist A"}""")),
        ))
        val vm = AlbumViewModel("w4", "hint", LibraryClient(t, base, cred), WorkDetailClient(t, base, cred), dispatcher)
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals("Album One", s.work!!.title)
        assertEquals("Artist A", s.work!!.artist)
        assertEquals(listOf("a1", "a2"), s.tracks.map { it.id })
    }

    @Test fun anAlbumWhoseReadsFailKeepsTheHintAndSaysWhy() {
        val t = SubstringTransport(listOf("GET /works/w4" to HttpResponse(500, "")))
        val vm = AlbumViewModel("w4", "Hinted", LibraryClient(t, base, cred), WorkDetailClient(t, base, cred), dispatcher)
        val s = vm.state.value
        assertEquals("Hinted", s.work!!.title)
        assertTrue(s.error!!.isNotBlank())
        assertEquals(0, s.tracks.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WantsViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()
    private val base = "https://h.example"
    private val cred = Credential.Session("t")

    private val wants = """{"items":[
        {"id":"i1","scope":"work","work_id":"w1","monitor":true,"reason":"the good copy","acquisition":{"state":"SEARCHING","phase":"searching"},"created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-02T00:00:00Z"},
        {"id":"i2","scope":"work","work_id":"w9","monitor":false,"acquisition":{"state":"WANTED"},"created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:00Z"}]}"""

    @Test fun theDashboardResolvesTitlesAndSurvivesAMissingWork() {
        val t = SubstringTransport(listOf(
            "GET /desired?limit=200" to HttpResponse(200, wants),
            "GET /works/w1" to HttpResponse(200, workJson("w1", "Arrival", "movie")),
            "GET /works/w9" to HttpResponse(404, ""),
        ))
        val vm = WantsViewModel(WantsClient(t, base, cred), LibraryClient(t, base, cred), dispatcher)
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf("i1", "i2"), s.wants.map { it.id })
        assertEquals("Arrival", s.titleOf(s.wants[0]))
        assertEquals("w9", s.titleOf(s.wants[1]))
    }

    @Test fun theDetailShowsCandidatesAndReloadsAfterAnActionWithTheNodesRefusal() {
        val t = SubstringTransport(listOf(
            "GET /desired?limit=200" to HttpResponse(200, wants),
            "GET /works/w1" to HttpResponse(200, workJson("w1", "Arrival", "movie")),
            "GET /desired/i1/candidates" to HttpResponse(200, """{"selected":null,"candidates":[{"candidate_id":"tiny","title":"480p","accepted":false,"score":0}]}"""),
            "POST /desired/i1/select" to HttpResponse(400, """{"detail":"candidate tiny is rejected by resolution.gte"}"""),
            "POST /desired/i1/search" to HttpResponse(202, "{}"),
        ))
        val vm = WantDetailViewModel("i1", WantsClient(t, base, cred), WorkDetailClient(t, base, cred), LibraryClient(t, base, cred), dispatcher)
        val s0 = vm.state.value
        assertEquals("Arrival", s0.title)
        assertEquals(1, s0.candidates.candidates.size)
        assertFalse(s0.gone)

        val loads = t.calls.count { it.contains("/desired?limit") }
        vm.select(s0.candidates.candidates[0])
        val s1 = vm.state.value
        assertTrue(s1.notice!!.contains("resolution.gte"))
        assertFalse(s1.busy)
        assertEquals("reloaded after the action", loads + 1, t.calls.count { it.contains("/desired?limit") })

        vm.searchAgain()
        assertEquals("Search queued", vm.state.value.notice)
    }

    @Test fun aWantThatVanishedIsGone() {
        val t = SubstringTransport(listOf("GET /desired?limit=200" to HttpResponse(200, """{"items":[]}""")))
        val vm = WantDetailViewModel("i1", WantsClient(t, base, cred), WorkDetailClient(t, base, cred), LibraryClient(t, base, cred), dispatcher)
        assertTrue(vm.state.value.gone)
        assertNull(vm.state.value.want)
    }
}
