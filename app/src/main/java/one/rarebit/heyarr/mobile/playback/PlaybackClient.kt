package one.rarebit.heyarr.mobile.playback

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport

/**
 * The playback / blob-stream seam. heyarr serves content two ways (mobile-client
 * contract):
 *
 *  - **Direct blob stream** — `GET /api/v1/blobs/{hash}/content`, a range-capable
 *    byte hop the player pulls from. This is the primitive a native media player
 *    (ExoPlayer/Media3) points at once it has a work's content hash, authenticated
 *    with the caller's [Credential]. This is the path the M10 player uses: the
 *    read-scoped bootstrap session can stream a blob it knows the hash of directly,
 *    and ExoPlayer's HTTP data source handles the `Range`/`206` seek itself.
 *  - **Negotiated playback** — `POST /api/v1/playback/plan` (read) / `POST
 *    /api/v1/playback` (write), which plan a session (transcode/remux/direct) and
 *    answer *how* to play a given **asset** (a `content_url`, and for the write path
 *    a short-lived `token`). Negotiation is keyed on an `asset_id` + a `device_id`,
 *    so it is fully exercised once the client is an **enrolled device** (device auth
 *    is phone-gated / billing-gated). [plan] lands the wire-correct create+parse now.
 *
 * What is proven in CI here (pure, unit-tested): the range-capable content URL, the
 * auth header, the range probe, the [PlaybackTarget] builder, and the plan
 * create+parse. What is phone-gated: feeding [PlaybackTarget.contentUrl] into a real
 * ExoPlayer/Media3 pipeline and seeking on a real codec — see the README.
 */
class PlaybackClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    /**
     * Build the direct-stream [PlaybackTarget] for a known blob hash: the range-capable
     * content URL under this client's credential. This is the path the M10 player takes
     * for an item whose content hash the browse layer already knows.
     */
    fun blobTarget(hash: String, isVideo: Boolean, mimeType: String? = null): PlaybackTarget =
        PlaybackTarget(
            contentUrl = blobContentUrl(baseUrl, hash),
            credential = credential,
            isVideo = isVideo,
            mimeType = mimeType,
        )

    /**
     * Negotiate a DIRECT play for an asset via `POST /api/v1/playback/plan` (read
     * scope — usable by the bootstrap session). Returns a [PlaybackTarget] when the
     * plan is DIRECT and carries a `content_url`; returns null when the plan cannot be
     * played directly (REMUX/TRANSCODE/refusal), which the caller surfaces rather than
     * feeding the player a null URL.
     *
     * Keyed on `asset_id` + `device_id`; a real `device_id` arrives with device
     * enrolment (phone-gated), so today's live path is [blobTarget]. The create+parse
     * is wire-correct and unit-tested so it drops in unchanged once enrolment lands.
     */
    fun plan(assetId: String, deviceId: String, isVideo: Boolean, mimeType: String? = null): PlaybackTarget? {
        val body = """{"asset_id":${quote(assetId)},"device_id":${quote(deviceId)}}"""
        val resp = http.post(
            planUrl(baseUrl),
            body = body,
            contentType = "application/json",
            headers = credential.asHeader(),
        )
        require(resp.status == 200) { "playback: POST /playback/plan failed: HTTP ${resp.status}" }
        val plan = PlaybackJson.parse(resp.body)
        val url = plan.contentUrl?.takeIf { plan.isPlayable && plan.isDirect } ?: return null
        return PlaybackTarget(
            contentUrl = absolute(url),
            credential = credential,
            isVideo = isVideo,
            mimeType = mimeType,
        )
    }

    /** HEAD-style range probe: does the server honour ranges for this blob (seekable)? */
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

    /** Resolve a possibly-relative `content_url` from a plan against the base origin. */
    private fun absolute(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url
        else baseUrl.trimEnd('/') + "/" + url.trimStart('/')

    companion object {
        /**
         * Pure, tested: the range-capable content URL for a blob hash. The hash is
         * `blake3:<64 lowercase hex>` and goes into the path VERBATIM — the server
         * validates that exact shape and answers 400 to a percent-encoded colon
         * (`blake3%3A…`), which is how every playback used to fail against a live node.
         * Anything outside that alphabet is refused here rather than encoded.
         */
        fun blobContentUrl(baseUrl: String, hash: String): String {
            require(BLOB_HASH.matches(hash)) { "not a blob hash: $hash" }
            return baseUrl.trimEnd('/') + "/api/v1/blobs/" + hash + "/content"
        }

        private val BLOB_HASH = Regex("^blake3:[0-9a-f]{64}$")

        /** Pure, tested: the playback-negotiation endpoint (write path). */
        fun playbackUrl(baseUrl: String): String =
            baseUrl.trimEnd('/') + "/api/v1/playback"

        /** Pure, tested: the playback-plan endpoint (read path). */
        fun planUrl(baseUrl: String): String =
            baseUrl.trimEnd('/') + "/api/v1/playback/plan"

        /** Minimal JSON string quoting for the plan request body. */
        private fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
            return sb.append("\"").toString()
        }
    }
}
