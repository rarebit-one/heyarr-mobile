package one.rarebit.heyarr.mobile.personalstate

/**
 * Unwraps a space key that was wrapped to THIS device's public X25519 key
 * (ADR-0049). This interface is the exact seam the mobile-client contract calls out
 * (constraint 1): the device-side client takes an **`Unwrapper`**, never a raw
 * private key, so a phone can supply a keystore-backed unwrapper that does the ECDH
 * **in-enclave** and never yields the private key. Same interface as heyarr-core's
 * `internal/personalstate/client.Unwrapper`.
 *
 * ⚠️ NO CRYPTO IS IMPLEMENTED IN THIS REPO. Decrypt-on-device is the product
 * differentiator, and the private key is non-exportable hardware-held state — the
 * real unwrap belongs in a device-gated module wired to Android Keystore/StrongBox
 * (X25519 ECDH → HKDF → the space key), mirroring voidbind-kmp's hardware path. This
 * scaffold lands the interface and a fail-closed default so nothing silently
 * "decrypts" to plaintext.
 */
fun interface Unwrapper {
    /** Unwrap the wrapped space key into the raw symmetric key bytes (in-enclave on a phone). */
    fun unwrap(wrapped: ByteArray): ByteArray

    companion object {
        /**
         * The default: refuses to unwrap. The scaffold must NOT pretend to decrypt —
         * a real [Unwrapper] is device-gated hardware work (see class notes).
         */
        val Unavailable = Unwrapper {
            throw NotImplementedError(
                "on-device unwrap is phone-gated (Keystore/StrongBox X25519 ECDH); no crypto in this scaffold",
            )
        }
    }
}
