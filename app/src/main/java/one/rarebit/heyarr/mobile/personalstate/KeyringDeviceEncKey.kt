package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.mobile.device.DeviceKeyring

/**
 * [DeviceEncKey] backed by the phone's enrolled keyring. The X25519 encryption key
 * is sealed at rest by `device/SealedSecretStore` (agreement keys cannot live in
 * the secure element; ADR-0001), so reading it is a file/unseal — no biometric — and
 * the ECDH runs off-enclave in [SpaceCrypto]. Cached: the key is stable per install.
 */
internal class KeyringDeviceEncKey(private val keyring: DeviceKeyring) : DeviceEncKey {
    private val key by lazy { keyring.encryptionKey() }
    override fun publicKey(): ByteArray = key.publicKey
    override fun seed(): ByteArray = key.privateKey
}
