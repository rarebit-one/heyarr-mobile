package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport

/**
 * **Source-agnostic content search.** The user types what they want; this hits
 * heyarr's REST content-search route and returns [SearchResult]s. There is no
 * "pick your indexer" surface — intent in, works out, the server routes to sources.
 *
 * Wire target — **live** (M12 Slice 5): `POST /api/v1/search` with a JSON body
 * `{ "query", "content_type"?, "limit"? }` ⇒ `200 { "works": [ { work_id,
 * content_type, title, year? } ] }` (heyarr-core `internal/api/resources/search.go`
 * `SearchContent`/`searchContentRoute`). It LIKE-matches the normalised `sort_title`
 * and filters on `content_type`; the intent travels in a body, so it is a POST though
 * it reads. Response parsing goes through [SearchResultsJson] so it is exercised on
 * plain JVM in CI.
 *
 * Authenticated with the caller's [Credential] (a `Device` cert+proof, or a bootstrap
 * `Bearer` session token) exactly like [one.rarebit.heyarr.mobile.library.LibraryClient].
 */
class SearchClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    /**
     * Run a content search. A blank query with no [contentType] returns empty without
     * a round-trip (the server 400s when it is given nothing to search on; the state
     * machine treats blank as Idle). Throws on a non-200 so the caller can surface it.
     */
    fun search(query: String, contentType: String? = null, limit: Int? = null): List<SearchResult> {
        if (query.isBlank() && contentType.isNullOrBlank()) return emptyList()
        val resp = http.post(
            url = searchUrl(baseUrl),
            body = searchBody(query, contentType, limit),
            contentType = "application/json",
            headers = credential.asHeader() + ("Content-Type" to "application/json"),
        )
        require(resp.status == 200) { "search: POST /search failed: HTTP ${resp.status}" }
        return SearchResultsJson.parse(resp.body)
    }

    companion object {
        /** `POST /api/v1/search` — the live source-agnostic content-search route. */
        fun searchUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/search"

        /**
         * The `SearchContentRequest` body — `{ "query", "content_type"?, "limit"? }`.
         * `content_type` and `limit` are omitted when not set (the server defaults the
         * limit to 25, caps at 100). Pure + unit-tested.
         */
        fun searchBody(query: String, contentType: String? = null, limit: Int? = null): String {
            val fields = buildList {
                add("\"query\":" + jsonString(query))
                if (!contentType.isNullOrBlank()) add("\"content_type\":" + jsonString(contentType))
                if (limit != null) add("\"limit\":$limit")
            }
            return "{" + fields.joinToString(",") + "}"
        }

        private fun jsonString(s: String): String = AcquireClient.jsonString(s)
    }
}
