package one.rarebit.heyarr.mobile.net

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.auth.DeviceSession

/**
 * Wraps the app's [HttpTransport] so an enrolled device's requests always carry a
 * live `Device` credential, and a refused one is re-minted and retried ONCE
 * (mobile-client §1a constraint 2 — "re-mint and retry, never fail hard").
 *
 * The API clients hold an immutable [Credential] snapshot and set the header
 * themselves; this decorator is the single place that keeps it fresh:
 *
 *  1. A request whose `Authorization` is a `Device …` value is re-stamped with
 *     [DeviceSession.current] — the proof in force (re-minted proactively when it is
 *     within the skew window of expiry), so a client's stale snapshot never hits the
 *     wire.
 *  2. On a `401` the session re-mints (a forced fresh proof — on a phone this may
 *     show the biometric prompt) and the request is sent again exactly once. heyarr
 *     answers every Device refusal with the same undifferentiated 401 (expired proof,
 *     not-yet-valid, revoked, unknown device …), so one retry is the whole strategy: a
 *     second 401 is surfaced as-is.
 *
 * Requests with a `Bearer` (session) credential, or none, pass straight through.
 */
class DeviceAuthTransport(
    private val inner: HttpTransport,
    private val session: () -> DeviceSession?,
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResponse =
        withDeviceAuth(headers) { inner.get(url, it) }

    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        withDeviceAuth(headers) { inner.post(url, body, contentType, it) }

    override fun delete(url: String, headers: Map<String, String>): HttpResponse =
        withDeviceAuth(headers) { inner.delete(url, it) }

    private inline fun withDeviceAuth(headers: Map<String, String>, send: (Map<String, String>) -> HttpResponse): HttpResponse {
        val auth = headers[Credential.HEADER]
        val s = session()
        if (s == null || auth == null || !auth.startsWith(SCHEME_PREFIX)) return send(headers)

        val first = send(headers + (Credential.HEADER to s.current().headerValue()))
        if (first.status != 401) return first
        val fresh = s.remint()
        return send(headers + (Credential.HEADER to fresh.headerValue()))
    }

    private companion object {
        const val SCHEME_PREFIX = "Device "
    }
}
