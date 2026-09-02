package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.playback.PlaybackClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A transport that records the last POST and returns a canned response. */
private class RecordingTransport(private val response: HttpResponse) : HttpTransport {
    var lastPostUrl: String? = null; private set
    var lastPostBody: String? = null; private set
    var lastPostAuth: String? = null; private set
    override fun get(url: String, headers: Map<String, String>): HttpResponse = HttpResponse(405, "")
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
        lastPostUrl = url
        lastPostBody = body
        lastPostAuth = headers["Authorization"]
        return response
    }
}

class PlaybackClientTest {

    @Test fun blobTargetPointsAtRangeCapableUrlUnderCredential() {
        val client = PlaybackClient(RecordingTransport(HttpResponse(200, "")), "https://h.example/", Credential.Session("tok"))
        val target = client.blobTarget("blake3:a617353faeec5b99c042ceb349976d8171e5b7efc6fb1924fbc8b0d605ad8c9e", isVideo = true, mimeType = "video/mp4")
        assertEquals("https://h.example/api/v1/blobs/blake3:a617353faeec5b99c042ceb349976d8171e5b7efc6fb1924fbc8b0d605ad8c9e/content", target.contentUrl)
        assertEquals("Bearer tok", target.authHeaders()["Authorization"])
        assertTrue(target.isVideo)
        assertEquals("video/mp4", target.mimeType)
    }

    @Test fun planPostsToPlanUrlWithAssetDeviceBodyAndAuth() {
        val t = RecordingTransport(
            HttpResponse(200, """{"decision":"DIRECT","content_url":"https://h.example/api/v1/blobs/xyz/content"}"""),
        )
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        val target = client.plan("asset-1", "device-1", isVideo = false)
        assertEquals("https://h.example/api/v1/playback/plan", t.lastPostUrl)
        assertEquals("""{"asset_id":"asset-1","device_id":"device-1"}""", t.lastPostBody)
        assertEquals("Bearer tok", t.lastPostAuth)
        assertEquals("https://h.example/api/v1/blobs/xyz/content", target?.contentUrl)
    }

    @Test fun planReturnsNullWhenNotDirect() {
        val t = RecordingTransport(HttpResponse(200, """{"decision":"TRANSCODE"}"""))
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        assertNull(client.plan("asset-1", "device-1", isVideo = true))
    }

    @Test fun planResolvesRelativeContentUrlAgainstBase() {
        val t = RecordingTransport(HttpResponse(200, """{"decision":"DIRECT","content_url":"/api/v1/blobs/rel/content"}"""))
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        val target = client.plan("a", "d", isVideo = false)
        assertEquals("https://h.example/api/v1/blobs/rel/content", target?.contentUrl)
    }

    @Test fun planThrowsOnNon200() {
        val t = RecordingTransport(HttpResponse(403, "forbidden"))
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        try {
            client.plan("a", "d", isVideo = false)
            throw AssertionError("expected an exception on HTTP 403")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("403"))
        }
    }

    @Test fun planUrlBuilder() {
        assertEquals("https://h.example/api/v1/playback/plan", PlaybackClient.planUrl("https://h.example/"))
    }
}
