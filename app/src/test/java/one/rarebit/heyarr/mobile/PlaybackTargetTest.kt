package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTargetTest {

    @Test fun authHeadersCarryTheCredential() {
        val target = PlaybackTarget(
            contentUrl = "https://h.example/api/v1/blobs/abc/content",
            credential = Credential.Session("tok"),
            isVideo = true,
        )
        assertEquals(mapOf("Authorization" to "Bearer tok"), target.authHeaders())
    }

    @Test fun deviceCredentialHeaderIsCarried() {
        val target = PlaybackTarget(
            contentUrl = "https://h.example/api/v1/blobs/abc/content",
            credential = Credential.Device(cert = "CERT", proof = "PROOF"),
            isVideo = false,
        )
        assertEquals("Device CERT~PROOF", target.authHeaders()["Authorization"])
    }

    @Test fun mimeDecidesVideoOverKind() {
        // A video MIME wins even if the kind says otherwise.
        assertTrue(PlaybackTarget.looksLikeVideo("video/mp4", "album"))
        // An audio MIME forces audio even for a video-ish kind.
        assertFalse(PlaybackTarget.looksLikeVideo("audio/mpeg", "movie"))
    }

    @Test fun kindDecidesWhenMimeAbsent() {
        assertTrue(PlaybackTarget.looksLikeVideo(null, "movie"))
        assertTrue(PlaybackTarget.looksLikeVideo(null, "Episode"))
        assertFalse(PlaybackTarget.looksLikeVideo(null, "album"))
        assertFalse(PlaybackTarget.looksLikeVideo(null, null))
    }
}
