package one.rarebit.heyarr.mobile.playback

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport

/**
 * The playback / blob-stream seam. heyarr serves content two ways (mobile-client
 * contract):
 *
 *  - **Direct blob stream** — `GET /api/v1/blobs/{hash}/content`, a range-capable
 *    byte hop the player pulls from. This is the primitive a native media player
 *    (ExoPlayer/Media3) points at once it has a work's content hash.
 *  - **Negotiated playback** — `POST /api/v1/playback`, which plans a session
 *    (transcode/remux/direct) and answers *how* to play a given edition.
 *
 * This scaffold lands the **structure**: the range-capable content URL (pure +
 * tested), the auth header, and a HEAD probe for size/range support. Feeding bytes
 * into a real player, seeking, and driving the `/playback` negotiation are
 * PHONE-GATED follow-ups (a Media3 pipeline on a real device) — see the README.
 */
class PlaybackClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    /** HEAD the blob to learn its size / range support before starting a stream. */
    fun probe(hash: String): ProbeResult {
        val headers = credential.asHeader() + mapOf("Range" to "bytes=0-0")
        val resp = http.get(blobContentUrl(baseUrl, hash), headers)
        return ProbeResult(status = resp.status)
    }

    /** The outcome of a range probe — just the status in the scaffold. */
    data class ProbeResult(val status: Int) {
        /** 206 Partial Content means the server honours ranges (seekable stream). */
        val acceptsRanges: Boolean get() = status == 206
    }

    // TODO(playback, phone-gated): open a Media3/ExoPlayer DataSource against
    // blobContentUrl(...) with the Authorization header + Range, and drive
    // POST /api/v1/playback for transcode negotiation. Needs a real device/codec.

    companion object {
        /** Pure, tested: the range-capable content URL for a blob hash. */
        fun blobContentUrl(baseUrl: String, hash: String): String =
            baseUrl.trimEnd('/') + "/api/v1/blobs/" + java.net.URLEncoder.encode(hash, "UTF-8") + "/content"

        /** Pure, tested: the playback-negotiation endpoint. */
        fun playbackUrl(baseUrl: String): String =
            baseUrl.trimEnd('/') + "/api/v1/playback"
    }
}
