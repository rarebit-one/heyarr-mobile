package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.playback.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure half of subtitle-track selection — the picker label. */
class SubtitlesTest {

    @Test fun prefersAnExplicitLabel() {
        assertEquals("Director's commentary", Subtitles.label(language = "en", label = "Director's commentary", index = 0))
    }

    @Test fun fallsBackToTheLanguageName() {
        assertEquals("English", Subtitles.label(language = "en", label = null, index = 0))
        assertEquals("French", Subtitles.label(language = "fr", label = "  ", index = 1))
    }

    @Test fun fallsBackToAnOrdinalWhenNothingIsKnown() {
        assertEquals("Track 1", Subtitles.label(language = null, label = null, index = 0))
        assertEquals("Track 3", Subtitles.label(language = "und", label = null, index = 2))
    }

    @Test fun languageNameHandlesUnknownAndUnd() {
        assertEquals("English", Subtitles.languageName("en"))
        assertNull(Subtitles.languageName("und"))
        assertNull(Subtitles.languageName(null))
        assertNull(Subtitles.languageName(""))
        // A code with no display name comes back as the code itself, not null.
        assertEquals("zz", Subtitles.languageName("zz"))
    }
}
