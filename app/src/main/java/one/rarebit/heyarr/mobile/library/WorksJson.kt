package one.rarebit.heyarr.mobile.library

/**
 * A minimal, dependency-free parser for heyarr's `GET /api/v1/works` list body,
 * kept JVM-testable (no org.json, which is stubbed in unit tests) for the same
 * reason as the login `MiniJson`.
 *
 * It is tolerant of the two envelope shapes a list endpoint may return — a bare
 * top-level array `[ {…}, {…} ]` or an object wrapping one under `items` / `works` /
 * `data` — and extracts, per element, an `id` and a display title (from `title`,
 * else `name`, else `sort_title`) plus an optional kind (`kind` / `type` /
 * `media_type`). Anything richer is intentionally dropped: this feeds a browse list.
 *
 * This is a scaffold reader. When a shared, generated client (or kotlinx.serialization
 * against the published OpenAPI) lands, swap this for it.
 */
object WorksJson {

    private val TITLE_KEYS = listOf("title", "name", "sort_title")
    private val KIND_KEYS = listOf("kind", "type", "media_type")

    /** Parse a works-list response body into [Work]s, skipping any element missing an id. */
    fun parse(body: String): List<Work> {
        val array = extractArray(body) ?: return emptyList()
        return splitObjects(array).mapNotNull { obj ->
            val id = firstString(obj, listOf("id")) ?: return@mapNotNull null
            val title = firstString(obj, TITLE_KEYS) ?: id
            Work(id = id, title = title, kind = firstString(obj, KIND_KEYS))
        }
    }

    /** Return the `[ … ]` slice: the whole body if it's an array, else the array under a known key. */
    private fun extractArray(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return sliceBalanced(trimmed, 0, '[', ']')
        for (key in listOf("items", "works", "data")) {
            val at = trimmed.indexOf("\"$key\"")
            if (at < 0) continue
            val open = trimmed.indexOf('[', at)
            if (open < 0) continue
            return sliceBalanced(trimmed, open, '[', ']')
        }
        return null
    }

    /** Split a `[ {…}, {…} ]` array body into its top-level object substrings. */
    private fun splitObjects(array: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < array.length) {
            if (array[i] == '{') {
                val obj = sliceBalanced(array, i, '{', '}') ?: break
                out.add(obj)
                i += obj.length
            } else {
                i++
            }
        }
        return out
    }

    /** The first present, non-null top-level string field among [keys], or null. */
    private fun firstString(obj: String, keys: List<String>): String? {
        for (k in keys) stringField(obj, k)?.let { return it }
        return null
    }

    /**
     * Slice a brace/bracket-balanced substring starting at [start] (which must be
     * [open]), respecting string literals and escapes. Returns null if unbalanced.
     */
    private fun sliceBalanced(s: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var inStr = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    open -> depth++
                    close -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
                }
            }
            i++
        }
        return null
    }

    /** Top-level string field reader (same escape handling as login/MiniJson). */
    private fun stringField(json: String, key: String): String? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        if (i < 0) return null
        i += needle.length
        while (i < json.length && (json[i] == ' ' || json[i] == '\t' || json[i] == ':')) i++
        if (i >= json.length) return null
        if (json.startsWith("null", i)) return null
        if (json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    val n = json[i + 1]
                    sb.append(when (n) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> n })
                    i += 2
                }
                c == '"' -> return sb.toString()
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }
}
