package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport

/**
 * Browses heyarr's **native** library reach — `GET /api/v1/works` — authenticated
 * with the caller's [Credential] (a `Device` cert+proof, or a bootstrap `Bearer`
 * session token). This is the reach surface the scaffold DEMONSTRATES end-to-end
 * (URL + auth header + parse); the Subsonic reach is stubbed in [SubsonicClient] as
 * the documented alternative.
 *
 * `/api/v1/works` and `/api/v1/works/{id}` are the real server routes
 * (internal/api/resources). Response parsing goes through [WorksJson] so it is
 * exercised on plain JVM in CI.
 */
class LibraryClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    private fun api() = baseUrl.trimEnd('/') + "/api/v1"

    /** Fetch the work list. Throws on a non-200 so the caller can surface the status. */
    fun listWorks(): List<Work> {
        val resp = http.get(worksUrl(baseUrl), credential.asHeader())
        require(resp.status == 200) { "library: GET /works failed: HTTP ${resp.status}" }
        return WorksJson.parse(resp.body)
    }

    companion object {
        /** Pure URL builder — unit-tested. */
        fun worksUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/works"

        /** Pure URL builder for a single work — unit-tested. */
        fun workUrl(baseUrl: String, id: String): String =
            baseUrl.trimEnd('/') + "/api/v1/works/" + java.net.URLEncoder.encode(id, "UTF-8")
    }
}
