package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.search.SearchClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class GetCapturingTransport(private val body: String, private val status: Int = 200) : HttpTransport {
    var lastUrl: String? = null; private set
    var lastAuth: String? = null; private set
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastUrl = url
        lastAuth = headers["Authorization"]
        return HttpResponse(status, body)
    }
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        HttpResponse(405, "")
}

class SearchClientTest {

    @Test fun buildsSearchUrlWithEncodedQuery() {
        // Source-agnostic content search rides GET /api/v1/works?q=…
        assertEquals(
            "https://h.example/api/v1/works?q=blade+runner",
            SearchClient.searchUrl("https://h.example/", "blade runner"),
        )
    }

    @Test fun appendsContentTypeWhenGiven() {
        val url = SearchClient.searchUrl("https://h.example", "dune", "movie")
        assertTrue(url.contains("q=dune"))
        assertTrue(url.contains("content_type=movie"))
    }

    @Test fun sendsAuthAndParsesResults() {
        val t = GetCapturingTransport("""[{"id":"w1","title":"Dune","content_type":"movie","year":2021}]""")
        val client = SearchClient(t, "https://h.example", Credential.Session("tok-9"))
        val results = client.search("dune")
        assertEquals("https://h.example/api/v1/works?q=dune", t.lastUrl)
        assertEquals("Bearer tok-9", t.lastAuth)
        assertEquals(1, results.size)
        assertEquals("Dune", results[0].title)
        assertEquals(2021, results[0].year)
    }

    @Test fun blankQueryReturnsEmptyWithoutRoundTrip() {
        val t = GetCapturingTransport("SHOULD-NOT-BE-CALLED")
        val client = SearchClient(t, "https://h.example", Credential.Session("t"))
        assertEquals(0, client.search("   ").size)
        assertEquals(null, t.lastUrl)
    }
}
