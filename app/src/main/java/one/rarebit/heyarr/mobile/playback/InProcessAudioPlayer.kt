package one.rarebit.heyarr.mobile.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * One ExoPlayer for the whole process, built lazily on the main thread, streaming each
 * queue item's blob route through the shared OkHttp client — so every range read picks
 * up the live credential from net/AuthInterceptor without a per-item data-source
 * factory. Dies with the process: the MediaSession service is the follow-up.
 */
@UnstableApi
class InProcessAudioPlayer(
    private val context: Context,
    private val okHttp: OkHttpClient,
    private val scope: CoroutineScope,
) : AudioPlayer {

    private val _state = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = _state.asStateFlow()

    private var ticker: Job? = null

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(okHttp)))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.update { it.copy(playing = isPlaying) }
                        if (isPlaying) startTicker() else stopTicker()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        _state.update { it.copy(index = currentMediaItemIndex, positionMs = 0, durationMs = duration.coerceAtLeast(0), error = null) }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _state.update { it.copy(durationMs = duration.coerceAtLeast(0)) }
                        if (playbackState == Player.STATE_ENDED) _state.update { it.copy(playing = false) }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        _state.update { it.copy(playing = false, error = error.errorCodeName) }
                    }
                })
            }
    }

    override fun playQueue(items: List<AudioItem>, startIndex: Int) {
        if (items.isEmpty()) return
        val start = startIndex.coerceIn(0, items.size - 1)
        _state.value = AudioState(queue = items, index = start)
        player.setMediaItems(items.map { it.toMediaItem() }, start, 0L)
        player.prepare()
        player.play()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    override fun next() { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
    override fun previous() { if (player.currentPosition > 3_000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem() }
    override fun seekTo(positionMs: Long) { player.seekTo(positionMs.coerceAtLeast(0)); _state.update { it.copy(positionMs = positionMs) } }
    override fun skipTo(index: Int) { if (index in _state.value.queue.indices) { player.seekTo(index, 0L); player.play() } }

    override fun stop() {
        stopTicker()
        player.stop()
        player.clearMediaItems()
        _state.value = AudioState()
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                _state.update { it.copy(positionMs = player.currentPosition.coerceAtLeast(0), durationMs = player.duration.coerceAtLeast(0)) }
                delay(500)
            }
        }
    }

    private fun stopTicker() { ticker?.cancel(); ticker = null }

    private fun AudioItem.toMediaItem(): MediaItem {
        val builder = MediaItem.Builder().setMediaId(assetId).setUri(Uri.parse(contentUrl))
        MediaMime.of(mime)?.let { builder.setMimeType(it) }
        builder.setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
                .setArtworkUri(artworkUrl?.let { Uri.parse(it) }).build(),
        )
        return builder.build()
    }
}
