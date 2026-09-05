package one.rarebit.heyarr.mobile.nav

import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackClient
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.catalog.Artwork
import one.rarebit.heyarr.mobile.music.trackTitle

/**
 * The navigation host's decisions, as pure functions — so what a tap means, what the
 * player route shows, and when the mini-player appears are unit-tested rather than
 * discovered on a device. The host only wires them.
 */
object Decisions {

    /** What tapping a card does, per hub: a film plays, an album opens its tracks, a book opens the reader entry. */
    enum class Tap { PLAY, OPEN_ALBUM, OPEN_READER }

    fun tapFor(work: Work): Tap = when (Route.hubFor(work.kind)) {
        Route.HUB_MUSIC -> Tap.OPEN_ALBUM
        Route.HUB_BOOKS -> Tap.OPEN_READER
        else -> Tap.PLAY
    }

    /** What the full-screen player route renders: video pre-empts the audio queue; nothing means leave. */
    enum class PlayerContent { VIDEO, AUDIO, NONE }

    fun playerContent(nowPlaying: NowPlaying?, audioItem: AudioItem?): PlayerContent = when {
        nowPlaying != null -> PlayerContent.VIDEO
        audioItem != null -> PlayerContent.AUDIO
        else -> PlayerContent.NONE
    }

    /** The mini-player strip shows only when audio is queued and the screen is not already the player. */
    fun showMiniPlayer(fullScreen: Boolean, audioItem: AudioItem?): Boolean = !fullScreen && audioItem != null

    /**
     * An album's tracks as queue items: the blob route per track, the album's cover as
     * every track's artwork, the album's artist. A track without a blob is dropped (it
     * cannot be streamed), and the start index is re-pointed at the same track when
     * earlier ones fell away.
     */
    fun queueFor(baseUrl: String, work: Work, tracks: List<WorkAsset>, start: Int): Pair<List<AudioItem>, Int> {
        val playable = tracks.filter { !it.blobHash.isNullOrBlank() }
        val items = playable.map { t ->
            AudioItem(
                assetId = t.id, workId = work.id, title = trackTitle(t), artist = work.artist, album = work.title,
                artworkUrl = Artwork.posterUrl(baseUrl, work),
                contentUrl = PlaybackClient.blobContentUrl(baseUrl, t.blobHash!!), mime = t.mime,
            )
        }
        val wanted = tracks.getOrNull(start)?.id
        val index = playable.indexOfFirst { it.id == wanted }.takeIf { it >= 0 } ?: 0
        return items to index
    }
}
