package one.rarebit.heyarr.mobile.auth

import one.rarebit.voidbind.Ed25519Verifier
import one.rarebit.voidbind.crypto.Base64Url
import one.rarebit.voidbind.crypto.MiniJson
import java.security.MessageDigest

/**
 * The **possession proof** half of heyarr's `Device` credential — a byte-exact port of
 * voidbind-go v0.5.0 `enrolment.SignPossession` / `VerifyPossession` (what heyarr-core
 * vendors). voidbind-client mints certs and web-login assertions but no possession
 * proof, so this is the app's.
 *
 * Wire (Go `encoding/json`, struct field order, compact):
 * ```
 * body  = {"v":2,"crt":"<base64url(sha256(cert token bytes))>","iat":<unix s>,"exp":<unix s>}
 * proof = base64url(body) + "." + base64url(ed25519(deviceKey, body))
 * ```
 * `base64url` is RFC 4648 §5 **without padding** (Go's `RawURLEncoding`). `crt` hashes
 * the cert token *as presented* (the whole `payload.sig` string), so a proof is bound
 * to one cert. There is no domain label — the JSON body IS the signed message.
 *
 * Validity (server side, `VerifyPossession`): signature first, then `v == 2`, then
 * the cert hash, then time — **not-yet-valid tolerates [SKEW_SECONDS] of a device
 * clock running ahead; expiry is strict, zero grace** (ADR-0048 skews toward
 * refusing). heyarr answers every refusal with an undifferentiated `401`, so the
 * client's re-mint strategy is "on a 401 from a Device request, re-sign once and
 * retry" ([one.rarebit.heyarr.mobile.net.DeviceAuthTransport]).
 */
object PossessionProof {

    /** `enrolment.Version` — the cert/proof payload version. */
    const val VERSION = 2

    /** `enrolment.PossessionTTL` — the Go client's default proof lifetime (2 min). */
    const val DEFAULT_TTL_SECONDS = 120L

    /** `enrolment.PossessionSkew` — how far ahead a device clock may run and still be honoured. */
    const val SKEW_SECONDS = 30L

    /** Why a proof was refused, in the server's check order. */
    enum class Reason { MALFORMED, BAD_SIGNATURE, WRONG_CERT, NOT_YET_VALID, EXPIRED }

    class Refused(val reason: Reason, message: String) : IllegalArgumentException(message)

    /** The parsed, NOT-yet-verified payload of a proof. */
    data class Payload(val version: Int, val certHash: String, val issuedAt: Long, val expiresAt: Long)

    /** `base64url(sha256(certToken))` — the `crt` field. */
    fun certHash(certToken: String): String =
        Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(certToken.encodeToByteArray()))

    /** The exact JSON bytes the device signs. */
    fun signingBytes(certToken: String, issuedAt: Long, expiresAt: Long): ByteArray =
        MiniJson.encodeObject(
            listOf(
                "v" to VERSION,
                "crt" to certHash(certToken),
                "iat" to issuedAt,
                "exp" to expiresAt,
            ),
        ).encodeToByteArray()

    /**
     * Mint a proof over [certToken] issued at [now] (unix seconds) for [ttlSeconds],
     * signing with the device key via [sign] (on a phone: the hardware-sealed key,
     * user-presence gated). A non-positive ttl means [DEFAULT_TTL_SECONDS], as in Go.
     */
    fun mint(
        certToken: String,
        now: Long,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
        sign: (ByteArray) -> ByteArray,
    ): String {
        require(certToken.isNotEmpty()) { "possession proof needs a cert token" }
        val ttl = if (ttlSeconds <= 0) DEFAULT_TTL_SECONDS else ttlSeconds
        val body = signingBytes(certToken, now, now + ttl)
        val sig = sign(body)
        require(sig.size == 64) { "device signer returned ${sig.size} bytes, want a 64-byte Ed25519 signature" }
        return Base64Url.encode(body) + "." + Base64Url.encode(sig)
    }

    /** Split and decode a proof WITHOUT verifying it (a client-side freshness read). */
    fun parse(proof: String): Payload = try {
        val dot = proof.indexOf('.')
        require(dot > 0 && dot < proof.length - 1)
        val obj = MiniJson.parseObject(Base64Url.decode(proof.substring(0, dot)).decodeToString())
        Payload(
            version = (obj["v"] as Long).toInt(),
            certHash = obj["crt"] as String,
            issuedAt = obj["iat"] as Long,
            expiresAt = obj["exp"] as Long,
        )
    } catch (e: Refused) {
        throw e
    } catch (e: Exception) {
        throw Refused(Reason.MALFORMED, "malformed possession proof: ${e.message}")
    }

    /**
     * Verify [proof] exactly as heyarr does (`enrolment.VerifyPossession`): the
     * device's 32-byte [devicePublicKey] signed it, it is v2, it names [certToken],
     * and [now] (unix seconds) lies inside its window with the skew asymmetry above.
     * Throws [Refused] with the server's reason, else returns the payload.
     */
    fun verify(
        proof: String,
        devicePublicKey: ByteArray,
        certToken: String,
        now: Long,
        verifier: Ed25519Verifier,
    ): Payload {
        val dot = proof.indexOf('.')
        if (dot <= 0 || dot >= proof.length - 1) throw Refused(Reason.MALFORMED, "possession proof is not <body>.<sig>")
        val body: ByteArray
        val sig: ByteArray
        try {
            body = Base64Url.decode(proof.substring(0, dot))
            sig = Base64Url.decode(proof.substring(dot + 1))
        } catch (e: IllegalArgumentException) {
            throw Refused(Reason.MALFORMED, "possession proof is not base64url: ${e.message}")
        }
        if (sig.size != 64 || !verifier.verify(devicePublicKey, body, sig)) {
            throw Refused(Reason.BAD_SIGNATURE, "possession proof signature does not verify")
        }
        val p = parse(proof)
        if (p.version != VERSION || p.certHash.isEmpty()) throw Refused(Reason.MALFORMED, "possession proof payload is not v$VERSION")
        if (p.certHash != certHash(certToken)) throw Refused(Reason.WRONG_CERT, "possession proof is bound to a different cert")
        if (now + SKEW_SECONDS < p.issuedAt) throw Refused(Reason.NOT_YET_VALID, "possession proof valid from ${p.issuedAt}, now $now")
        if (now >= p.expiresAt) throw Refused(Reason.EXPIRED, "possession proof expired at ${p.expiresAt}, now $now")
        return p
    }
}
