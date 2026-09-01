package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.net.ProblemDetail
import java.net.URLEncoder

/**
 * Lists what the user is subscribed to (the "Following" screen), and can unfollow.
 *
 * Backs `list_followed` / `unfollow` against the **live** M12 routes:
 * `GET /api/v1/followed-sources` ⇒ `{ "followed_sources": [ … ] }` and
 * `DELETE /api/v1/followed-sources/{id}?keep_archive=…` ⇒ `204` (heyarr-core
 * `internal/api/resources/followed.go`). Parses through [FollowedSourcesJson].
 *
 * **Phase-1 reality:** `keep_archive` defaults to `true` (stop polling, keep what is
 * archived); `keep_archive=false` (remove the archive) is **not implemented** and is
 * refused with a `400` whose `detail` explains it — surfaced through [UnfollowResult].
 */
class FollowingClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    /** Fetch the followed-source list. Throws on a non-200 so the caller can surface the status. */
    fun list(): List<FollowedSource> {
        val resp = http.get(followedUrl(baseUrl), credential.asHeader())
        require(resp.status == 200) { "following: GET /followed-sources failed: HTTP ${resp.status}" }
        return FollowedSourcesJson.parse(resp.body)
    }

    /**
     * Stop following [sourceId]. With [keepArchive] `true` (the default) polling stops
     * and everything already archived is kept — the archive is the point. `false`
     * (remove the archive) is refused in Phase 1, surfaced as [UnfollowResult.Refused].
     */
    fun unfollow(sourceId: String, keepArchive: Boolean = true): UnfollowResult {
        val resp = http.delete(unfollowUrl(baseUrl, sourceId, keepArchive), credential.asHeader())
        return when {
            resp.status in 200..299 -> UnfollowResult.Removed
            // A read-scoped session (QR web-login) 403s on this write route — surface
            // it honestly, not as a raw scope string. See AcquireClient.READ_ONLY_*.
            resp.status == 403 -> UnfollowResult.Refused(READ_ONLY_UNFOLLOW_HINT)
            resp.status == 400 -> UnfollowResult.Refused(ProblemDetail.message(resp.body, 400, "unfollow"))
            else -> UnfollowResult.Failed(resp.status, ProblemDetail.message(resp.body, resp.status, "unfollow"))
        }
    }

    /** The outcome of an [unfollow] — a value the VM/tests read directly. */
    sealed interface UnfollowResult {
        /** The subscription was removed (`204`/2xx). */
        data object Removed : UnfollowResult

        /** A `400` the server refused by policy (e.g. `keep_archive=false` in Phase 1). */
        data class Refused(val message: String) : UnfollowResult

        /** Any other non-2xx. */
        data class Failed(val status: Int, val message: String) : UnfollowResult
    }

    companion object {
        /**
         * A read-scoped session 403s on the write DELETE route; surfaced honestly. The
         * interim path to write is a follow-management grant (ADR-0061) an operator
         * authorises for this device — see [SessionClient]/[AcquireClient].
         */
        const val READ_ONLY_UNFOLLOW_HINT =
            "This is a read-only session. Authorize this device to manage follows, then try again."

        /** `GET /api/v1/followed-sources` — the live `list_followed` route. */
        fun followedUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/followed-sources"

        /**
         * `DELETE /api/v1/followed-sources/{id}?keep_archive=<bool>` — the live unfollow
         * route. `keep_archive` is always sent explicitly (it defaults to true server-side).
         */
        fun unfollowUrl(baseUrl: String, sourceId: String, keepArchive: Boolean = true): String =
            followedUrl(baseUrl) + "/" + URLEncoder.encode(sourceId, "UTF-8") + "?keep_archive=" + keepArchive
    }
}
