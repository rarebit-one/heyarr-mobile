package one.rarebit.heyarr.mobile.auth

import one.rarebit.voidbind.auth.DeviceCredential

/**
 * The credential this client presents to heyarr on every `/api/v1` call.
 *
 * heyarr accepts two credential shapes (mobile-client contract, ADR-0048):
 *
 *  - [Device] — the **primary** credential a first-party client carries once it is
 *    an enrolled device: a user-signed enrolment cert plus a fresh possession proof,
 *    presented under heyarr's own `Device` auth scheme:
 *    `Authorization: Device <cert>~<proof>`. The two halves are joined by the
 *    enrolment separator `~` (deviceauth.CredentialSeparator). It authenticates
 *    **offline** — the server verifies the cert against a pinned key and checks the
 *    possession proof; no token round-trip.
 *
 *  - [Session] — the **bootstrap** credential from a QR web-login: a short-lived
 *    Bearer session token minted by the weblogin broker, carried as
 *    `Authorization: Bearer <token>`. This is how a brand-new install reaches the
 *    library before (or without) enrolling as a device.
 *
 * Producing a [Device] proof needs the device private key, which on a phone lives
 * **non-exportable** in the Android Keystore / StrongBox and signs in-enclave — see
 * voidbind-client's [DeviceCredential] (the library owns the wire format: the `~`
 * join, the `Device ` scheme and the possession proof). Formatting the header from
 * an already-obtained cert+proof is pure and unit-tested; obtaining the proof is
 * phone-gated.
 */
sealed interface Credential {

    /** The `Authorization` header value to send. */
    fun headerValue(): String

    /** Bootstrap: a short-lived Bearer session token from a QR web-login. */
    data class Session(val token: String) : Credential {
        override fun headerValue() = "Bearer $token"
    }

    /** Primary: an enrolled device's cert + possession proof under the `Device` scheme. */
    data class Device(val cert: String, val proof: String) : Credential {
        override fun headerValue() = DeviceCredential.headerValue(cert, proof)
    }

    companion object {
        const val HEADER = "Authorization"
    }

    /** Convenience: the single-entry header map to merge into a request. */
    fun asHeader(): Map<String, String> = mapOf(HEADER to headerValue())
}
