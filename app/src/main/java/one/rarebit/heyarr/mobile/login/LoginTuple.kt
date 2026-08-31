package one.rarebit.heyarr.mobile.login

/**
 * The Voidbind web-login tuple `voidbind:login?rp=<origin>&id=<login-id>` — the
 * SAME wire contract as voidbind-kmp's `LoginQr` (which is itself byte-identical to
 * voidbind-go's `weblogin.EncodeLogin`/`DecodeLogin`).
 *
 * It carries ONLY the RP origin and the login id. heyarr's login channel is **QR**
 * (plan DECISIONS LOG): the app renders this tuple as a QR code, the user's
 * authenticator (voidbind-kmp) scans it and pulls the full signed challenge from the
 * RP by id. Nothing secret rides here.
 *
 * This is a scaffold copy of the voidbind-kmp contract so the app is buildable and
 * testable today; replace it with the shared `voidbind-client` artifact once
 * voidbind-kmp publishes one (see settings.gradle.kts).
 */
object LoginTuple {

    const val SCHEME = "voidbind"
    private const val PREFIX = "$SCHEME:login?"

    data class Parsed(val rp: String, val id: String)

    /** Render the tuple. Keys are sorted (`id` before `rp`) to match voidbind-go's `url.Values.Encode()`. */
    fun encode(rp: String, id: String): String {
        require(rp.isNotEmpty() && id.isNotEmpty()) { "a login tuple needs an rp and an id" }
        return PREFIX + "id=" + urlEncode(id) + "&rp=" + urlEncode(rp)
    }

    /** Parse the tuple back into (rp, id), tolerant of key order and percent/plus encoding. */
    fun decode(uri: String): Parsed {
        require(uri.startsWith(PREFIX)) { "not a $SCHEME login tuple" }
        val fields = HashMap<String, String>()
        for (pair in uri.substring(PREFIX.length).split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            fields[urlDecode(pair.substring(0, eq))] = urlDecode(pair.substring(eq + 1))
        }
        val rp = fields["rp"].orEmpty()
        val id = fields["id"].orEmpty()
        require(rp.isNotEmpty() && id.isNotEmpty()) { "login tuple missing rp or id" }
        return Parsed(rp, id)
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun urlDecode(s: String): String =
        java.net.URLDecoder.decode(s.replace("+", "%20"), "UTF-8")
}
