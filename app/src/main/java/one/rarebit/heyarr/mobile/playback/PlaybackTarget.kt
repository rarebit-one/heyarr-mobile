package one.rarebit.heyarr.mobile.playback

import one.rarebit.heyarr.mobile.auth.Credential

/**
 * Everything a player needs to start streaming one item: WHERE the bytes are, WHICH
 * credential authorises the pull, and enough about the media to configure the player
 * (video vs. audio, an optional MIME hint).
 *
 * [contentUrl] is the range-capable blob endpoint (`/api/v1/blobs/{hash}/content`,
 * ADR-0013) — the player issues `Range` requests against it and the server answers
 * `206 Partial Content`, which is what lets a scrub seek without pulling the whole
 * file (the M10 win). [credential] is carried as the `Authorization` header on every
 * one of those range reads.
 *
 * The type is pure and unit-tested; feeding it into a real ExoPlayer/Media3 pipeline
 * is the phone-gated half (a codec + a surface only exist on a device).
 */
data class PlaybackTarget(
    val contentUrl: String,
    val credential: Credential,
    /** A video item drives a video surface; an audio item shows transport-only UI. */
    val isVideo: Boolean,
    /** Optional MIME hint (e.g. `video/mp4`, `audio/mpeg`) to help the player pick a track. */
    val mimeType: String? = null,
) {
    /** The single `Authorization` header map the data source sends on every range read. */
    fun authHeaders(): Map<String, String> = credential.asHeader()

    companion object {
        /** MIME prefixes heyarr may hand back that a player should treat as video. */
        private const val VIDEO_PREFIX = "video/"

        /**
         * Decide whether an item is video from the signals a browse row or a playback
         * plan carries — a video MIME wins, otherwise a coarse `kind` (movie, episode
         * or video ⇒ video; everything else ⇒ audio). Pure so the audio-vs-video
         * branch is exercised in CI, not discovered on a device.
         */
        fun looksLikeVideo(mimeType: String?, kind: String?): Boolean {
            mimeType?.lowercase()?.let { if (it.startsWith(VIDEO_PREFIX)) return true }
            if (mimeType?.lowercase()?.startsWith("audio/") == true) return false
            return when (kind?.lowercase()?.trim()) {
                "movie", "episode", "video", "film", "tv", "show" -> true
                else -> false
            }
        }
    }
}
