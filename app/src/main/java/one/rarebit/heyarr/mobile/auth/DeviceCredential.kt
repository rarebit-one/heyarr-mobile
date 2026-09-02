package one.rarebit.heyarr.mobile.auth

/**
 * The device credential presented under heyarr's `Device` auth scheme
 * (`Authorization: Device <cert>~<proof>`, ADR-0048). This object owns the WIRE
 * FORMAT (the `~` join, `enrolment.CredentialSeparator` — a tilde is outside the
 * base64url alphabet so it can never occur inside either half); the proof bytes are
 * [PossessionProof]'s and the signing key is the [Prover]'s.
 *
 * On a phone the [Prover] is the hardware-sealed Ed25519 device key from
 * voidbind-client's `DeviceKeyStore` (voidbind-kmp ADR-0001: a software seed sealed
 * by a non-extractable, user-presence-gated AES key in StrongBox/TEE — the seed is
 * unsealed only transiently to sign, and signing needs a recent biometric). In unit
 * tests it is a software Ed25519 key.
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
     * Signs a possession-proof body with the device private key. The interface
     * deliberately never yields the key (mobile-client constraint 1): on a phone the
     * signature happens through the sealed keystore and may block on a biometric.
     */
    fun interface Prover {
        fun sign(message: ByteArray): ByteArray
    }

    /**
     * Mint a complete [Credential.Device] for [certToken] at [now] (unix seconds):
     * a fresh [PossessionProof] signed by [prover], joined to the cert.
     */
    fun mint(
        certToken: String,
        prover: Prover,
        now: Long,
        ttlSeconds: Long = PossessionProof.DEFAULT_TTL_SECONDS,
    ): Credential.Device = Credential.Device(certToken, PossessionProof.mint(certToken, now, ttlSeconds, prover::sign))
}

/**
 * An enrolled device's live credential: the long-lived cert plus the proof currently
 * in force, re-minted when it is about to lapse or when the server refuses it.
 *
 * Why not sign every request: on Android each signature unseals the seed behind a
 * 30-second user-presence window (`DeviceKeyStore`), so a per-request proof would
 * mean a biometric prompt every half-minute. Instead one proof is minted for
 * [ttlSeconds] and reused; [current] re-mints proactively once it is within
 * [PossessionProof.SKEW_SECONDS] of expiry, and [remint] is the forced refresh the
 * transport uses after a `401` (mobile-client constraint 2: re-mint on wake, never
 * fail hard on `expired`/`not_yet_valid`). The server does not cap a proof's ttl —
 * the client chooses; the Go client's default is 2 minutes.
 */
class DeviceSession(
    val certToken: String,
    private val prover: DeviceCredential.Prover,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val ttlSeconds: Long = PossessionProof.DEFAULT_TTL_SECONDS,
) {
    @Volatile
    private var live: Credential.Device? = null

    @Volatile
    private var expiresAt: Long = 0

    /** The credential to present now — re-minted if the proof is stale or about to be. */
    @Synchronized
    fun current(): Credential.Device {
        val cred = live
        if (cred != null && clock() + PossessionProof.SKEW_SECONDS < expiresAt) return cred
        return remint()
    }

    /** Force a fresh proof (after a `401`, or on wake). May prompt for user presence. */
    @Synchronized
    fun remint(): Credential.Device {
        val now = clock()
        val cred = DeviceCredential.mint(certToken, prover, now, ttlSeconds)
        live = cred
        expiresAt = now + ttlSeconds
        return cred
    }
}
