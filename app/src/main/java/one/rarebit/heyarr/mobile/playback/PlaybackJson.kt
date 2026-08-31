package one.rarebit.heyarr.mobile.playback

/**
 * A minimal, dependency-free reader for heyarr's playback-negotiation responses —
 * `POST /api/v1/playback/plan` (read-scoped) and `POST /api/v1/playback` (write) —
 * kept JVM-testable (no `org.json`, stubbed in unit tests) for the same reason as
 * [one.rarebit.heyarr.mobile.library.WorksJson] and the login `MiniJson`.
 *
 * The fields it lifts are the ones a client needs to start playing: the `content_url`
 * (present on a DIRECT plan), a short-lived `token` (minted by `POST /playback`), and
 * the plan `decision` so a non-DIRECT verdict (REMUX / TRANSCODE / a refusal) is a
 * value the UI can surface rather than a null URL it trips over. Everything richer
 * (routing, reasons, session id) is intentionally dropped here.
 *
 * When a generated client lands (kotlinx.serialization against the published
 * OpenAPI), swap this for it.
 */
object PlaybackJson {

    /** The parsed shape of a playback plan/start response. */
    data class Plan(
        val contentUrl: String?,
        val token: String?,
        val decision: String?,
    ) {
        /** A DIRECT plan with a URL is immediately streamable; anything else is not (yet). */
        val isDirect: Boolean get() = decision?.equals("DIRECT", ignoreCase = true) == true
        val isPlayable: Boolean get() = !contentUrl.isNullOrBlank()
    }

    fun parse(body: String): Plan =
        Plan(
            contentUrl = stringField(body, "content_url"),
            token = stringField(body, "token"),
            decision = stringField(body, "decision"),
        )

    /**
     * Top-level string field reader (same escape handling as login/MiniJson). Reads
     * only the first occurrence of the key at any depth, which is enough for these
     * flat response bodies (`content_url`, `token` and `decision` are top-level).
     */
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
