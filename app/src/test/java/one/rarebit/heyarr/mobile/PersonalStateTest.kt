package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.personalstate.PersonalStateClient
import one.rarebit.heyarr.mobile.personalstate.Unwrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private class EchoTransport(private val body: String) : HttpTransport {
    var lastUrl: String? = null; private set
    var lastAuth: String? = null; private set
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastUrl = url
        lastAuth = headers["Authorization"]
        return HttpResponse(200, body)
    }
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        HttpResponse(405, "")
}

class PersonalStateTest {

    @Test fun buildsSpaceUrls() {
        assertEquals("https://h.example/api/v1/spaces", PersonalStateClient.spacesUrl("https://h.example/"))
        assertEquals("https://h.example/api/v1/spaces/s1/keys", PersonalStateClient.keysUrl("https://h.example", "s1"))
        assertEquals("https://h.example/api/v1/spaces/s1/changes", PersonalStateClient.changesUrl("https://h.example", "s1"))
        assertEquals("https://h.example/api/v1/spaces/s1/snapshot", PersonalStateClient.snapshotUrl("https://h.example", "s1"))
    }

    @Test fun fetchesOpaqueChangesWithAuthAndDoesNotDecrypt() {
        val t = EchoTransport("OPAQUE-CIPHERTEXT-BYTES")
        val client = PersonalStateClient(t, "https://h.example", Credential.Session("tok"))
        val bytes = client.fetchChanges("s1")
        assertEquals("https://h.example/api/v1/spaces/s1/changes", t.lastUrl)
        assertEquals("Bearer tok", t.lastAuth)
        // The client carries bytes verbatim — it never decrypts them.
        assertEquals("OPAQUE-CIPHERTEXT-BYTES", String(bytes, Charsets.UTF_8))
    }

    @Test fun decryptIsFailClosedByDefault() {
        val t = EchoTransport("x")
        val client = PersonalStateClient(t, "https://h.example", Credential.Session("tok"))
        // The default Unwrapper.Unavailable refuses — the scaffold never fakes plaintext.
        assertThrows(NotImplementedError::class.java) {
            client.decryptOnDevice(PersonalStateClient.OpaqueChange("cipher".toByteArray()))
        }
    }

    @Test fun unavailableUnwrapperRefuses() {
        assertThrows(NotImplementedError::class.java) {
            Unwrapper.Unavailable.unwrap(ByteArray(0))
        }
    }
}
