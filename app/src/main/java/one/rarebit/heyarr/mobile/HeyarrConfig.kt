package one.rarebit.heyarr.mobile

/**
 * Where this client points: the base URL of a self-hosted heyarr server that serves
 * the weblogin broker (`/login`), the library/playback APIs (`/api/v1/…`) and the
 * personal-state sync surface. Override per install (homelab vs. LAN vs. Tailscale).
 */
data class HeyarrConfig(
    /** e.g. https://heyarr.bartley-ridge.thesim.family (no trailing slash needed). */
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        // A placeholder default; the real origin is configured per install. Kept as a
        // constant (not a secret) so BuildConfig / a settings screen can override it.
        const val DEFAULT_BASE_URL = "https://heyarr.example.thesim.family"
    }
}
