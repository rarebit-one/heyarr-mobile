package one.rarebit.heyarr.mobile.auth

/**
 * The device credential presented under heyarr's `Device` auth scheme
 * (`Authorization: Device <cert>~<proof>`, ADR-0048). This object owns the WIRE
 * FORMAT (the `~` join, matching deviceauth.CredentialSeparator); it does NOT own
 * the crypto that produces a proof.
 *
 * ── What is real here ──────────────────────────────────────────────────────────
 * [format] / [parse] are pure and unit-tested: they build and split the
 * `<cert>~<proof>` value exactly as heyarr's verifier expects.
 *
 * ── What is PHONE-GATED (a documented seam, no crypto in this repo) ────────────
 * A [Prover] turns the enrolment cert into a *fresh* possession proof by signing a
 * challenge with the device private key. On a phone that key is **non-exportable**
 * and lives in the Android Keystore / StrongBox; the Ed25519 signature happens
 * **in-enclave** and the key never leaves it (mobile-client constraint 1 — the
 * `Unwrapper`/prover interface pattern, ADR-0022's hardware-root revisit). A slept,
 * clock-drifted device must **re-mint** the proof on wake rather than fail
 * (constraint 2, ADR-0048 skew-toward-refusing). Implementing [KeystoreProver] and
 * the enrolment handshake is device-side work — see the README follow-ups.
 */
object DeviceCredential {

    /** The enrolment separator joining the cert and the possession proof. */
    const val SEPARATOR = "~"

    /** Build the `Device` header VALUE (the part after `Device `). Pure, testable. */
    fun format(cert: String, proof: String): String {
        require(cert.isNotEmpty() && proof.isNotEmpty()) { "a device credential needs a cert and a proof" }
        require(!cert.contains(SEPARATOR)) { "cert must not contain the '$SEPARATOR' separator" }
        return cert + SEPARATOR + proof
    }

    /** Split a `Device` header value back into (cert, proof). Pure, testable. */
    fun parse(value: String): Pair<String, String> {
        val i = value.indexOf(SEPARATOR)
        require(i > 0 && i < value.length - 1) { "malformed device credential: expected <cert>${SEPARATOR}<proof>" }
        return value.substring(0, i) to value.substring(i + 1)
    }

    /**
     * Mints a fresh possession proof for a challenge. On a phone this signs
     * **in-enclave** with a non-exportable key — the interface deliberately never
     * yields the private key (constraint 1).
     *
     * TODO(device-login, phone-gated): the real Keystore/StrongBox implementation.
     */
    fun interface Prover {
        fun sign(challenge: ByteArray): ByteArray
    }
}
