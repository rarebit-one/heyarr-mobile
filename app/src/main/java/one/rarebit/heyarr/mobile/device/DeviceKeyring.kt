package one.rarebit.heyarr.mobile.device

import android.content.Context
import one.rarebit.voidbind.Cert
import one.rarebit.voidbind.DeviceIdentity
import one.rarebit.voidbind.DeviceKeyStore
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.VoidbindAndroid
import java.io.File

/** The honest hardware tier of the device signing key's wrapping key (never over-stated). */
enum class KeyTier { STRONGBOX, TEE, SOFTWARE }

/** What this device holds, for the enrol screen and the read-only banner. */
data class DeviceKeyInfo(
    /** `ed25519:<hex>` — the key an operator names to authorise this device. */
    val deviceKey: String,
    /** `x25519:<hex>` — the encryption key a pairing cert binds as `denc`. */
    val deviceEncKey: String,
    val tier: KeyTier,
    /** The user-signed enrolment cert token, once this device has been paired in. */
    val certToken: String?,
) {
    val isEnrolled: Boolean get() = certToken != null
}

/**
 * This phone's Voidbind device keys, persisted **sealed** (voidbind-kmp ADR-0001):
 *
 *  - the **Ed25519 signing key** lives in voidbind-client's hardware [DeviceKeyStore]
 *    — a software seed sealed by a non-extractable, user-auth-gated AES-GCM key in
 *    the AndroidKeyStore (StrongBox where present, TEE otherwise). It is generated on
 *    first run and never exported; every possession proof is signed through it.
 *  - the **X25519 encryption key** (which no secure element can hold) is sealed at
 *    rest by [SealedSecretStore]; its public half is stored plain. It is what unseals
 *    a cert delivered over the pairing relay.
 *  - the **enrolment cert** (a public, user-signed token) is stored plain once pairing
 *    completes.
 *
 * Keystore calls that need a fresh user authentication (first provisioning, and
 * every signature) go through the [BiometricGate]; run this off the main thread.
 */
class DeviceKeyring(
    context: Context,
    private val gate: BiometricGate,
    private val alias: String = DEFAULT_ALIAS,
) {
    private val app = context.applicationContext
    private val secrets = SealedSecretStore(app)

    init {
        VoidbindAndroid.init(app)
    }

    private fun dir(): File = File(app.filesDir, "heyarr-device").apply { mkdirs() }
    private fun encPubFile() = File(dir(), "enc.$alias.pub")
    private fun certFile() = File(dir(), "cert.$alias.token")

    /** True once the sealed signing key exists on this phone (no prompt to check). */
    fun isProvisioned(): Boolean = File(File(app.filesDir, "voidbind"), "$alias.key").exists()

    /**
     * The device info WITHOUT provisioning: null on a fresh install. Loading existing
     * sealed keys needs no user presence (only signing / first creation do), so this is
     * safe to call at start-up without surfacing a biometric prompt.
     */
    fun peek(): DeviceKeyInfo? = if (isProvisioned()) info() else null

    /** Open (or on first run, provision) the signing key. May prompt for user presence. */
    fun keyStore(): DeviceKeyStore =
        gate.gated("Set up this device", "Confirm it's you to create the device key") {
            DeviceKeyStore.getOrCreate(alias)
        }

    /** The sealed X25519 keypair, generated once on first use. */
    fun encryptionKey(): DeviceIdentity.EncryptionKey {
        val priv = secrets.unseal(ENC_SECRET)
        val pubFile = encPubFile()
        if (priv != null && pubFile.exists()) {
            return DeviceIdentity.EncryptionKey(priv, pubFile.readBytes())
        }
        val fresh = DeviceIdentity.generateEncryptionKey()
        secrets.seal(ENC_SECRET, fresh.privateKey)
        pubFile.writeBytes(fresh.publicKey)
        return fresh
    }

    /** The full device identity: hardware signer + enc keypair. */
    fun identity(): DeviceIdentity {
        val ks = keyStore()
        val enc = encryptionKey()
        return DeviceIdentity(ks.publicKey().bytes, enc.publicKey, enc.privateKey) { message ->
            gate.gated("Sign with device key", "Confirm it's you to prove possession") { ks.sign(message) }
        }
    }

    /** The honest tier of the wrapping key backing the signing key. */
    fun tier(ks: DeviceKeyStore): KeyTier = when (ks.securityLevel()) {
        DeviceKeyStore.SecurityLevel.STRONGBOX -> KeyTier.STRONGBOX
        DeviceKeyStore.SecurityLevel.TEE -> KeyTier.TEE
        DeviceKeyStore.SecurityLevel.SOFTWARE -> KeyTier.SOFTWARE
    }

    fun certToken(): String? = certFile().takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /**
     * Persist a delivered enrolment cert after checking it names THIS device's keys —
     * a cert for another device would be refused by the server anyway, but storing it
     * would wedge the app into a Device credential that never works.
     */
    fun saveCert(token: String) {
        val parsed = Cert.parse(token).cert
        val info = info()
        require(parsed.device.render() == info.deviceKey) { "cert binds a different device key" }
        require(parsed.deviceEnc.render() == info.deviceEncKey) { "cert binds a different encryption key" }
        certFile().writeText(token)
    }

    /** Forget the cert (the keys stay — re-pairing re-uses them). */
    fun clearCert() {
        certFile().delete()
    }

    /** Snapshot for the UI. Provisions the keys on first call. */
    fun info(): DeviceKeyInfo {
        val ks = keyStore()
        val enc = encryptionKey()
        return DeviceKeyInfo(
            deviceKey = ks.publicKey().render(),
            deviceEncKey = KeyRef.x25519(enc.publicKey).render(),
            tier = tier(ks),
            certToken = certToken(),
        )
    }

    companion object {
        const val DEFAULT_ALIAS = "heyarr-device"
        private const val ENC_SECRET = "enc"
    }
}
