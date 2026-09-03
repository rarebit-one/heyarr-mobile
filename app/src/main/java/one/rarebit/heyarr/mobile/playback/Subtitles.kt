package one.rarebit.heyarr.mobile.playback

import java.util.Locale

/**
 * The pure half of subtitle-track selection — the label a text track shows in the
 * picker. The Media3 side (enabling the text renderer, reading `Tracks`, applying a
 * `TrackSelectionOverride`) lives in the player composable and is phone-gated; this
 * name-building is exercised in CI.
 *
 * heyarr's `mov_text` (MP4 tx3g / MKV timed text) tracks surface through Media3's
 * extractors as ordinary TEXT tracks; each may carry a BCP-47 [language] and/or a
 * human [label]. We prefer the label, fall back to a language turned into a
 * display name, and last to a 1-based ordinal so two unnamed tracks are still
 * distinguishable.
 */
object Subtitles {

    /** The picker label for a text track: its label, else its language name, else "Track N". */
    fun label(language: String?, label: String?, index: Int): String {
        label?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        languageName(language)?.let { return it }
        return "Track ${index + 1}"
    }

    /** A BCP-47 / ISO-639 code turned into a display name ("en" → "English"), or null. */
    fun languageName(language: String?): String? {
        val code = language?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "und" } ?: return null
        val display = Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH)
        return display.takeIf { it.isNotBlank() && it != code } ?: code
    }
}
