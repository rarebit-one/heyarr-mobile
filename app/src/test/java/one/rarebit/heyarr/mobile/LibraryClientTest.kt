package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Test

private class CapturingTransport(private val body: String, private val status: Int = 200) : HttpTransport {
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

class LibraryClientTest {

    @Test fun buildsWorksUrl() {
        assertEquals("https://h.example/api/v1/works", LibraryClient.worksUrl("https://h.example/"))
        assertEquals("https://h.example/api/v1/works/abc", LibraryClient.workUrl("https://h.example", "abc"))
    }

    @Test fun sendsBearerHeaderAndParsesWorks() {
        val t = CapturingTransport("""[{"id":"w1","title":"Dune"}]""")
        val client = LibraryClient(t, "https://h.example", Credential.Session("tok-123"))
        val works = client.listWorks()
        assertEquals("https://h.example/api/v1/works", t.lastUrl)
        assertEquals("Bearer tok-123", t.lastAuth)
        assertEquals(1, works.size)
        assertEquals("Dune", works[0].title)
    }

    @Test fun sendsDeviceCredentialHeader() {
        val t = CapturingTransport("[]")
        val client = LibraryClient(t, "https://h.example", Credential.Device(cert = "CERT", proof = "PROOF"))
        client.listWorks()
        assertEquals("Device CERT~PROOF", t.lastAuth)
    }
}
