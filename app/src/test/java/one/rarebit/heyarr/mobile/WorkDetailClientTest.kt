package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.WorkDetailClient
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scripted transport: each URL (path + query) maps to a canned response; every call
 * is recorded so tests can assert the exact routes, methods, bodies and auth.
 */
internal class RoutedTransport(private val routes: Map<String, HttpResponse>) : HttpTransport {
    val calls = ArrayList<Triple<String, String, String?>>() // method, url, body
    var lastAuth: String? = null; private set

    private fun answer(method: String, url: String, body: String?, headers: Map<String, String>): HttpResponse {
        calls.add(Triple(method, url, body))
        lastAuth = headers["Authorization"]
        val path = url.substringAfter("/api/v1")
        return routes["$method $path"] ?: routes[path] ?: HttpResponse(404, """{"status":404,"detail":"no such route in the fake"}""")
    }

    override fun get(url: String, headers: Map<String, String>) = answer("GET", url, null, headers)
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) = answer("POST", url, body, headers)
    override fun delete(url: String, headers: Map<String, String>) = answer("DELETE", url, null, headers)
    override fun patch(url: String, body: String?, contentType: String?, headers: Map<String, String>) = answer("PATCH", url, body, headers)
}

class WorkDetailClientTest {

    private val base = "https://h.example"
    private val cred = Credential.Session("tok")

    @Test fun buildsTheLiveRoutes() {
        assertEquals("$base/api/v1/assets?limit=200", WorkDetailClient.assetsUrl(base))
        assertEquals("$base/api/v1/assets?limit=200&cursor=a%2Fb", WorkDetailClient.assetsUrl(base, "a/b"))
        assertEquals("$base/api/v1/assets/a1", WorkDetailClient.assetUrl(base, "a1"))
        assertEquals("$base/api/v1/editions/e1", WorkDetailClient.editionUrl(base, "e1"))
        assertEquals("$base/api/v1/blobs/blake3%3Aab", WorkDetailClient.blobUrl(base, "blake3:ab"))
        assertEquals("$base/api/v1/desired?work_id=w1&limit=200", WorkDetailClient.wantsUrl(base, "w1"))
        assertEquals("$base/api/v1/desired/d1", WorkDetailClient.wantUrl(base, "d1"))
        assertEquals("""{"monitor":false}""", WorkDetailClient.monitorBody(false))
        assertEquals("$base/api/v1/works?limit=200", LibraryClient.worksUrl(base, null))
        assertEquals("$base/api/v1/works?limit=200&cursor=c1", LibraryClient.worksUrl(base, "c1"))
    }

    @Test fun assetsForWorkPagesTheCollectionAndJoinsThroughEditions() {
        // No per-work asset route exists: the client walks /assets and resolves each
        // distinct edition to its work, keeping only this work's, with label + size.
        val t = RoutedTransport(
            mapOf(
                "GET /assets?limit=200" to HttpResponse(200, """{"items":[{"id":"a1","edition_id":"e1","source_class":"managed","blob_hash":"blake3:aa","role":"primary","filename":"one.mkv","mime":"video/x-matroska","identification_source":"scan","created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-01T00:00:00Z"}],"next_cursor":"p2"}"""),
                "GET /assets?limit=200&cursor=p2" to HttpResponse(200, """{"items":[
                    {"id":"a2","edition_id":"e2","source_class":"managed","blob_hash":"blake3:bb","role":"primary","filename":"other.mkv","identification_source":"scan","created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-01T00:00:00Z"},
                    {"id":"a3","edition_id":"e1","source_class":"linked","blob_hash":null,"role":"subtitle","filename":"one.srt","identification_source":"scan","created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-01T00:00:00Z"}
                ]}"""),
                "GET /editions/e1" to HttpResponse(200, """{"id":"e1","work_id":"w1","label":"1080p","edition_type":"release","attributes":{},"created_at":"2026-09-01T00:00:00Z"}"""),
                "GET /editions/e2" to HttpResponse(200, """{"id":"e2","work_id":"w2","label":"x","edition_type":"release","attributes":{},"created_at":"2026-09-01T00:00:00Z"}"""),
                "GET /blobs/blake3%3Aaa" to HttpResponse(200, """{"hash":"blake3:aa","size":2048,"mime":"video/x-matroska","chunked":false,"chunk_manifest":"not_required","first_seen_at":"2026-09-01T00:00:00Z"}"""),
            ),
        )
        val assets = WorkDetailClient(t, base, cred).assetsForWork("w1")
        assertEquals(listOf("a1", "a3"), assets.map { it.id })
        assertEquals("1080p", assets[0].editionLabel)
        assertEquals(2048L, assets[0].sizeBytes)
        assertEquals(null, assets[1].sizeBytes)
        // e1 resolved once (cached), e2 once, blob read once for the one hashed asset of ours.
        assertEquals(1, t.calls.count { it.second.endsWith("/editions/e1") })
        assertEquals(1, t.calls.count { it.second.endsWith("/editions/e2") })
        assertEquals(1, t.calls.count { it.second.contains("/blobs/") })
        assertEquals("Bearer tok", t.lastAuth)
    }

