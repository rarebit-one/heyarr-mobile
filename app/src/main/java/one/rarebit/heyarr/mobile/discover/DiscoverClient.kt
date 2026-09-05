package one.rarebit.heyarr.mobile.discover

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.net.JsonScan
import one.rarebit.heyarr.mobile.net.ProblemDetail
import one.rarebit.heyarr.mobile.search.AcquireClient

/**
 * One candidate the metadata provider returned (heyarr-core #454 `DiscoveryResult`):
 * something the library may not hold yet, followable in one step by its [tvdbId].
 */
data class DiscoverResult(
    val title: String,
    val year: Int? = null,
    val type: String? = null,
    val tvdbId: String? = null,
    val overview: String? = null,
) {
    /** The per-row key the acquire state is tracked under (a discovery has no work id). */
    val key: String get() = "tvdb:" + (tvdbId ?: title)
}

/**
 * `POST /api/v1/discover {query}` — the "not-yet-in-library" door beside `/search`
 * (heyarr-core #454). Network-touching by design, so the app asks it on demand, not
 * per keystroke. A node without a discovery-capable provider answers 503; a node that
 * predates the route answers 404 — both are [Outcome.Unavailable], not errors.
 */
class DiscoverClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    sealed interface Outcome {
        data class Found(val results: List<DiscoverResult>) : Outcome
        data class Unavailable(val why: String) : Outcome
        data class Failed(val status: Int, val message: String) : Outcome
    }

    fun discover(query: String): Outcome {
        if (query.isBlank()) return Outcome.Found(emptyList())
        val resp = http.post(
            url = discoverUrl(baseUrl),
            body = discoverBody(query),
            contentType = "application/json",
            headers = credential.asHeader() + ("Content-Type" to "application/json"),
        )
        return when (resp.status) {
            200 -> Outcome.Found(parse(resp.body))
            404, 405 -> Outcome.Unavailable("this node has no discovery search")
            503 -> Outcome.Unavailable(ProblemDetail.message(resp.body, resp.status, "discover"))
            else -> Outcome.Failed(resp.status, ProblemDetail.message(resp.body, resp.status, "discover"))
        }
    }

    companion object {
        fun discoverUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/discover"

        fun discoverBody(query: String): String = "{\"query\":" + AcquireClient.jsonString(query.trim()) + "}"

        /** `{ "results": [ { title, year, type, tvdb_id, overview } ] }`, tolerantly; a hit with no title is skipped. */
        fun parse(body: String): List<DiscoverResult> =
            JsonScan.objectsOf(body, listOf("results", "items")).mapNotNull { obj ->
                val title = JsonScan.stringField(obj, "title") ?: return@mapNotNull null
                DiscoverResult(
                    title = title,
                    year = JsonScan.intField(obj, "year"),
                    type = JsonScan.stringField(obj, "type"),
                    tvdbId = JsonScan.stringField(obj, "tvdb_id"),
                    overview = JsonScan.stringField(obj, "overview"),
                )
            }
    }
}
