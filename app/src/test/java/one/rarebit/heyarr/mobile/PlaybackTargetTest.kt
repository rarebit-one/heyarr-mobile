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

    // ── Restart-seek on a transcoded stream (#433, ADR-0069) ───────────────────────

    private val streamBase = "https://h.example/api/v1/playback/stream/blake3:abcdef.sig-Ttok_123"

    @Test fun streamUrlAppendsStartWithoutTouchingTheToken() {
        // Zero (or less) → the base, untouched.
        assertEquals(streamBase, PlaybackTarget.streamUrl(streamBase, 0.0))
        assertEquals(streamBase, PlaybackTarget.streamUrl(streamBase, -5.0))
        // A positive offset appends ?start= — and the token's ':' is NOT percent-encoded
        // (the #16 blake3%3A trap): the path is on the wire verbatim.
        val at90 = PlaybackTarget.streamUrl(streamBase, 90.0)
        assertEquals("$streamBase?start=90", at90)
        assertTrue(at90.contains("/stream/blake3:abcdef.sig-Ttok_123"))
        assertFalse(at90.contains("%3A"))
    }

    @Test fun streamUrlUsesAmpersandWhenABaseAlreadyHasAQuery() {
        assertEquals("$streamBase?x=1&start=12", PlaybackTarget.streamUrl("$streamBase?x=1", 12.0))
    }

    @Test fun formatSecondsIsIntegerWhenWholeElseTrimmed() {
        assertEquals("90", PlaybackTarget.formatSeconds(90.0))
        assertEquals("12.5", PlaybackTarget.formatSeconds(12.5))
        assertEquals("12.25", PlaybackTarget.formatSeconds(12.25))
        assertEquals("0", PlaybackTarget.formatSeconds(0.0))
    }

    @Test fun atStreamStartRebuildsOffTheBaseAndClampsAtZero() {
        val t = PlaybackTarget(
            contentUrl = streamBase,
            credential = Credential.Session("tok"),
            isVideo = true,
            seekable = false,
            origin = PlaybackTarget.Origin.STREAM,
            restartSeekable = true,
            streamBaseUrl = streamBase,
        )
        val at60 = t.atStreamStart(60.0)
        assertEquals("$streamBase?start=60", at60.contentUrl)
        assertEquals(60.0, at60.streamStartSeconds, 0.0)
        // A second seek builds off the BASE, never stacking ?start on ?start.
        val at120 = at60.atStreamStart(120.0)
        assertEquals("$streamBase?start=120", at120.contentUrl)
        // Negative clamps to zero (→ the bare base).
        assertEquals(streamBase, at60.atStreamStart(-10.0).contentUrl)
    }

    @Test fun atStreamStartIsANoOpWithoutABaseUrl() {
        val t = PlaybackTarget(contentUrl = "https://h/x", credential = Credential.Session("tok"), isVideo = true)
        assertEquals(t, t.atStreamStart(30.0))
    }
}
