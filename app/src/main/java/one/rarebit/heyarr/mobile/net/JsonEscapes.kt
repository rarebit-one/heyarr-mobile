package one.rarebit.heyarr.mobile.net

/**
 * The one JSON string-escape decoder shared by the app's dependency-free field
 * readers ([ProblemDetail], `SessionJson`, `SearchResultsJson`, `WorksJson`,
 * `PlaybackJson`). Handles `\"`, `\\`, `\/`, `\n`/`\t`/`\r`/`\b` and `\uXXXX` —
 * Go's `encoding/json` (heyarr-core) escapes `&`, `<`, `>` as `&` etc., so any
 * title or URL carrying one only round-trips if the escape is decoded (the same gap
 * the login-tuple parser had before #6).
 */
object JsonEscapes {
    /**
     * [json]`[i]` is a backslash. Append the decoded character to [sb] and return the
     * index just past the escape sequence. An unknown escape appends the literal
     * following character (lenient, as before).
     */
    fun append(sb: StringBuilder, json: String, i: Int): Int {
        if (i + 1 >= json.length) return i + 1
        val n = json[i + 1]
        if (n == 'u' && i + 5 < json.length) {
            val hex = json.substring(i + 2, i + 6).toIntOrNull(16)
            if (hex != null) {
                sb.append(hex.toChar())
                return i + 6
            }
        }
        sb.append(
            when (n) {
                'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'
                else -> n
            },
        )
        return i + 2
    }
}
