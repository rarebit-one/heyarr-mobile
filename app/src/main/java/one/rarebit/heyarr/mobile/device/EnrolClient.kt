package one.rarebit.heyarr.mobile.device

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.net.ProblemDetail
import one.rarebit.voidbind.crypto.MiniJson

/**
 * Registers a freshly paired device's cert with the heyarr node. A cert verifies
 * offline, but heyarr's `deviceauth.Verify` additionally requires a
 * `device_identities` row — a cert the node never accepted authenticates nobody
 * (heyarr-core `internal/deviceauth/verifier.go` step 5).
 *
 * Two lanes, tried in order:
 *  - **Self-enrol** `POST {base}/enrol {cert, proof, name}` (outside `/api/v1`; the
 *    proof is a fresh possession proof, so the caller demonstrates it holds the device
 *    key). Planned server-side; a node without it answers 404/405.
 *  - **Admin registration** `POST /api/v1/identities/devices {cert, name}` — admin
 *    scope, which a read-only session or a not-yet-registered device never has. When
 *    the self-enrol route is absent the app surfaces the cert for an operator to
 *    register, rather than pretending.
 *
 * NOT `POST /api/v1/devices`: that route registers a *playback capability profile*
 * keyed by a client-chosen string, explicitly not an identity.
 */
class EnrolClient(
    private val http: HttpTransport,
    private val baseUrl: String,
) {
    sealed interface Outcome {
        /** The node accepted the cert (self-enrol, or the admin route succeeded). */
        data class Registered(val via: String) : Outcome

        /** No route this caller can use — an operator must register the cert. */
        data class NeedsAdmin(val reason: String) : Outcome

        data class Failed(val message: String) : Outcome
    }

    /** Try to register [certToken]; [credential] is the caller's current (session) credential, if any. */
    fun register(certToken: String, proof: String, name: String, credential: Credential?): Outcome {
        val selfBody = MiniJson.encodeObject(listOf("cert" to certToken, "proof" to proof, "name" to name))
        val self = runCatching { http.post(selfEnrolUrl(baseUrl), selfBody, "application/json") }
            .getOrElse { return Outcome.Failed("self-enrol: ${it.message}") }
        when (self.status) {
            200, 201, 204 -> return Outcome.Registered("POST /enrol")
            404, 405 -> Unit // not mounted on this node — fall through to the admin lane
            else -> return Outcome.Failed(ProblemDetail.message(self.body, self.status, "self-enrol"))
        }

        if (credential == null) return Outcome.NeedsAdmin("this node has no /enrol route")
        val adminBody = MiniJson.encodeObject(listOf("cert" to certToken, "name" to name))
        val admin = runCatching {
            http.post(
                adminEnrolUrl(baseUrl), adminBody, "application/json",
                credential.asHeader() + ("Content-Type" to "application/json"),
            )
        }.getOrElse { return Outcome.Failed("register: ${it.message}") }
        return when (admin.status) {
            200, 201 -> Outcome.Registered("POST /api/v1/identities/devices")
            401, 403 -> Outcome.NeedsAdmin("no /enrol route, and registering needs admin scope (HTTP ${admin.status})")
            else -> Outcome.Failed(ProblemDetail.message(admin.body, admin.status, "register"))
        }
    }

    companion object {
        fun selfEnrolUrl(baseUrl: String) = baseUrl.trimEnd('/') + "/enrol"
        fun adminEnrolUrl(baseUrl: String) = baseUrl.trimEnd('/') + "/api/v1/identities/devices"
    }
}
