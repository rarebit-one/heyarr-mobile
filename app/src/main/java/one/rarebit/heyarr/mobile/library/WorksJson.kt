package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.mobile.net.JsonScan

/**
 * A minimal, dependency-free parser for heyarr's `GET /api/v1/works` list body and
 * the single `GET /api/v1/works/{id}` object, kept JVM-testable (no org.json, which
 * is stubbed in unit tests) for the same reason as the login `MiniJson`.
 *
 * It is tolerant of the two envelope shapes a list endpoint may return — a bare
 * top-level array `[ {…}, {…} ]` or an object wrapping one under `items` / `works` /
 * `data` (the live shape is `{ "items": [Work…], "next_cursor"? }`) — and extracts,
 * per element, an `id` and a display title (from `title`, else `name`, else
 * `sort_title`) plus an optional kind (`content_type` / `kind` / `type` /
 * `media_type`), `year`, `work_key` and the two timestamps. Anything richer is
 * intentionally dropped: this feeds a browse list and a detail header.
 *
 * This is a scaffold reader. When a shared, generated client (or kotlinx.serialization
 * against the published OpenAPI) lands, swap this for it.
 */
object WorksJson {

    private val TITLE_KEYS = listOf("title", "name", "sort_title")
    private val KIND_KEYS = listOf("content_type", "kind", "type", "media_type")
    // Playback handles, tolerantly read when a browse row inlines a primary asset.
    private val HASH_KEYS = listOf("blob_hash", "content_hash", "hash")
    private val MIME_KEYS = listOf("mime", "mime_type")
    private val ENVELOPE_KEYS = listOf("items", "works", "data")

    /** Parse a works-list response body into [Work]s, skipping any element missing an id. */
    fun parse(body: String): List<Work> =
        JsonScan.objectsOf(body, ENVELOPE_KEYS).mapNotNull { parseObject(it) }

    /** Parse one `Work` object body (`GET /works/{id}`), or null if it has no id. */
    fun parseOne(body: String): Work? = JsonScan.rootObject(body)?.let { parseObject(it) }

    /** The page's `next_cursor`, when the server says there is another page. */
    fun nextCursor(body: String): String? =
        JsonScan.rootObject(body)?.let { JsonScan.stringField(it, "next_cursor") }?.takeIf { it.isNotBlank() }

    private fun parseObject(obj: String): Work? {
        val id = JsonScan.stringField(obj, "id") ?: return null
        val title = JsonScan.firstString(obj, TITLE_KEYS) ?: id
        return Work(
            id = id,
            title = title,
            kind = JsonScan.firstString(obj, KIND_KEYS),
            blobHash = JsonScan.firstString(obj, HASH_KEYS),
            mime = JsonScan.firstString(obj, MIME_KEYS),
            year = JsonScan.intField(obj, "year"),
            workKey = JsonScan.stringField(obj, "work_key"),
            sortTitle = JsonScan.stringField(obj, "sort_title"),
            externalIds = JsonScan.stringMap(obj, "external_ids"),
            createdAt = JsonScan.stringField(obj, "created_at"),
            updatedAt = JsonScan.stringField(obj, "updated_at"),
        )
    }
}
