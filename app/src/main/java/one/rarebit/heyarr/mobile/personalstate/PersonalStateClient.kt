package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport

/**
 * The device-side personal-state seam — heyarr's encrypted CRDT sync surface
 * (M9, ADR-0051):
 *
 * ```
 * GET /api/v1/spaces                    -> the spaces this device can see
 * GET /api/v1/spaces/{id}/keys          -> wrapped space keys (opaque)
 * GET /api/v1/spaces/{id}/changes       -> opaque CRDT changes (incremental)
 * GET /api/v1/spaces/{id}/snapshot      -> a snapshot for a fresh/long-offline device
 * ```
 *
 * THE INVARIANT: everything the server hands back is **opaque ciphertext**. The peer
 * never decrypts (Invariant 6); decryption happens **only on-device**, under a space
 * key unwrapped in-enclave via [Unwrapper] (constraint 1). This client therefore
 * fetches and carries bytes — it does NOT decrypt, and this repo ships NO crypto.
 * A local **Personal MCP** (#372/#387) is the on-device agent that reads the
 * decrypted state; it is a device-gated follow-up.
 *
 * The scaffold demonstrates: the three URLs (pure + tested), the auth header, the
 * opaque [WrappedKey]/[OpaqueChange] carriers, and the fail-closed decrypt seam.
 */
class PersonalStateClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
    /** Decrypt-on-device seam. Defaults to fail-closed — see [Unwrapper]. */
    private val unwrapper: Unwrapper = Unwrapper.Unavailable,
) {
    /** An opaque wrapped space key as returned by `/keys` — bytes, never inspected here. */
    data class WrappedKey(val bytes: ByteArray)

    /** An opaque CRDT change as returned by `/changes` — ciphertext, decrypted only on-device. */
    data class OpaqueChange(val bytes: ByteArray)

    /** Fetch the raw (opaque) wrapped-keys body for a space. Bytes stay opaque. */
    fun fetchWrappedKeys(spaceId: String): ByteArray {
        val resp = http.get(keysUrl(baseUrl, spaceId), credential.asHeader())
        require(resp.status == 200) { "personal-state: GET /keys failed: HTTP ${resp.status}" }
        return resp.body.toByteArray(Charsets.UTF_8)
    }

    /** Fetch the raw (opaque) changes body for a space. Bytes stay opaque. */
    fun fetchChanges(spaceId: String): ByteArray {
        val resp = http.get(changesUrl(baseUrl, spaceId), credential.asHeader())
        require(resp.status == 200) { "personal-state: GET /changes failed: HTTP ${resp.status}" }
        return resp.body.toByteArray(Charsets.UTF_8)
    }

    /**
     * Decrypt an opaque change on-device. Delegates to [unwrapper]; with the default
     * fail-closed [Unwrapper.Unavailable] this THROWS rather than returning fake
     * plaintext. Real decryption is phone-gated (Keystore/StrongBox) — no crypto here.
     */
    fun decryptOnDevice(@Suppress("unused") change: OpaqueChange): ByteArray {
        // TODO(personal-state, phone-gated): unwrap the space key in-enclave, then
        // AEAD-decrypt the change. This repo ships NO crypto; the fail-closed default
        // makes "not implemented" explicit rather than silently plaintext.
        val spaceKey = unwrapper.unwrap(ByteArray(0))
        error("decrypt not implemented in scaffold (unwrapped key len=${spaceKey.size})")
    }

    companion object {
        fun spacesUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/spaces"
        fun keysUrl(baseUrl: String, spaceId: String): String = space(baseUrl, spaceId) + "/keys"
        fun changesUrl(baseUrl: String, spaceId: String): String = space(baseUrl, spaceId) + "/changes"
        fun snapshotUrl(baseUrl: String, spaceId: String): String = space(baseUrl, spaceId) + "/snapshot"

        private fun space(baseUrl: String, spaceId: String): String =
            baseUrl.trimEnd('/') + "/api/v1/spaces/" + java.net.URLEncoder.encode(spaceId, "UTF-8")
    }
}
