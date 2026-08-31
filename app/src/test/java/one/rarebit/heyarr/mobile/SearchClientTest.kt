package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.search.SearchClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class SearchPostTransport(private val respBody: String, private val status: Int = 200) : HttpTransport {
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

class SearchClientTest {

    @Test fun buildsSearchUrlToTheSearchRoute() {
        // Source-agnostic content search rides POST /api/v1/search (no ?q=, no indexer).
        assertEquals("https://h.example/api/v1/search", SearchClient.searchUrl("https://h.example/"))
    }

    @Test fun bodyCarriesQueryOnly() {
        assertEquals("""{"query":"blade runner"}""", SearchClient.searchBody("blade runner"))
    }

    @Test fun bodyCarriesContentTypeAndLimitWhenGiven() {
        val body = SearchClient.searchBody("dune", "movie", 10)
        assertTrue(body.contains(""""query":"dune""""))
        assertTrue(body.contains(""""content_type":"movie""""))
        assertTrue(body.contains(""""limit":10"""))
    }

    @Test fun sendsAuthPostsAndParsesResults() {
        // Live response shape: { "works": [ { work_id, content_type, title, year } ] }.
        val resp = """{"works":[{"work_id":"w1","content_type":"movie","title":"Dune","year":2021}]}"""
        val t = SearchPostTransport(resp)
        val client = SearchClient(t, "https://h.example", Credential.Session("tok-9"))
        val results = client.search("dune")

        assertEquals("https://h.example/api/v1/search", t.lastUrl)
        assertEquals("application/json", t.lastContentType)
        assertEquals("Bearer tok-9", t.lastAuth)
        assertTrue(t.lastBody!!.contains(""""query":"dune""""))
        assertEquals(1, results.size)
        assertEquals("w1", results[0].workId)
        assertEquals("Dune", results[0].title)
        assertEquals("movie", results[0].type)
        assertEquals(2021, results[0].year)
    }

    @Test fun blankQueryWithNoContentTypeReturnsEmptyWithoutRoundTrip() {
        val t = SearchPostTransport("SHOULD-NOT-BE-CALLED")
        val client = SearchClient(t, "https://h.example", Credential.Session("t"))
        assertEquals(0, client.search("   ").size)
        assertNull(t.lastUrl)
    }

    @Test fun contentTypeOnlySearchStillRoundTrips() {
        val t = SearchPostTransport("""{"works":[]}""")
        val client = SearchClient(t, "https://h.example", Credential.Session("t"))
        client.search("", contentType = "series")
        assertEquals("https://h.example/api/v1/search", t.lastUrl)
        assertTrue(t.lastBody!!.contains(""""content_type":"series""""))
    }
}
