package one.rarebit.heyarr.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.Want
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.library.WorkDetailClient
import one.rarebit.heyarr.mobile.library.WorkDetailUiState
import one.rarebit.heyarr.mobile.library.WorkDetailViewModel
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.search.FollowedSource
import one.rarebit.heyarr.mobile.search.FollowingClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The pure state transitions, then the VM over a scripted transport (everything Unconfined so it settles inline). */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkDetailViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()

    private val work = Work(id = "w1", title = "Dune", kind = "movie")
    private val want = Want(id = "d1", workId = "w1", monitor = true, state = "WANTED")
    private val asset = WorkAsset(id = "a1", editionId = "e1", blobHash = "blake3:aa")

    // ── Pure transitions ──────────────────────────────────────────────────────────

    @Test fun loadedTransitionsAreValueLevel() {
        val s = WorkDetailUiState.Loaded(work, assets = listOf(asset), wants = listOf(want))
        val busy = s.starting("d1")
        assertTrue("d1" in busy.busy)
        val noticed = busy.noticed("d1", "nope")
        assertTrue("d1" !in noticed.busy)
        assertEquals("nope", noticed.notices["d1"])
        assertTrue(noticed.wantRemoved("d1").wants.isEmpty())
        assertNull(noticed.wantRemoved("d1").notices["d1"])
        assertEquals(false, noticed.wantReplaced(want.copy(monitor = false)).wants.single().monitor)
        assertTrue(noticed.assetRemoved("a1").assets.isEmpty())
        assertEquals(listOf(asset), s.playable)
    }

    @Test fun sourceForJoinsOnWorkId() {
        val sources = listOf(FollowedSource(id = "s1", title = "x", workId = "w9"), FollowedSource(id = "s2", title = "y", workId = "w1"))
        assertEquals("s2", WorkDetailUiState.sourceFor("w1", sources)?.id)
        assertNull(WorkDetailUiState.sourceFor("w0", sources))
    }

    // ── The VM over a scripted node ───────────────────────────────────────────────

    private fun node(vararg extra: Pair<String, HttpResponse>) = RoutedTransport(
        mapOf(
            "GET /works/w1" to HttpResponse(200, """{"id":"w1","content_type":"movie","title":"Dune","year":2021,"work_key":"k","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z"}"""),
            "GET /assets?limit=200" to HttpResponse(200, """{"items":[{"id":"a1","edition_id":"e1","source_class":"managed","blob_hash":"blake3:aa","role":"primary","filename":"Dune.mkv","mime":"video/mp4","identification_source":"scan","created_at":"x","updated_at":"x"}]}"""),
            "GET /editions/e1" to HttpResponse(200, """{"id":"e1","work_id":"w1","label":"4K","edition_type":"release","attributes":{},"created_at":"x"}"""),
            "GET /blobs/blake3%3Aaa" to HttpResponse(200, """{"hash":"blake3:aa","size":100,"chunked":false,"chunk_manifest":"not_required","first_seen_at":"x"}"""),
            "GET /desired?work_id=w1&limit=200" to HttpResponse(200, """{"items":[{"id":"d1","scope":"work","work_id":"w1","quality_profile_id":"q","monitor":true,"acquisition":{"state":"WANTED","phase":"searching","managed":false,"content":"missing","placement":"n/a"},"created_at":"x","updated_at":"x"}]}"""),
            "GET /followed-sources" to HttpResponse(200, """{"followed_sources":[{"id":"s1","work_id":"w1","type":"tv_series","feed_ref":"tvdb:1","items_known":3,"items_archived":2,"health":"healthy","created_at":"x"}]}"""),
            *extra,
        ),
    )

    private fun vm(t: RoutedTransport): WorkDetailViewModel {
        val cred = Credential.Session("tok")
        return WorkDetailViewModel(
            work = work,
            library = LibraryClient(t, "https://h", cred),
            detail = WorkDetailClient(t, "https://h", cred),
            following = FollowingClient(t, "https://h", cred),
            io = dispatcher,
        )
    }

    @Test fun loadsHeaderAssetsWantsAndTheFollowedSource() {
        val s = vm(node()).state.value as WorkDetailUiState.Loaded
        assertEquals(2021, s.work.year)
        assertEquals("4K", s.assets.single().editionLabel)
        assertEquals(100L, s.assets.single().sizeBytes)
        assertEquals("WANTED", s.wants.single().state)
        assertEquals("s1", s.source?.id)
        assertNull(s.partialError)
    }

    @Test fun aFailedReadIsPartialNotFatal() {
        val s = vm(node("GET /assets?limit=200" to HttpResponse(500, ""))).state.value as WorkDetailUiState.Loaded
        assertEquals("Dune", s.work.title)
        assertTrue(s.assets.isEmpty())
        assertEquals(1, s.wants.size)
        assertTrue(s.partialError!!.startsWith("assets:"))
    }

    @Test fun cancelRemovesTheWantOn204() {
        val v = vm(node("DELETE /desired/d1" to HttpResponse(204, "")))
        v.cancelWant(want)
        val s = v.state.value as WorkDetailUiState.Loaded
        assertTrue(s.wants.isEmpty())
        assertTrue(s.busy.isEmpty())
    }

    @Test fun cancelOn403LeavesTheWantAndSurfacesTheReadOnlyHint() {
        val v = vm(node("DELETE /desired/d1" to HttpResponse(403, """{"status":403,"detail":"this token does not carry the write scope"}""")))
        v.cancelWant(want)
        val s = v.state.value as WorkDetailUiState.Loaded
        assertEquals(1, s.wants.size)
        assertEquals(WorkDetailClient.READ_ONLY_HINT, s.notices["d1"])
    }

    @Test fun pauseReplacesTheWantFromThePatchResponse() {
        val v = vm(node("PATCH /desired/d1" to HttpResponse(200, """{"id":"d1","scope":"work","work_id":"w1","quality_profile_id":"q","monitor":false,"created_at":"x","updated_at":"y"}""")))
        v.setMonitor(want, false)
        assertEquals(false, (v.state.value as WorkDetailUiState.Loaded).wants.single().monitor)
    }

    @Test fun retryQueuesAndSaysSo() {
        val v = vm(node("POST /desired/d1/reconcile" to HttpResponse(202, """{"job_id":"j"}""")))
        v.retry(want)
        assertEquals("Reconciliation queued.", (v.state.value as WorkDetailUiState.Loaded).notices["d1"])
    }

    @Test fun removeAssetDropsTheRowOn204AndKeepsItOnRefusal() {
        val ok = vm(node("DELETE /assets/a1" to HttpResponse(204, "")))
        ok.removeAsset(asset)
        assertTrue((ok.state.value as WorkDetailUiState.Loaded).assets.isEmpty())

        val refused = vm(node("DELETE /assets/a1" to HttpResponse(409, """{"status":409,"detail":"in use"}""")))
        refused.removeAsset(asset)
        val s = refused.state.value as WorkDetailUiState.Loaded
        assertEquals(1, s.assets.size)
        assertEquals("in use", s.notices["a1"])
    }
}
