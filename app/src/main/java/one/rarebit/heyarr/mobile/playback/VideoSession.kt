package one.rarebit.heyarr.mobile.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** One episode the player can move on to after the current one (the work's other playable files). */
data class QueueEntry(
    val assetId: String,
    val blobHash: String,
    val title: String,
    val subtitle: String?,
    val mime: String?,
    val kind: String?,
    val thumbnailPath: String? = null,
    val sizeBytes: Long? = null,
)

/**
 * What is playing on THIS phone, app-wide — the mobile twin of the desktop's
 * `PlaybackSession`. One ExoPlayer lives for the session, owned here rather than by
 * the player screen, so leaving that screen does not stop playback: the persistent
 * now-playing bar keeps play/pause, ±10 s, seek and the title while you browse, and
 * coming back re-attaches the same player to a surface. Stop is what stops it.
 *
 * Streams through `HeyarrDataSource` (auth on every ranged read); the honest banners
 * of the old screen survive here as [State.issue] / [State.noFrame] / [State.error]
 * so the app can re-plan for a stream once (`PlaybackCoordinator.onIssue`).
 * Everything runs on the main thread (ExoPlayer's requirement); Compose reads the
 * state directly.
 */
@UnstableApi
class VideoSession(
    private val context: Context,
    private val okHttp: OkHttpClient,
    private val scope: CoroutineScope,
) {
    /** One selectable subtitle track: the Media3 group + index behind a human label. */
    data class TextTrack(val id: String, val label: String, val language: String?, val group: Tracks.Group, val trackIndex: Int)

    data class State(
        val paused: Boolean = true,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val buffering: Boolean = false,
        val ended: Boolean = false,
        val textTracks: List<TextTrack> = emptyList(),
        val selectedTrackId: String? = null,
        val error: String? = null,
        val issue: String? = null,
        val noFrame: Boolean = false,
        val renderedFrame: Boolean = false,
    ) {
        val fraction: Float get() = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat() else 0f
    }

    var current: NowPlaying? by mutableStateOf(null)
        private set
    var state: State by mutableStateOf(State())
        private set
    /** The work's other playable episodes, for "next". */
    var queue: List<QueueEntry> by mutableStateOf(emptyList())
    var fullscreen: Boolean by mutableStateOf(false)

    /** The live player, as Compose state so a surface re-attaches when a stream restarts. */
    var player: ExoPlayer? by mutableStateOf(null)
        private set
    private var trackSelector: DefaultTrackSelector? = null
    private var target: PlaybackTarget? = null
    private var streamStart = 0.0
    private var resumed = true
    private var startSeconds = 0.0
    private var ticker: Job? = null

    /** Where playback reached, for the consumption reporter — set by the shell. */
    var onProgress: (PlaybackProgress) -> Unit = {}
    /** A renderer type Media3 cannot decode — the shell may re-plan for a stream. */
    var onIssue: (PlaybackDiagnostics.Issue) -> Unit = {}

    val active: Boolean get() = current != null

    /** Begin (or replace) playback of [np]. The same asset on the same URL just resumes. */
    fun load(np: NowPlaying) {
        val same = current?.assetId != null && current?.assetId == np.assetId && target?.contentUrl == np.target.contentUrl
        current = np
        if (same && player != null) { player?.play(); return }
        val t = np.target
        streamStart = if (t.restartSeekable && np.startSeconds > 0) np.startSeconds else t.streamStartSeconds
        startSeconds = np.startSeconds
        resumed = np.startSeconds <= 0 || t.restartSeekable
        val effective = if (t.restartSeekable && t.streamBaseUrl != null) t.atStreamStart(streamStart) else t
        start(effective, np.title)
    }

    private fun start(t: PlaybackTarget, title: String) {
        release()
        target = t
        state = State()
        val selector = DefaultTrackSelector(context)
        trackSelector = selector
        val p = ExoPlayer.Builder(context)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(HeyarrDataSource.factory(okHttp, t)))
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(listener)
        val item = MediaItem.Builder().setUri(t.contentUrl).setMediaId(t.contentUrl)
            .apply { MediaMime.of(t.mimeType)?.let { setMimeType(it) } }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
        player = p
        startTicker()
        if (t.isVideo) scope.launch {
            delay(PlaybackDiagnostics.NO_FRAME_GRACE_MS)
            if (player === p && p.playbackState == Player.STATE_READY && !state.renderedFrame) state = state.copy(noFrame = true)
        }
    }

    /** The source position: a stream's own clock starts at its offset. */
    fun sourceSeconds(): Double {
        val p = player ?: return streamStart
        val base = if (target?.origin == PlaybackTarget.Origin.STREAM) streamStart else 0.0
        return base + p.currentPosition.coerceAtLeast(0L) / 1000.0
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val t = target ?: return
            val groups = tracks.groups.map { g ->
                val f = if (g.length > 0) g.getTrackFormat(0) else null
                PlaybackDiagnostics.TrackGroup(type = g.type, supported = g.isSupported, sampleMime = f?.sampleMimeType, channels = f?.channelCount ?: 0)
            }
            val issue = PlaybackDiagnostics.assess(groups, t)
            if (issue != null && state.issue == null) { state = state.copy(issue = issue.message); onIssue(issue) }
            val collected = ArrayList<TextTrack>()
            var n = 0
            tracks.groups.forEachIndexed { gi, g ->
                if (g.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
                for (ti in 0 until g.length) {
                    val f = g.getTrackFormat(ti)
                    collected.add(TextTrack("g${gi}t$ti", Subtitles.label(f.language, f.label, n), f.language, g, ti))
                    n++
                }
            }
            state = state.copy(textTracks = collected, selectedTrackId = collected.firstOrNull { it.group.isTrackSelected(it.trackIndex) }?.id)
        }

        override fun onPlayerError(error: PlaybackException) {
            target?.let { state = state.copy(error = PlaybackDiagnostics.describeError(error.errorCodeName, error.message, it), paused = true) }
        }

        override fun onRenderedFirstFrame() { state = state.copy(renderedFrame = true, noFrame = false) }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            state = state.copy(buffering = playbackState == Player.STATE_BUFFERING, durationMs = p.duration.coerceAtLeast(0), ended = playbackState == Player.STATE_ENDED)
            if (playbackState == Player.STATE_READY && !resumed) {
                resumed = true
                p.seekTo((startSeconds * 1000).toLong())
            }
            if (playbackState == Player.STATE_ENDED) onProgress(PlaybackProgress(sourceSeconds(), true, PlaybackProgress.Event.ENDED))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            state = state.copy(paused = !isPlaying)
            val p = player ?: return
            if (p.playbackState == Player.STATE_ENDED) return
            onProgress(PlaybackProgress(sourceSeconds(), false, if (isPlaying) PlaybackProgress.Event.RESUMED else PlaybackProgress.Event.PAUSED))
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var lastReport = 0L
            while (isActive) {
                player?.let { p ->
                    state = state.copy(positionMs = p.currentPosition.coerceAtLeast(0), durationMs = p.duration.coerceAtLeast(0))
                    val now = System.currentTimeMillis()
                    if (p.isPlaying && now - lastReport >= PROGRESS_TICK_MS) { lastReport = now; onProgress(PlaybackProgress(sourceSeconds(), false, PlaybackProgress.Event.TICK)) }
                }
                delay(500)
            }
        }
    }

    // ── transport ────────────────────────────────────────────────────────────────

    fun togglePause() { val p = player ?: return; if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() } }
    fun pause() { player?.pause() }
    fun play() { player?.play() }

    fun seekTo(positionMs: Long) {
        val t = target ?: return
        if (t.restartSeekable) { restartStream(positionMs / 1000.0); return }
        if (!t.seekable) return
        player?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun seekFraction(f: Float) { seekTo((f.coerceIn(0f, 1f) * state.durationMs).toLong()) }

    fun seekBy(seconds: Double) {
        val t = target ?: return
        if (t.restartSeekable) { restartStream(sourceSeconds() + seconds); return }
        if (!t.seekable) return
        val p = player ?: return
        p.seekTo((p.currentPosition + (seconds * 1000).toLong()).coerceAtLeast(0))
    }

    /** A restart-seekable stream (#433): re-request the token URL at a new source offset. */
    private fun restartStream(atSeconds: Double) {
        val np = current ?: return
        val base = np.target.streamBaseUrl ?: return
        streamStart = atSeconds.coerceAtLeast(0.0)
        resumed = true
        start(np.target.atStreamStart(streamStart).copy(streamBaseUrl = base), np.title)
    }

    /** Pick a text track (or turn subtitles off): an override + enabling the text renderer. */
    fun selectText(track: TextTrack?) {
        val selector = trackSelector ?: return
        val params = selector.buildUponParameters()
        if (track == null) params.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
        selector.setParameters(params.build())
        state = state.copy(selectedTrackId = track?.id)
    }

    /** The episode after the current one, if the queue knows one. */
    fun next(): QueueEntry? {
        val c = current ?: return null
        val i = queue.indexOfFirst { it.assetId == c.assetId }
        if (i < 0) return null
        return queue.getOrNull(i + 1)
    }

    /** Stop and release: a stop that keeps the node's position, then nothing is playing. */
    fun stop() {
        if (player != null) onProgress(PlaybackProgress(sourceSeconds(), false, PlaybackProgress.Event.LEFT))
        release()
        current = null
        queue = emptyList()
        fullscreen = false
        state = State()
    }

    private fun release() {
        ticker?.cancel(); ticker = null
        player?.let { it.removeListener(listener); it.release() }
        player = null
        trackSelector = null
        target = null
    }

    companion object {
        private const val PROGRESS_TICK_MS = 5_000L
    }
}
