package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.nav.Decisions
import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionsTest {
    private val hash = "blake3:" + "1".repeat(64)
    private val np = NowPlaying(target = PlaybackTarget(contentUrl = "u", credential = Credential.Session("t"), isVideo = true), title = "x")
    private val item = AudioItem(assetId = "a", workId = "w", title = "t", contentUrl = "u")

    @Test fun aTapMeansTheRightThingPerHub() {
        assertEquals(Decisions.Tap.PLAY, Decisions.tapFor(Work(id = "1", title = "F", kind = "movie")))
        assertEquals(Decisions.Tap.PLAY, Decisions.tapFor(Work(id = "1", title = "S", kind = "series")))
        assertEquals(Decisions.Tap.OPEN_ALBUM, Decisions.tapFor(Work(id = "1", title = "A", kind = "music")))
        assertEquals(Decisions.Tap.OPEN_READER, Decisions.tapFor(Work(id = "1", title = "B", kind = "book")))
        assertEquals(Decisions.Tap.PLAY, Decisions.tapFor(Work(id = "1", title = "?", kind = null)))
    }

    @Test fun videoPreEmptsAudioAndNothingMeansLeave() {
        assertEquals(Decisions.PlayerContent.VIDEO, Decisions.playerContent(np, item))
        assertEquals(Decisions.PlayerContent.AUDIO, Decisions.playerContent(null, item))
        assertEquals(Decisions.PlayerContent.NONE, Decisions.playerContent(null, null))
    }

    @Test fun theMiniPlayerNeedsAudioAndNotTheFullScreen() {
        assertTrue(Decisions.showMiniPlayer(fullScreen = false, audioItem = item))
        assertFalse(Decisions.showMiniPlayer(fullScreen = true, audioItem = item))
        assertFalse(Decisions.showMiniPlayer(fullScreen = false, audioItem = null))
    }

    @Test fun theQueueDropsBloblessTracksAndKeepsTheChosenStart() {
        val album = Work(id = "w", title = "Album", kind = "music", artist = "Artist", artworkPath = "/api/v1/blobs/blake3:33/content")
        val tracks = listOf(
            WorkAsset(id = "a1", editionId = "e", filename = "01 - One.flac", mime = "audio/flac", blobHash = hash),
            WorkAsset(id = "a2", editionId = "e", filename = "02 - Linked.flac", mime = "audio/flac", blobHash = null),
            WorkAsset(id = "a3", editionId = "e", filename = "03 - Three.flac", mime = "audio/flac", blobHash = hash),
        )
        val (items, index) = Decisions.queueFor("https://h", album, tracks, start = 2)
        assertEquals(listOf("a1", "a3"), items.map { it.assetId })
        assertEquals("the third track is now the second queue item", 1, index)
        assertEquals("Three", items[1].title)
        assertEquals("Artist", items[1].artist)
        assertEquals("https://h/api/v1/blobs/$hash/content", items[1].contentUrl)
        assertEquals("https://h/api/v1/blobs/blake3:33/content", items[1].artworkUrl)
        // Starting on the blob-less track falls back to the first playable one.
        assertEquals(0, Decisions.queueFor("https://h", album, tracks, start = 1).second)
        assertEquals(0, Decisions.queueFor("https://h", album, emptyList(), 0).first.size)
    }
}
