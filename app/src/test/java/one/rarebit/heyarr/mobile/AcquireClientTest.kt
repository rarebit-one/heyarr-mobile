package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.search.AcquireClient
import one.rarebit.heyarr.mobile.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class PostCapturingTransport(private val status: Int, private val respBody: String = "") : HttpTransport {
    var lastUrl: String? = null; private set
    var lastBody: String? = null; private set
    var lastAuth: String? = null; private set
    var lastContentType: String? = null; private set
    override fun get(url: String, headers: Map<String, String>): HttpResponse = HttpResponse(405, "")
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
        lastUrl = url
        lastBody = body
        lastAuth = headers["Authorization"]
        lastContentType = contentType
        return HttpResponse(status, respBody)
    }
}

class AcquireClientTest {

    private val movie = SearchResult(workId = "w1", title = "Dune", type = "movie", year = 2021)
    private val series = SearchResult(workId = "w2", title = "Severance", type = "series")

    // ── One-off ("Get once") → the LIVE POST /api/v1/desired, monitor:false ──────

    @Test fun getOnceHitsDesiredRouteWithMonitorFalse() {
        val t = PostCapturingTransport(201, """{"id":"d-1"}""")
        val client = AcquireClient(t, "https://h.example", Credential.Session("tok"), "living-room")
        val result = client.getOnce(movie)

        assertEquals("https://h.example/api/v1/desired", t.lastUrl)
        assertEquals("application/json", t.lastContentType)
        assertEquals("Bearer tok", t.lastAuth)
        val body = t.lastBody!!
        assertTrue("carries work_id", body.contains("\"work_id\":\"w1\""))
        assertTrue("one-off ⇒ monitor:false", body.contains("\"monitor\":false"))
        assertTrue("carries the quality profile", body.contains("\"quality_profile\":\"living-room\""))
        assertTrue(result is AcquireClient.Result.Wanted)
        assertEquals("d-1", (result as AcquireClient.Result.Wanted).desiredId)
    }

    // ── Follow ("Subscribe") → the follow_source SEAM, POST /api/v1/followed ─────

    @Test fun followHitsFollowedRouteWithMonitorTrue() {
        val t = PostCapturingTransport(201, """{"id":"src-7"}""")
        val client = AcquireClient(t, "https://h.example", Credential.Session("tok"), "living-room")
        val result = client.follow(series)

        assertEquals("https://h.example/api/v1/followed", t.lastUrl)
        val body = t.lastBody!!
        assertTrue("carries source_id", body.contains("\"source_id\":\"w2\""))
        assertTrue("carries source_type", body.contains("\"source_type\":\"series\""))
        assertTrue("follow ⇒ monitor:true", body.contains("\"monitor\":true"))
        assertTrue("carries a backfill policy", body.contains("\"backfill\":"))
        assertTrue(result is AcquireClient.Result.Following)
        assertEquals("src-7", (result as AcquireClient.Result.Following).sourceId)
    }

    // ── The dispatch difference is the whole feature ─────────────────────────────

    @Test fun getOnceAndFollowGoToDifferentRoutes() {
        val once = PostCapturingTransport(201)
        val follow = PostCapturingTransport(201)
        AcquireClient(once, "https://h", Credential.Session("t"), "p").getOnce(movie)
        AcquireClient(follow, "https://h", Credential.Session("t"), "p").follow(series)
        assertEquals("https://h/api/v1/desired", once.lastUrl)
        assertEquals("https://h/api/v1/followed", follow.lastUrl)
        assertTrue(once.lastBody!!.contains("\"monitor\":false"))
        assertTrue(follow.lastBody!!.contains("\"monitor\":true"))
    }

    @Test fun nonSuccessStatusBecomesFailed() {
        val t = PostCapturingTransport(404)
        val result = AcquireClient(t, "https://h", Credential.Session("t"), "p").follow(series)
        assertTrue(result is AcquireClient.Result.Failed)
        assertEquals(404, (result as AcquireClient.Result.Failed).status)
    }

    @Test fun pureBodyBuildersAreCorrect() {
        val once = AcquireClient.oneOffWantBody(movie, "living-room")
        assertTrue(once.contains("\"monitor\":false"))
        val f = AcquireClient.followBody(series, "living-room", backfill = "full")
        assertTrue(f.contains("\"monitor\":true"))
        assertTrue(f.contains("\"backfill\":\"full\""))
    }
}
