package one.rarebit.heyarr.mobile.search

/**
 * A source the user is subscribed to — one row of the "Following" list. Models the
 * `FollowedSource` / `list_followed` shape from the *Followed Sources / The Archive*
 * plan (M12): a subscription that projects new `DesiredItem`s over time. A thin
 * projection — id, a display title, its type, and the archive's running counters
 * ([itemsKnown] enumerated, [itemsArchived] captured) plus [health] — of a richer
 * server row; the screen's job is to show what you're following, not model policy.
 */
data class FollowedSource(
    val id: String,
    val title: String,
    val type: String? = null,
    val itemsKnown: Int? = null,
    val itemsArchived: Int? = null,
    val health: String? = null,
)

/**
 * Parser for the `list_followed` response body — same tolerant, dependency-free
 * scanning as [SearchResultsJson] (whose primitives it borrows via a bare-array/
 * envelope reader). SEAM: the exact field names track the plan §7 (`list_followed`
 * returns each source + last-poll, items known, items archived, health); verify when
 * heyarr-core's REST route lands.
 */
object FollowedSourcesJson {

    fun parse(body: String): List<FollowedSource> {
        // Scanned directly from each object so this reader owns its own envelope keys
        // (`followed`/`sources`), independent of SearchResultsJson's (`works`/`items`).
        return objectsOf(body).mapNotNull { obj ->
            val id = stringField(obj, listOf("id", "source_id")) ?: return@mapNotNull null
            FollowedSource(
                id = id,
                title = stringField(obj, listOf("title", "name", "sort_title")) ?: id,
                type = stringField(obj, listOf("content_type", "source_type", "type", "kind")),
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
            else -> listOf("items", "followed", "sources", "data")
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
