package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.device.EnrolClient
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrolClientTest {

    private class Fake(private val byUrl: Map<String, Int>) : HttpTransport {
        val posts = ArrayList<Triple<String, String?, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
        override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
            posts += Triple(url, body, headers)
            return HttpResponse(byUrl[url] ?: 404, """{"detail":"nope"}""")
        }
    }

    private val base = "http://h"

    @Test fun selfEnrolRouteWinsWhenPresent() {
        val f = Fake(mapOf("http://h/enrol" to 201))
        val out = EnrolClient(f, base).register("CERT", "PROOF", "phone", Credential.Session("t"))
        assertEquals(EnrolClient.Outcome.Registered("POST /enrol"), out)
        assertEquals("""{"cert":"CERT","proof":"PROOF","name":"phone"}""", f.posts.single().second)
    }

    @Test fun fallsBackToAdminRouteAndReportsNeedsAdminOn403() {
        val f = Fake(mapOf("http://h/enrol" to 404, "http://h/api/v1/identities/devices" to 403))
        val out = EnrolClient(f, base).register("CERT", "PROOF", "phone", Credential.Session("t"))
        assertTrue(out is EnrolClient.Outcome.NeedsAdmin)
        assertEquals(2, f.posts.size)
        assertEquals("Bearer t", f.posts[1].third["Authorization"])
        assertEquals("""{"cert":"CERT","name":"phone"}""", f.posts[1].second)
    }

    @Test fun adminRouteSuccessRegisters() {
        val f = Fake(mapOf("http://h/enrol" to 405, "http://h/api/v1/identities/devices" to 201))
        assertEquals(
            EnrolClient.Outcome.Registered("POST /api/v1/identities/devices"),
            EnrolClient(f, base).register("C", "P", "n", Credential.Session("t")),
        )
    }

    @Test fun otherSelfEnrolFailureSurfacesDetail() {
        val f = Fake(mapOf("http://h/enrol" to 400))
        assertEquals(EnrolClient.Outcome.Failed("nope"), EnrolClient(f, base).register("C", "P", "n", null))
    }
}
