package one.rarebit.heyarr.mobile.login

/**
 * A minimal reader for the small, FLAT JSON objects the heyarr weblogin broker
 * returns (`{"id":..,"qr":..}` and `{"status":..,"token":..,"user":..}`).
 * Deliberately dependency-free (no org.json, which is stubbed in JVM unit tests) so
 * the login state machine is testable on plain JVM. The real voidbind-client uses
 * voidbind-kmp's `MiniJson`; this is the scaffold stand-in.
 */
internal object MiniJson {

    /**
     * Extract a top-level string field's value, or null if absent/null. Handles `\"`,
     * `\n`/`\t`/`\r` and `\uXXXX` escapes — Go's `encoding/json` (heyarr-core) emits
     * `&` in the `qr` tuple as `\u0026`, so the login tuple only parses if we decode it.
     */
    fun stringField(json: String, key: String): String? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        if (i < 0) return null
        i += needle.length
        // skip whitespace + colon
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
                    if (n == 'u' && i + 5 < json.length) {
                        val hex = json.substring(i + 2, i + 6).toIntOrNull(16)
                        if (hex != null) {
                            sb.append(hex.toChar())
                            i += 6
                            continue
                        }
                    }
                    sb.append(
                        when (n) {
                            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                            else -> n
                        },
                    )
                    i += 2
                }
                c == '"' -> return sb.toString()
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }
}