    @Test fun wantsForWorkFiltersByWorkIdAndIsRecentFirst() {
        val t = RoutedTransport(
            mapOf(
                "GET /desired?work_id=w1&limit=200" to HttpResponse(200, """{"items":[
                    {"id":"old","scope":"work","work_id":"w1","quality_profile_id":"q","monitor":true,"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"},
                    {"id":"new","scope":"work","work_id":"w1","quality_profile_id":"q","monitor":true,"created_at":"2026-02-01T00:00:00Z","updated_at":"2026-02-01T00:00:00Z"}
                ]}"""),
            ),
        )
        assertEquals(listOf("new", "old"), WorkDetailClient(t, base, cred).wantsForWork("w1").map { it.id })
    }

    @Test fun writesHitTheRealRoutesWithTheRightMethods() {
        val t = RoutedTransport(
            mapOf(
                "DELETE /desired/d1" to HttpResponse(204, ""),
                "PATCH /desired/d1" to HttpResponse(200, """{"id":"d1","scope":"work","work_id":"w1","quality_profile_id":"q","monitor":false,"created_at":"x","updated_at":"y"}"""),
                "POST /desired/d1/reconcile" to HttpResponse(202, """{"desired_item_id":"d1","job_id":"j1","status":"queued"}"""),
                "POST /desired/d1/search" to HttpResponse(202, """{"job_id":"j2"}"""),
                "DELETE /assets/a1" to HttpResponse(204, ""),
            ),
        )
        val c = WorkDetailClient(t, base, cred)
        assertTrue(c.cancelWant("d1") is WorkDetailClient.Outcome.Done)
        assertTrue(c.setMonitor("d1", false) is WorkDetailClient.Outcome.Done)
        assertTrue(c.reconcile("d1") is WorkDetailClient.Outcome.Done)
        assertTrue(c.searchAgain("d1") is WorkDetailClient.Outcome.Done)
        assertTrue(c.removeAsset("a1") is WorkDetailClient.Outcome.Done)
        assertEquals(
            listOf("DELETE /desired/d1", "PATCH /desired/d1", "POST /desired/d1/reconcile", "POST /desired/d1/search", "DELETE /assets/a1"),
            t.calls.map { it.first + " " + it.second.substringAfter("/api/v1") },
        )
        assertEquals("""{"monitor":false}""", t.calls[1].third)
    }

    @Test fun a403IsTheHonestReadOnlyHintAndA400CarriesTheDetail() {
        val t = RoutedTransport(
            mapOf(
                "DELETE /desired/d1" to HttpResponse(403, """{"status":403,"detail":"this token does not carry the write scope"}"""),
                "DELETE /assets/a1" to HttpResponse(404, """{"status":404,"detail":"asset not found"}"""),
                "POST /desired/d1/reconcile" to HttpResponse(500, """{"status":500}"""),
            ),
        )
        val c = WorkDetailClient(t, base, cred)
        val ro = c.cancelWant("d1")
        assertTrue(ro is WorkDetailClient.Outcome.ReadOnly)
        assertEquals(WorkDetailClient.READ_ONLY_HINT, (ro as WorkDetailClient.Outcome.ReadOnly).message)
        val gone = c.removeAsset("a1")
        assertTrue(gone is WorkDetailClient.Outcome.Refused)
        assertEquals("asset not found", (gone as WorkDetailClient.Outcome.Refused).message)
        val boom = c.reconcile("d1")
        assertTrue(boom is WorkDetailClient.Outcome.Failed)
        assertEquals("retry failed: HTTP 500", (boom as WorkDetailClient.Outcome.Failed).message)
    }

    @Test fun libraryListFollowsNextCursorAndOrdersRecentFirst() {
        val t = RoutedTransport(
            mapOf(
                "GET /works?limit=200" to HttpResponse(200, """{"items":[{"id":"a","title":"A","updated_at":"2026-01-01T00:00:00Z"}],"next_cursor":"n"}"""),
                "GET /works?limit=200&cursor=n" to HttpResponse(200, """{"items":[{"id":"b","title":"B","updated_at":"2026-05-01T00:00:00Z"}]}"""),
            ),
        )
        assertEquals(listOf("b", "a"), LibraryClient(t, base, cred).listWorks().map { it.id })
        assertEquals(2, t.calls.size)
    }
}
