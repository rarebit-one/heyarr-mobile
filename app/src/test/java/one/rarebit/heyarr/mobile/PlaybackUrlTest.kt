package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.playback.PlaybackClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RangeTransport(private val status: Int) : HttpTransport {
    var lastRange: String? = null; private set
    var lastAuth: String? = null; private set
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastRange = headers["Range"]
        lastAuth = headers["Authorization"]
        return HttpResponse(status, "")
    }
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        HttpResponse(405, "")
}

class PlaybackUrlTest {

    @Test fun blobContentUrlIsRangeCapablePath() {
        assertEquals(
            "https://h.example/api/v1/blobs/deadbeef/content",
            PlaybackClient.blobContentUrl("https://h.example/", "deadbeef"),
        )
    }

    @Test fun playbackUrl() {
        assertEquals("https://h.example/api/v1/playback", PlaybackClient.playbackUrl("https://h.example"))
    }

    @Test fun probeSendsRangeAndAuthAndReadsPartialContent() {
        val t = RangeTransport(status = 206)
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        val result = client.probe("abc123")
        assertEquals("bytes=0-0", t.lastRange)
        assertEquals("Bearer tok", t.lastAuth)
        assertTrue(result.acceptsRanges)
    }

    @Test fun probeReportsNoRangeSupportOn200() {
        val t = RangeTransport(status = 200)
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        assertTrue(!client.probe("abc123").acceptsRanges)
    }
}
