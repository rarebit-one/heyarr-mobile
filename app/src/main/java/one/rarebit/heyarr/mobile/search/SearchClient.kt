package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport
import java.net.URLEncoder

/**
 * **Source-agnostic content search.** The user types what they want; this hits
 * heyarr's REST content-search route and returns [SearchResult]s. There is no
 * "pick your indexer" surface — intent in, works out, the server routes to sources.
 *
 * Wire target — **exists today**: `GET /api/v1/works?q=<term>` (optionally
 * `&content_type=<type>`), heyarr-core `internal/api/resources/content.go`
 * `listWorks`. It is the REST counterpart of the MCP `search_content` verb: both
 * LIKE-match the normalised `sort_title` and filter on `content_type`. Response
 * parsing goes through [SearchResultsJson] so it is exercised on plain JVM in CI.
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
     * Run a content search. Blank query returns empty without a round-trip (the
     * server would 400 on an empty `q`; the state machine treats blank as Idle).
     * Throws on a non-200 so the caller can surface the status.
     */
    fun search(query: String, contentType: String? = null): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val url = searchUrl(baseUrl, query, contentType)
        val resp = http.get(url, credential.asHeader())
        require(resp.status == 200) { "search: GET /works failed: HTTP ${resp.status}" }
        return SearchResultsJson.parse(resp.body)
    }

    companion object {
        /** Pure URL builder — unit-tested. `q` and `content_type` are URL-encoded. */
        fun searchUrl(baseUrl: String, query: String, contentType: String? = null): String {
            val base = baseUrl.trimEnd('/') + "/api/v1/works?q=" + enc(query)
            return if (contentType.isNullOrBlank()) base else base + "&content_type=" + enc(contentType)
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
