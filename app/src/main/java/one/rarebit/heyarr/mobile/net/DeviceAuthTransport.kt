package one.rarebit.heyarr.mobile.net

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.voidbind.auth.DeviceAuthPolicy
import one.rarebit.voidbind.auth.DeviceCredential

/**
 * Wraps the app's [HttpTransport] so an enrolled device's requests always carry a
 * live `Device` credential, and a refused one is re-minted and retried ONCE
 * (mobile-client §1a constraint 2 — "re-mint and retry, never fail hard").
 *
 * The API clients hold an immutable [Credential] snapshot and set the header
 * themselves; this decorator is the single place that keeps it fresh, and it does so
 * by driving voidbind-client's [DeviceAuthPolicy] over the library's [DeviceCredential]:
 *
 *  1. A request whose `Authorization` is a `Device …` value is re-stamped with
 *     [DeviceCredential.headerValue] — the proof in force (reused for the credential's
 *     `reuseForSeconds`, re-minted once the window lapses), so a client's stale
 *     snapshot never hits the wire.
 *  2. On a `401` the credential is [DeviceCredential.refresh]ed (a forced fresh proof —
 *     on a phone this may show the biometric prompt) and the request is sent again
 *     exactly once. heyarr answers every Device refusal with the same undifferentiated
 *     401, so one retry is the whole strategy: a second 401 is surfaced as-is.
 *
 * Requests with a `Bearer` (session) credential, or none, pass straight through.
 *
 * **Membership seam.** Device requests may additionally carry a
 * [MEMBERSHIP_HEADER] (`Voidbind-Membership`) value from [membership] — the
 * membership assertion voidbind-go v0.9.0's membership ops will mint. Nothing
 * provides one yet (the default provider yields `null`, and a null/blank value adds
 * no header), so the seam exists without changing any request on the wire.
 */
class DeviceAuthTransport(
    private val inner: HttpTransport,
    private val credential: () -> DeviceCredential?,
    private val membership: () -> String? = { null },
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResponse =
        withDeviceAuth(headers) { inner.get(url, it) }

    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        withDeviceAuth(headers) { inner.post(url, body, contentType, it) }

    override fun delete(url: String, headers: Map<String, String>): HttpResponse =
        withDeviceAuth(headers) { inner.delete(url, it) }

    private inline fun withDeviceAuth(headers: Map<String, String>, send: (Map<String, String>) -> HttpResponse): HttpResponse {
        val cred = credential()
        if (cred == null || !DeviceCredential.isDeviceHeader(headers[Credential.HEADER])) return send(headers)

        val stamped = membership()?.takeIf { it.isNotBlank() }
            ?.let { headers + (MEMBERSHIP_HEADER to it) } ?: headers
        return DeviceAuthPolicy.execute(cred, statusOf = { it.status }) { header ->
            send(stamped + (Credential.HEADER to header))
        }
    }

    companion object {
        /**
         * The optional membership-assertion request header a Device request may carry
         * (voidbind-go v0.9.0 membership ops). Empty until a later PR fills it.
         */
        const val MEMBERSHIP_HEADER = "Voidbind-Membership"
    }
}
