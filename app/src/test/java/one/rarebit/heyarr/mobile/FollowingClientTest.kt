package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.search.FollowedSourcesJson
import one.rarebit.heyarr.mobile.search.FollowingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FollowingTransport(
    private val getBody: String = "[]",
    private val getStatus: Int = 200,
    private val deleteStatus: Int = 204,
    private val deleteBody: String = "",
) : HttpTransport {
    var lastGetUrl: String? = null; private set
    var lastDeleteUrl: String? = null; private set
    var lastDeleteAuth: String? = null; private set
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastGetUrl = url
        return HttpResponse(getStatus, getBody)
    }
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        HttpResponse(405, "")
    override fun delete(url: String, headers: Map<String, String>): HttpResponse {
        lastDeleteUrl = url
        lastDeleteAuth = headers["Authorization"]
        return HttpResponse(deleteStatus, deleteBody)
    }
}

class FollowingClientTest {

    @Test fun buildsFollowedAndUnfollowUrls() {
        assertEquals(
            "https://h.example/api/v1/followed-sources",
            FollowingClient.followedUrl("https://h.example/"),
        )
        assertEquals(
            "https://h.example/api/v1/followed-sources/src-1?keep_archive=true",
            FollowingClient.unfollowUrl("https://h.example", "src-1"),
        )
        assertEquals(
            "https://h.example/api/v1/followed-sources/src-1?keep_archive=false",
            FollowingClient.unfollowUrl("https://h.example", "src-1", keepArchive = false),
        )
    }

    @Test fun listsFollowedSourcesFromEnvelopeWithCounters() {
        // Live shape: { "followed_sources": [ FollowedSourceView ] }; view carries work_id, no title.
        val body = """
            {"followed_sources":[
              {"id":"s1","work_id":"w1","type":"tv_series","items_known":10,"items_archived":8,"health":"healthy"},
              {"id":"s2","work_id":"w2","type":"tv_series","health":"unknown"}
            ]}
        """.trimIndent()
        val client = FollowingClient(FollowingTransport(getBody = body), "https://h.example", Credential.Session("t"))
        val sources = client.list()
        assertEquals(2, sources.size)
        assertEquals("w1", sources[0].workId)
        assertEquals("w1", sources[0].title) // no title in the view → falls back to work_id
        assertEquals("tv_series", sources[0].type)
        assertEquals(10, sources[0].itemsKnown)
        assertEquals(8, sources[0].itemsArchived)
        assertEquals("healthy", sources[0].health)
        assertEquals(null, sources[1].itemsKnown)
    }

    @Test fun listHitsFollowedSourcesRoute() {
        val t = FollowingTransport(getBody = "[]")
        FollowingClient(t, "https://h.example", Credential.Session("t")).list()
        assertEquals("https://h.example/api/v1/followed-sources", t.lastGetUrl)
    }

    @Test fun unfollowDeletesWithKeepArchiveTrueAndReports204() {
        val t = FollowingTransport(deleteStatus = 204)
        val result = FollowingClient(t, "https://h.example", Credential.Session("tok")).unfollow("s9")
        assertEquals(FollowingClient.UnfollowResult.Removed, result)
        assertEquals("https://h.example/api/v1/followed-sources/s9?keep_archive=true", t.lastDeleteUrl)
        assertEquals("Bearer tok", t.lastDeleteAuth)
    }

    @Test fun keepArchiveFalseRefusalSurfacesServerDetail() {
        val detail = "removing the archive is not implemented yet — Phase 1 unfollow stops polling " +
            "and keeps what was archived (keep_archive defaults to true)"
        val t = FollowingTransport(deleteStatus = 400, deleteBody = """{"status":400,"detail":"$detail"}""")
        val result = FollowingClient(t, "https://h.example", Credential.Session("t"))
            .unfollow("s9", keepArchive = false)
        assertTrue(result is FollowingClient.UnfollowResult.Refused)
        assertEquals(detail, (result as FollowingClient.UnfollowResult.Refused).message)
    }

    @Test fun unfollowFrom403ReadScopedSessionSurfacesEnrolHint() {
        // GET list works from the read session; DELETE is write → 403.
        val t = FollowingTransport(deleteStatus = 403, deleteBody = """{"status":403,"detail":"this token does not carry the write scope"}""")
        val result = FollowingClient(t, "https://h", Credential.Session("t")).unfollow("s9")
        assertTrue(result is FollowingClient.UnfollowResult.Refused)
        assertEquals(
            FollowingClient.READ_ONLY_UNFOLLOW_HINT,
            (result as FollowingClient.UnfollowResult.Refused).message,
        )
    }

    @Test fun parserToleratesBareArrayAndEmpty() {
        assertEquals(1, FollowedSourcesJson.parse("""[{"id":"a","work_id":"w"}]""").size)
        assertEquals(0, FollowedSourcesJson.parse("""{"error":"x"}""").size)
    }
}
