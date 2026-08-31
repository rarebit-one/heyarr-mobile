package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport

/**
 * The two acquisition intents behind a search result, and where each one goes on the
 * wire. This is the UI counterpart of heyarr's MCP acquisition verbs.
 *
 * - **[getOnce]** — a **one-off** acquisition: "get me this, once." Wire target
 *   **exists today**: `POST /api/v1/desired` with `monitor:false` (heyarr-core
 *   `internal/api/resources/desired.go` `createDesired` → `WantContentRequest`; the
 *   REST form of the MCP `want_content` verb). A one-off is exactly `monitor:false`:
 *   want it, acquire it, then stop looking (§60 keeps monitoring first-class, so the
 *   one-off is the deliberate opt-out).
 *
 * - **[follow]** — an **ongoing subscription**: "follow this source and keep
 *   archiving whatever it emits" (a series' new episodes, a podcast's new entries, a
 *   channel's new videos). Wire target is the `follow_source` contract from the
 *   *Followed Sources / The Archive* plan (M12), which is **being built now** and has
 *   **no REST route yet** — so [follow] is a documented **SEAM** (like the login
 *   `VoidbindLogin` seam): it POSTs the expected body to the expected route and
 *   TODO-marks the swap. See [followedUrl] / [followBody].
 *
 * The distinction is the whole feature: a one-off is a single `DesiredItem`; a follow
 * is a `FollowedSource` that *projects* new `DesiredItem`s forever.
 */
class AcquireClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
    private val qualityProfile: String,
) {

    /** Outcome of an acquire action — kept as a value so the VM/tests read it directly. */
    sealed interface Result {
        /** The one-off want was created (`201`); [desiredId] is the new desired-item id if known. */
        data class Wanted(val desiredId: String?) : Result

        /** The follow was created (`201`/`200`); [sourceId] is the followed-source id if known. */
        data class Following(val sourceId: String?) : Result

        /** The request was rejected; [status] is the HTTP code, [message] a short reason. */
        data class Failed(val status: Int, val message: String) : Result
    }

    private val jsonHeaders: Map<String, String>
        get() = credential.asHeader() + ("Content-Type" to "application/json")

    /**
     * **Get once** — a one-off want against the live `POST /api/v1/desired` route.
     * `monitor:false` is what makes it one-off. Returns [Result.Wanted] on `201`.
     */
    fun getOnce(result: SearchResult, reason: String? = null): Result {
        val resp = http.post(
            url = desiredUrl(baseUrl),
            body = oneOffWantBody(result, qualityProfile, reason),
            contentType = "application/json",
            headers = jsonHeaders,
        )
        return if (resp.status == 201 || resp.status == 200) {
            Result.Wanted(desiredId = idFromBody(resp.body))
        } else {
            Result.Failed(resp.status, "want failed: HTTP ${resp.status}")
        }
    }

    /**
     * **Follow / Subscribe** — an ongoing follow against the `follow_source` contract.
     *
     * SEAM: heyarr-core's REST `follow_source` route is landing with M12; until it
     * does, this POSTs the plan's documented body shape to the expected
     * `POST /api/v1/followed` route. When the route lands, verify the field names +
     * path against `internal/api/resources` and the `follow_source` MCP tool, then
     * delete this note. A `404`/`501` here means the route is not up yet — surfaced
     * as [Result.Failed], not a crash.
     */
    fun follow(result: SearchResult, backfill: String = "from-now", reason: String? = null): Result {
        val resp = http.post(
            url = followedUrl(baseUrl),
            body = followBody(result, qualityProfile, backfill, reason),
            contentType = "application/json",
            headers = jsonHeaders,
        )
        return if (resp.status == 201 || resp.status == 200) {
            Result.Following(sourceId = idFromBody(resp.body))
        } else {
            Result.Failed(resp.status, "follow failed: HTTP ${resp.status}")
        }
    }

    companion object {
        /** `POST /api/v1/desired` — the live want route (`want_content`). */
        fun desiredUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/desired"

        /**
         * `POST /api/v1/followed` — the EXPECTED `follow_source` route (SEAM, M12).
         * Track this against heyarr-core when the REST layer lands.
         */
        fun followedUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/followed"

        /**
         * The one-off want body — heyarr's `WantContentRequest` shape (real):
         * `{work_id, quality_profile, monitor:false, reason?}`. `monitor:false` is the
         * one-off marker.
         */
        fun oneOffWantBody(result: SearchResult, qualityProfile: String, reason: String? = null): String {
            val fields = buildList {
                add("\"work_id\":" + jsonString(result.workId))
                add("\"quality_profile\":" + jsonString(qualityProfile))
                add("\"monitor\":false")
                if (!reason.isNullOrBlank()) add("\"reason\":" + jsonString(reason))
            }
            return "{" + fields.joinToString(",") + "}"
        }

        /**
         * The follow body — the `follow_source` contract (SEAM): a source identified by
         * `source_id` (the work id from search) + `source_type` (its `content_type`), a
         * `quality_profile`, `monitor:true` (following is inherently monitored), and a
         * `backfill` policy. Field names track the plan §7; verify when the REST route
         * lands.
         */
        fun followBody(
            result: SearchResult,
            qualityProfile: String,
            backfill: String = "from-now",
            reason: String? = null,
        ): String {
            val fields = buildList {
                add("\"source_id\":" + jsonString(result.workId))
                result.type?.let { add("\"source_type\":" + jsonString(it)) }
                add("\"quality_profile\":" + jsonString(qualityProfile))
                add("\"monitor\":true")
                add("\"backfill\":" + jsonString(backfill))
                if (!reason.isNullOrBlank()) add("\"reason\":" + jsonString(reason))
            }
            return "{" + fields.joinToString(",") + "}"
        }

        /** Read an `id` (or `desired_id` / `source_id`) from a small JSON response body. */
        private fun idFromBody(body: String): String? =
            SearchResultsJson.parse("[$body]").firstOrNull()?.workId

        /** Minimal JSON string escaping for the flat bodies this builds. */
        internal fun jsonString(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.append("\"").toString()
        }
    }
}
