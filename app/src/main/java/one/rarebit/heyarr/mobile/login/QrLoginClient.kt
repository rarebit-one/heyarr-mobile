package one.rarebit.heyarr.mobile.login

import one.rarebit.heyarr.mobile.net.HttpTransport

/**
 * The initiator (RP) half of a QR web-login against heyarr's weblogin broker,
 * wire-identical to voidbind-kmp `WebLoginClient.createLogin`/`poll` and
 * voidbind-go's `weblogin.handler`:
 *
 * ```
 * POST {rp}/login             -> { "id":.., "qr":.. }             (start)
 * GET  {rp}/login/{id}         -> { "status", "token"?, "user"? }  (poll)
 * ```
 *
 * On [begin] it starts the login and hands back the tuple to render as a QR code.
 * On [awaitApproval] it polls until the broker reports the login approved and hands
 * back a short-lived **Bearer** session token (the bootstrap credential the app then
 * carries as `Authorization: Bearer <token>`).
 *
 * SCAFFOLD NOTES:
 *  - create+poll is REAL and works against a live heyarr weblogin broker today.
 *  - QR-code *rendering* (bitmap) and the device-side approval (scan → hardware
 *    Ed25519 signature) are follow-ups: rendering is a UI dependency (README), the
 *    approval belongs to the voidbind-kmp authenticator, not this consumption client.
 */
class QrLoginClient(
    private val http: HttpTransport,
    private val rpBase: String,
    /** Poll cadence; injected so tests run instantly. */
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val pollIntervalMs: Long = 1_500L,
) : VoidbindLogin {

    private fun base() = rpBase.trimEnd('/')

    override fun begin(): VoidbindLogin.Pending {
        val resp = http.post(base() + "/login")
        require(resp.status == 200) { "voidbind login: create failed: HTTP ${resp.status}" }
        val id = MiniJson.stringField(resp.body, "id")
            ?: error("voidbind login: create response missing id")
        val qr = MiniJson.stringField(resp.body, "qr")
            ?: LoginTuple.encode(base(), id)
        val tuple = LoginTuple.decode(qr)
        return VoidbindLogin.Pending(loginId = id, tuple = tuple, qrTuple = qr)
    }

    override fun awaitApproval(pending: VoidbindLogin.Pending, timeoutMs: Long): VoidbindLogin.Result {
        val deadline = nowMs() + timeoutMs
        while (true) {
            val resp = http.get(base() + "/login/" + pending.loginId)
            if (resp.status != 200) {
                return VoidbindLogin.Result.Failed("poll: HTTP ${resp.status}")
            }
            when (MiniJson.stringField(resp.body, "status")) {
                "approved" -> {
                    val token = MiniJson.stringField(resp.body, "token")
                        ?: return VoidbindLogin.Result.Failed("approved without a token")
                    return VoidbindLogin.Result.Approved(
                        sessionToken = token,
                        user = MiniJson.stringField(resp.body, "user"),
                    )
                }
                "denied" -> return VoidbindLogin.Result.Denied("declined on the authenticator")
                "expired" -> return VoidbindLogin.Result.Denied("login challenge expired")
                // "pending" / null → keep polling
            }
            if (nowMs() >= deadline) return VoidbindLogin.Result.Denied("timed out waiting for approval")
            sleep(pollIntervalMs)
        }
    }
}
