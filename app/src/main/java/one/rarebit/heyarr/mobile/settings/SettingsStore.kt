package one.rarebit.heyarr.mobile.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted runtime overrides for [one.rarebit.heyarr.mobile.HeyarrConfig]. A null
 * value means "no override — use the build default". Kept as an interface so the
 * resolution logic can be exercised on the JVM with [InMemorySettingsStore].
 */
interface SettingsStore {
    var baseUrlOverride: String?
    var qualityProfileOverride: String?
}

/** SharedPreferences-backed store (the phone). Values are plain origins, not secrets. */
class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var baseUrlOverride: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) = put(KEY_BASE_URL, value)

    override var qualityProfileOverride: String?
        get() = prefs.getString(KEY_QUALITY_PROFILE, null)
        set(value) = put(KEY_QUALITY_PROFILE, value)

    private fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    companion object {
        const val PREFS_NAME = "heyarr-settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_QUALITY_PROFILE = "quality_profile"
    }
}

/** Non-persistent store for tests and previews. */
class InMemorySettingsStore(
    override var baseUrlOverride: String? = null,
    override var qualityProfileOverride: String? = null,
) : SettingsStore
