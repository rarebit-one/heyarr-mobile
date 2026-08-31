package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.search.FollowedSourcesJson
import one.rarebit.heyarr.mobile.search.FollowingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ListingTransport(private val body: String, private val status: Int = 200) : HttpTransport {
    var lastGetUrl: String? = null; private set
    var lastPostUrl: String? = null; private set
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastGetUrl = url
        return HttpResponse(status, body)
    }
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
        lastPostUrl = url
        return HttpResponse(204, "")
    }
}

class FollowingClientTest {

    @Test fun buildsFollowedAndUnfollowUrls() {
        assertEquals("https://h.example/api/v1/followed", FollowingClient.followedUrl("https://h.example/"))
        assertEquals(
            "https://h.example/api/v1/followed/src-1/unfollow",
            FollowingClient.unfollowUrl("https://h.example", "src-1"),
        )
    }

    @Test fun listsFollowedSourcesWithCounters() {
        val body = """
            [
              {"id":"s1","title":"The Daily","content_type":"podcast","items_known":10,"items_archived":8,"health":"ok"},
              {"id":"s2","title":"Severance","content_type":"series"}
            ]
        """.trimIndent()
        val client = FollowingClient(ListingTransport(body), "https://h.example", Credential.Session("t"))
        val sources = client.list()
        assertEquals(2, sources.size)
        assertEquals("The Daily", sources[0].title)
        assertEquals("podcast", sources[0].type)
        assertEquals(10, sources[0].itemsKnown)
        assertEquals(8, sources[0].itemsArchived)
        assertEquals("ok", sources[0].health)
        assertEquals("Severance", sources[1].title)
        assertEquals(null, sources[1].itemsKnown)
    }

    @Test fun listHitsFollowedRoute() {
        val t = ListingTransport("[]")
        FollowingClient(t, "https://h.example", Credential.Session("t")).list()
        assertEquals("https://h.example/api/v1/followed", t.lastGetUrl)
    }

    @Test fun unfollowPostsToUnfollowRoute() {
        val t = ListingTransport("[]")
        val ok = FollowingClient(t, "https://h.example", Credential.Session("t")).unfollow("s9")
        assertTrue(ok)
        assertEquals("https://h.example/api/v1/followed/s9/unfollow", t.lastPostUrl)
    }

    @Test fun parserToleratesEnvelopeAndEmpty() {
        assertEquals(1, FollowedSourcesJson.parse("""{"followed":[{"id":"a","title":"A"}]}""").size)
        assertEquals(0, FollowedSourcesJson.parse("""{"error":"x"}""").size)
    }
}
