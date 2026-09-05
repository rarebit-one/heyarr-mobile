package one.rarebit.heyarr.mobile

import androidx.media3.common.MimeTypes
import one.rarebit.heyarr.mobile.playback.MediaMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMimeTest {
    @Test fun mapsContainers() {
        assertEquals(MimeTypes.VIDEO_MATROSKA, MediaMime.of("video/x-matroska"))
        assertEquals(MimeTypes.AUDIO_FLAC, MediaMime.of("audio/flac"))
        assertEquals(MimeTypes.VIDEO_MP4, MediaMime.of("Audio/M4A; charset=x"))
        assertNull(MediaMime.of("application/octet-stream"))
        assertNull(MediaMime.of(null))
    }

    @Test fun audioByMimeThenFilename() {
        assertTrue(MediaMime.isAudio("audio/flac", null))
        assertTrue(MediaMime.isAudio(null, "01 - Song.FLAC"))
        assertTrue(MediaMime.isAudio("application/octet-stream", "track.opus"))
        assertFalse(MediaMime.isAudio("video/mp4", "film.mp4"))
        assertFalse(MediaMime.isAudio(null, null))
    }
}
