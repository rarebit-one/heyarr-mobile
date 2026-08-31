package one.rarebit.heyarr.mobile.login

/**
 * The app's Voidbind QR-login seam. heyarr's login channel is **QR** (plan
 * DECISIONS LOG: "heyarr login channel = QR"). The app is the *initiator* — the
 * relying party (RP): it asks heyarr's weblogin broker to start a login, renders the
 * returned `voidbind:login?rp=&id=` tuple as a QR code for the user's authenticator
 * to scan, and polls until the broker reports the login approved, receiving a
 * short-lived **Bearer** session token (bootstrap credential — see
 * `auth/Credential.Session`).
 *
 * ── Consumption of voidbind-kmp ────────────────────────────────────────────────
 * The device-side approval half (scan the QR → hardware-gated Ed25519 sign → POST
 * approve) lives in voidbind-kmp's `voidbind-client` (`WebLoginClient`, `WebLogin`,
 * `LoginApproval`). That module is NOT yet published as a consumable artifact, so
 * this interface is the documented seam and [QrLoginClient] is a wire-compatible
 * scaffold of the *initiator* half (`POST /login` + poll `GET /login/{id}`).
 * TODO(voidbind-kmp packaging): replace this seam with the published
 * `voidbind-client` once it exists (see settings.gradle.kts).
 */
interface VoidbindLogin {

    /** The outcome of a QR login attempt. */
    sealed interface Result {
        /** Approved: a short-lived RP Bearer session token (the bootstrap credential). */
        data class Approved(val sessionToken: String, val user: String?) : Result

        /** The user declined on the authenticator, or the challenge expired. */
        data class Denied(val reason: String) : Result

        /** A transport/protocol failure. */
        data class Failed(val error: String) : Result
    }

    /**
     * A started login: the RP login id plus the tuple to render as a QR code. The
     * app draws [qrTuple] as a QR for the authenticator to scan.
     */
    data class Pending(val loginId: String, val tuple: LoginTuple.Parsed, val qrTuple: String)

    /**
     * Begin a QR login: ask the RP to create a login and return the pending handle
     * whose [Pending.qrTuple] the UI renders as a scannable QR code.
     */
    fun begin(): Pending

    /**
     * Wait for the authenticator's decision by polling the RP, up to [timeoutMs].
     * Returns [Result.Approved] with a Bearer session token once approved.
     */
    fun awaitApproval(pending: Pending, timeoutMs: Long = 120_000L): Result
}
