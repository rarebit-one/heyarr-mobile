package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.net.ProblemDetail
import java.net.URLEncoder

/**
 * The reads and the management writes behind the **work detail** screen, against the
 * routes heyarr-core actually mounts (`internal/api/resources/resources.go`,
 * `desired.go`):
 *
 * Reads (the `read` floor — a QR session can do all of these):
 * - `GET /assets?limit=200[&cursor]` + `GET /editions/{id}` → the work's files.
 *   **There is no per-work asset route** (`/assets` filters by `library_id` /
 *   `content_type` / `state` only, and an asset names its *edition*, not its work),
 *   so the client pages the collection and joins each distinct edition to its work
 *   — O(assets) reads on a homelab-sized library, with the edition→work map cached
 *   per client. A server-side `work_id` filter is the fix (filed on heyarr-core).
 * - `GET /blobs/{hash}` → the asset's byte size.
 * - `GET /desired?work_id=…` → the wants for this work, with §64 acquisition facts.
 *
 * Writes (`write` scope — a read-scoped session gets a `403`, surfaced honestly):
 * - `DELETE /desired/{id}` — cancel a want (physical; a statement of intent, not bytes).
 * - `PATCH /desired/{id} {monitor}` — pause / resume monitoring a want.
 * - `POST /desired/{id}/reconcile` → `202 {job_id}` — "retry": queue a reconciliation.
 * - `POST /desired/{id}/search` → `202` — "search again": queue a candidate search.
 * - `DELETE /assets/{id}` — remove a file from the catalog (logical, ADR-0018: the
 *   blob stays until GC).
 *
 * **Not on the server**: there is no `DELETE /works/{id}` (nor `/editions/{id}`),
 * no `PATCH /works`, so a work cannot be removed or edited from here — only its
 * wants cancelled and its assets removed. Filed on heyarr-core rather than invented.
 */
class WorkDetailClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    private val editions = HashMap<String, Edition?>()

    /** The outcome of a management write — a value the VM/tests read directly. */
    sealed interface Outcome {
        /** The write took (`2xx`); [body] is the response body for callers that re-parse. */
        data class Done(val status: Int, val body: String) : Outcome

        /** A `403` — this credential cannot write; the message is the honest hint. */
        data class ReadOnly(val message: String) : Outcome

        /** A `400`/`404`/`409` the server refused by policy, with its `detail`. */
        data class Refused(val status: Int, val message: String) : Outcome

        /** Any other non-2xx. */
        data class Failed(val status: Int, val message: String) : Outcome
    }

    // ── Reads ────────────────────────────────────────────────────────────────────

    /**
     * Every asset whose edition belongs to [workId], with edition labels and blob
     * sizes filled in. Throws on a non-200 list read so the caller can surface it.
     */
    fun assetsForWork(workId: String): List<WorkAsset> {
        val all = ArrayList<WorkAsset>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = http.get(assetsUrl(baseUrl, cursor), credential.asHeader())
            require(resp.status == 200) { "assets: GET /assets failed: HTTP ${resp.status}" }
            all.addAll(WorkDetailJson.parseAssets(resp.body))
            cursor = WorkDetailJson.nextCursor(resp.body)
            pages++
        } while (cursor != null && pages < LibraryClient.MAX_PAGES)

        return all.mapNotNull { asset ->
            val edition = edition(asset.editionId) ?: return@mapNotNull null
            if (edition.workId != workId) return@mapNotNull null
            asset.copy(
                editionLabel = edition.label,
                sizeBytes = asset.blobHash?.let { blobSize(it) },
            )
        }
    }

    /** `GET /editions/{id}`, cached; null when the edition cannot be read. */
    fun edition(id: String): Edition? = editions.getOrPut(id) {
        val resp = http.get(editionUrl(baseUrl, id), credential.asHeader())
        if (resp.status == 200) WorkDetailJson.parseEdition(resp.body) else null
    }

    /** `GET /blobs/{hash}` → `size`, or null when unreadable. */
    fun blobSize(hash: String): Long? {
        val resp = http.get(blobUrl(baseUrl, hash), credential.asHeader())
        return if (resp.status == 200) WorkDetailJson.parseBlobSize(resp.body) else null
    }

    /** The wants for [workId], recent first. Throws on a non-200. */
    fun wantsForWork(workId: String): List<Want> {
        val resp = http.get(wantsUrl(baseUrl, workId), credential.asHeader())
        require(resp.status == 200) { "wants: GET /desired failed: HTTP ${resp.status}" }
        return WorkDetailJson.recentFirst(WorkDetailJson.parseWants(resp.body))
    }

    // ── Writes ───────────────────────────────────────────────────────────────────

    /** `DELETE /desired/{id}` — cancel a want. */
    fun cancelWant(wantId: String): Outcome =
        classify(http.delete(wantUrl(baseUrl, wantId), credential.asHeader()), "cancel")

    /** `PATCH /desired/{id} {"monitor": …}` — pause (`false`) or resume (`true`) monitoring. */
    fun setMonitor(wantId: String, monitor: Boolean): Outcome =
        classify(
            http.patch(wantUrl(baseUrl, wantId), monitorBody(monitor), "application/json", jsonHeaders),
            if (monitor) "resume" else "pause",
        )

    /** `POST /desired/{id}/reconcile` — queue a reconciliation ("retry"). */
    fun reconcile(wantId: String): Outcome =
        classify(http.post(wantUrl(baseUrl, wantId) + "/reconcile", "{}", "application/json", jsonHeaders), "retry")

    /** `POST /desired/{id}/search` — queue a fresh candidate search. */
    fun searchAgain(wantId: String): Outcome =
        classify(http.post(wantUrl(baseUrl, wantId) + "/search", "{}", "application/json", jsonHeaders), "search")

    /** `DELETE /assets/{id}` — remove the catalog row (bytes stay until GC, ADR-0018). */
    fun removeAsset(assetId: String): Outcome =
        classify(http.delete(assetUrl(baseUrl, assetId), credential.asHeader()), "remove")

    private val jsonHeaders: Map<String, String>
        get() = credential.asHeader() + ("Content-Type" to "application/json")

    private fun classify(resp: one.rarebit.heyarr.mobile.net.HttpResponse, label: String): Outcome = when (resp.status) {
        in 200..299 -> Outcome.Done(resp.status, resp.body)
        403 -> Outcome.ReadOnly(READ_ONLY_HINT)
        400, 404, 409 -> Outcome.Refused(resp.status, ProblemDetail.message(resp.body, resp.status, label))
        else -> Outcome.Failed(resp.status, ProblemDetail.message(resp.body, resp.status, label))
    }

    companion object {
        /** The same honest read-floor hint the acquire/unfollow paths surface on a `403`. */
        const val READ_ONLY_HINT =
            "This is a read-only session. Authorize this device to manage your library, then try again."

        private fun api(baseUrl: String) = baseUrl.trimEnd('/') + "/api/v1"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun assetsUrl(baseUrl: String, cursor: String? = null): String {
            val base = api(baseUrl) + "/assets?limit=" + LibraryClient.PAGE_LIMIT
            return if (cursor.isNullOrBlank()) base else base + "&cursor=" + enc(cursor)
        }

        fun assetUrl(baseUrl: String, id: String): String = api(baseUrl) + "/assets/" + enc(id)
        fun editionUrl(baseUrl: String, id: String): String = api(baseUrl) + "/editions/" + enc(id)
        fun blobUrl(baseUrl: String, hash: String): String = api(baseUrl) + "/blobs/" + enc(hash)
        fun wantsUrl(baseUrl: String, workId: String): String =
            api(baseUrl) + "/desired?work_id=" + enc(workId) + "&limit=" + LibraryClient.PAGE_LIMIT
        fun wantUrl(baseUrl: String, id: String): String = api(baseUrl) + "/desired/" + enc(id)

        /** heyarr's `UpdateDesiredRequest`, one field: `{"monitor":true|false}`. */
        fun monitorBody(monitor: Boolean): String = "{\"monitor\":$monitor}"
    }
}
