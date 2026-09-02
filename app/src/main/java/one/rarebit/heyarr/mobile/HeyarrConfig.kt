package one.rarebit.heyarr.mobile

import java.net.URI

/**
 * Where this client points: the base URL of a self-hosted heyarr server that serves
 * the weblogin broker (`/login`), the library/playback APIs (`/api/v1/…`) and the
 * personal-state sync surface. Override per install (homelab vs. LAN vs. Tailscale).
 *
 * Resolution order (see [resolve]): a runtime override the user saved in Settings
 * ([one.rarebit.heyarr.mobile.settings.SettingsStore]) wins; otherwise the build-time
 * default (`BuildConfig.HEYARR_BASE_URL`, gradle property `heyarrBaseUrl`).
 */
data class HeyarrConfig(
    /** e.g. `http://192.168.16.224:7777` (no trailing slash needed). */
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * The quality profile a Get-once want / Follow is created against, named (not by
     * id) as heyarr's `WantContentRequest` expects ("living-room" is heyarr's fixture
     * default). The Settings screen can override per install.
     */
    val defaultQualityProfile: String = DEFAULT_QUALITY_PROFILE,
) {
    companion object {
        /**
         * The build-time default origin (gradle property `heyarrBaseUrl`, defaulting to
         * the live Bartley Ridge node). Not a secret — a LAN address the Settings
         * screen can override at runtime.
         */
        const val DEFAULT_BASE_URL: String = BuildConfig.HEYARR_BASE_URL
        const val DEFAULT_QUALITY_PROFILE = "living-room"

        /**
         * Resolve the effective config from the build default plus optional runtime
         * overrides. A blank/invalid base-URL override falls back to [DEFAULT_BASE_URL];
         * a blank profile override falls back to [DEFAULT_QUALITY_PROFILE]. Pure, so
         * default→override precedence is unit-tested.
         */
        fun resolve(
            baseUrlOverride: String?,
            qualityProfileOverride: String?,
            defaultBaseUrl: String = DEFAULT_BASE_URL,
            defaultQualityProfile: String = DEFAULT_QUALITY_PROFILE,
        ): HeyarrConfig = HeyarrConfig(
            baseUrl = normalizeBaseUrl(baseUrlOverride) ?: normalizeBaseUrl(defaultBaseUrl) ?: defaultBaseUrl,
            defaultQualityProfile = qualityProfileOverride?.trim()?.takeIf { it.isNotEmpty() }
                ?: defaultQualityProfile,
        )

        /**
         * Canonicalise a user-typed base URL: trim whitespace, drop trailing slashes,
         * and require an absolute `http`/`https` URL with a host. Returns null when the
         * input is blank or not usable as an origin (so the caller can fall back or
         * show a validation error).
         */
        fun normalizeBaseUrl(raw: String?): String? {
            val s = raw?.trim()?.trimEnd('/') ?: return null
            if (s.isEmpty()) return null
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrEmpty()) return null
            return s
        }
    }
}
