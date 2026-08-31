package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport
import java.net.URLEncoder

/**
 * Lists what the user is subscribed to (the "Following" screen), and can unfollow.
 *
 * SEAM: backs the `list_followed` / `unfollow` verbs from the *Followed Sources /
 * The Archive* plan (M12), whose REST routes are **landing now**. Until they do, this
 * targets the expected `GET /api/v1/followed` (list) and `DELETE /api/v1/followed/{id}`
 * (unfollow) routes and parses through [FollowedSourcesJson]. A `404`/`501` means the
 * route is not up yet — the caller surfaces the status rather than crashing. When the
 * routes land, verify the path + shape against heyarr-core `internal/api/resources`
 * and delete this note.
 */
class FollowingClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    /** Fetch the followed-source list. Throws on a non-200 so the caller can surface the status. */
    fun list(): List<FollowedSource> {
        val resp = http.get(followedUrl(baseUrl), credential.asHeader())
        require(resp.status == 200) { "following: GET /followed failed: HTTP ${resp.status}" }
        return FollowedSourcesJson.parse(resp.body)
    }

    /** Stop following [sourceId]. The archive stays (this is an archive). Returns true on 2xx. */
    fun unfollow(sourceId: String): Boolean {
        // DELETE is not on HttpTransport's small surface; unfollow rides POST with an
        // explicit intent until the transport grows a delete(), matching the seam note.
        val resp = http.post(unfollowUrl(baseUrl, sourceId), null, null, credential.asHeader())
        return resp.status in 200..299
    }

    companion object {
        /** `GET /api/v1/followed` — EXPECTED `list_followed` route (SEAM, M12). */
        fun followedUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/followed"

        /** `.../followed/{id}/unfollow` — EXPECTED `unfollow` route (SEAM, M12). */
        fun unfollowUrl(baseUrl: String, sourceId: String): String =
            followedUrl(baseUrl) + "/" + URLEncoder.encode(sourceId, "UTF-8") + "/unfollow"
    }
}
