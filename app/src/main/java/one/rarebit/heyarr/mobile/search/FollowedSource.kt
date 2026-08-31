package one.rarebit.heyarr.mobile.search

/**
 * A source the user is subscribed to — one row of the "Following" list. Models the
 * live `FollowedSourceView` (`GET /api/v1/followed-sources`, heyarr-core
 * `internal/api/resources/followed.go`): a subscription that projects new
 * `DesiredItem`s over time. A thin projection — its [id], the [workId] it follows,
 * its inferred [type], and the archive's running counters ([itemsKnown] enumerated,
 * [itemsArchived] captured) plus [health] (`healthy`/`unhealthy`/`unknown`).
 *
 * The server view carries no human title (only a `work_id`), so [title] falls back to
 * the work id for display until a work→title lookup is wired; that is what the
 * Following screen shows.
 */
data class FollowedSource(
    val id: String,
    val title: String,
    val workId: String? = null,
    val type: String? = null,
    val itemsKnown: Int? = null,
    val itemsArchived: Int? = null,
    val health: String? = null,
)

/**
 * Parser for the `GET /api/v1/followed-sources` body (`{ "followed_sources": [ … ] }`)
 * — same tolerant, dependency-free scanning as [SearchResultsJson] (no `org.json`,
 * which is stubbed in unit tests). Reads each `FollowedSourceView`'s `id`, `work_id`,
 * `type`, `items_known`, `items_archived`, and `health`. A bare top-level array is
 * also tolerated.
 */
object FollowedSourcesJson {

    fun parse(body: String): List<FollowedSource> {
        // Scanned directly from each object so this reader owns its own envelope keys
        // (`followed_sources`/`followed`/`sources`), independent of SearchResultsJson's.
        return objectsOf(body).mapNotNull { obj ->
            val id = stringField(obj, listOf("id", "source_id")) ?: return@mapNotNull null
            val workId = stringField(obj, listOf("work_id"))
            FollowedSource(
                id = id,
                title = stringField(obj, listOf("title", "name", "sort_title")) ?: workId ?: id,
                workId = workId,
                type = stringField(obj, listOf("type", "content_type", "source_type", "kind")),
                itemsKnown = intField(obj, listOf("items_known", "known")),
                itemsArchived = intField(obj, listOf("items_archived", "archived")),
                health = stringField(obj, listOf("health", "status")),
            )
        }
    }

    private fun objectsOf(body: String): List<String> {
        val trimmed = body.trim()
        val arr = when {
            trimmed.startsWith("[") -> trimmed
            else -> listOf("followed_sources", "items", "followed", "sources", "data")
                .firstNotNullOfOrNull { key ->
                    val at = trimmed.indexOf("\"$key\"")
                    if (at < 0) return@firstNotNullOfOrNull null
                    val open = trimmed.indexOf('[', at)
                    if (open < 0) null else trimmed.substring(open)
                } ?: return emptyList()
        }
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var i = 0
        while (i < arr.length) {
            val c = arr[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { out.add(arr.substring(start, i + 1)); start = -1 } }
                ']' -> if (depth == 0) break
            }
            i++
        }
        return out
    }

    private fun stringField(obj: String, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { rawString(obj, it) }

    private fun intField(obj: String, keys: List<String>): Int? =
        keys.firstNotNullOfOrNull { rawInt(obj, it) }

    private fun rawString(json: String, key: String): String? {
        var i = valueStart(json, key) ?: return null
        if (i >= json.length || json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> { sb.append(json[i + 1]); i += 2 }
                c == '"' -> return sb.toString()
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }

    private fun rawInt(json: String, key: String): Int? {
        var i = valueStart(json, key) ?: return null
        if (i >= json.length || json[i] == '"') return null
        val start = i
        if (json[i] == '-' || json[i] == '+') i++
        while (i < json.length && json[i].isDigit()) i++
        return json.substring(start, i).toIntOrNull()
    }

    private fun valueStart(json: String, key: String): Int? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        if (i < 0) return null
        i += needle.length
        while (i < json.length && (json[i] == ' ' || json[i] == '\t' || json[i] == ':')) i++
        return if (i >= json.length || json.startsWith("null", i)) null else i
    }
}
