package one.rarebit.heyarr.mobile.device

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.net.ProblemDetail
import one.rarebit.voidbind.crypto.MiniJson

/**
 * Registers a freshly paired device's admission with the heyarr node. An op verifies
 * offline, but heyarr's `deviceauth.Verify` additionally requires a
 * `device_identities` row — an admission the node never accepted authenticates
 * nobody (heyarr-core `internal/deviceauth/verifier.go`).
 *
 * Two lanes, tried in order:
 *  - **Self-enrol** `POST {base}/enrol {cert, proof, name, ops?}` (outside `/api/v1`;
 *    heyarr-core ADR-0067). `cert` carries the admitting op — a v2 cert IS a genesis
 *    add, and the route takes either token under `cert` or `op` (ADR-0068) — and the
 *    proof is a fresh possession proof over it, so the caller demonstrates it holds
 *    the device key. `ops` are the membership ops this device knows
 *    ([MembershipOps.presentable]): a device admitted by a member the node has never
 *    met MUST send that member's admission here, or its own cites a past the node
 *    cannot judge. A node that predates `ops` (strict JSON: an unknown field is a
 *    400) is retried once WITHOUT it — the pre-ADR-0068 body — so the app keeps
 *    working while the server catches up.
 *  - **Admin registration** `POST /api/v1/identities/devices {cert, name}` — admin
 *    scope, which a read-only session or a not-yet-registered device never has. When
 *    the self-enrol route is absent the app surfaces the op for an operator to
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
        /** The node accepted the admission (self-enrol, or the admin route succeeded). */
        data class Registered(val via: String) : Outcome

        /** No route this caller can use — an operator must register the op. */
        data class NeedsAdmin(val reason: String) : Outcome

        data class Failed(val message: String) : Outcome
    }

    /**
     * Try to register [certToken] (the admitting op) with [proof]; [ops] are the
     * membership ops to present beside it (none for a bare genesis add is fine);
     * [credential] is the caller's current (session) credential, if any.
     */
    fun register(
        certToken: String,
        proof: String,
        name: String,
        credential: Credential?,
        ops: List<String> = emptyList(),
    ): Outcome {
        var self = runCatching { http.post(selfEnrolUrl(baseUrl), selfBody(certToken, proof, name, ops), "application/json") }
            .getOrElse { return Outcome.Failed("self-enrol: ${it.message}") }
        if (self.status == 400 && ops.isNotEmpty()) {
            // A node that does not know `ops` yet refuses the field outright; the
            // admission itself may still be a plain genesis add it can judge alone.
            self = runCatching { http.post(selfEnrolUrl(baseUrl), selfBody(certToken, proof, name, emptyList()), "application/json") }
                .getOrElse { return Outcome.Failed("self-enrol: ${it.message}") }
        }
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

        /** The `POST /enrol` body: `cert`, `proof`, `name`, and `ops` only when there are any. */
        fun selfBody(certToken: String, proof: String, name: String, ops: List<String>): String =
            MiniJson.encodeObject(
                buildList<Pair<String, Any>> {
                    add("cert" to certToken)
                    add("proof" to proof)
                    add("name" to name)
                    if (ops.isNotEmpty()) add("ops" to ops)
                },
            )
    }
}
