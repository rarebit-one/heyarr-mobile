package one.rarebit.heyarr.mobile.login

/**
 * Same-phone hand-off to the Voidbind authenticator app: instead of a second phone
 * scanning a QR, the app fires an `ACTION_VIEW` intent carrying the very same
 * `voidbind:` URI the QR encodes, and the authenticator (which registers the
 * `voidbind` scheme) approves it in place. Pure URI construction; the Android intent
 * plumbing is [one.rarebit.heyarr.mobile.device.HandoffLauncher].
 *
 * The login URI is the login tuple verbatim (`voidbind:login?id=…&rp=…`), optionally
 * with a `callback` the authenticator can open to bring this app back to the
 * foreground once it has approved ([CALLBACK_URI], matched by the manifest
 * intent-filter). The RP is still polled for the outcome — the callback is a
 * courtesy, never the channel.
 */
object VoidbindHandoff {

    /** The URI the authenticator may open after approving, to foreground this app. */
    const val CALLBACK_URI = "heyarr-mobile://login"

    /** Build the `voidbind:login?…` URI to hand off; appends `callback=` when given. */
    fun loginUri(qrTuple: String, callback: String? = CALLBACK_URI): String {
        require(qrTuple.startsWith("${LoginTuple.SCHEME}:login?")) { "not a voidbind login tuple" }
        if (callback.isNullOrEmpty()) return qrTuple
        return qrTuple + "&callback=" + java.net.URLEncoder.encode(callback, "UTF-8")
    }

    /** A pairing invite is handed off verbatim — it already carries relay, session and salt. */
    fun pairUri(inviteQr: String): String {
        require(inviteQr.startsWith("voidbind:pair?")) { "not a voidbind pairing invite" }
        return inviteQr
    }
}
