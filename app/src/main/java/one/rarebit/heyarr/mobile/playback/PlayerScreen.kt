package one.rarebit.heyarr.mobile.playback

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/** One selectable subtitle track: the Media3 group + index behind a human label. */
@UnstableApi
private data class UiTextTrack(val id: String, val label: String, val group: Tracks.Group, val trackIndex: Int)

/**
 * The M10 player. Streams a [PlaybackTarget] over the authenticated, range-capable
 * endpoint through an ExoPlayer built on [HeyarrDataSource]. The [PlayerView]
 * supplies the transport controls and handles **both** video (renders to the
 * surface) and audio (controller-only, with the item title shown) items.
 *
 * **Subtitles (#432).** The player builds on a [DefaultTrackSelector] with the text
 * renderer enabled, reads the item's TEXT tracks (heyarr's `mov_text` — MP4 tx3g /
 * MKV timed text — surface here through Media3's extractors), and offers a **CC**
 * menu to pick one or turn them off (a `TrackSelectionOverride`, `PlayerView` renders
 * the chosen track). The menu appears only when the item actually carries text tracks.
 *
 * **Seeking a transcoded stream (#433, ADR-0069).** A `mode: stream` repackage cannot
 * be range-sought — but the token URL can be re-requested with `?start=<seconds>` to
 * restart ffmpeg from a new instant. For such a target the player shows explicit skip
 * controls; a skip recomputes the offset from the current position and swaps the media
 * item to the `?start=` URL, resuming there. The token is sent verbatim (the #16 trap).
 *
 * It is HONEST about what the phone can't do: a track group Media3 has no decoder for
 * (the AC-3 5.1 case) raises a banner via [PlaybackDiagnostics] and [onIssue] (so the
 * app can re-plan for a stream), a decoder/renderer failure shows an error state
 * instead of a black surface, and a video stream that reaches READY without ever
 * rendering a frame (AVI) says so after a short grace.
 *
 * The ExoPlayer is owned by the composition and **released** when the media changes or
 * the screen leaves it (`DisposableEffect`), so a backgrounded, re-sought or dismissed
 * player does not leak a codec or hold the audio focus.
 */
@UnstableApi
@Composable
fun PlayerScreen(
    target: PlaybackTarget,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** A banner the app decided on (e.g. after a re-plan); overrides the local verdict. */
    banner: String? = null,
    /** A renderer type Media3 cannot play; the app may re-plan for a stream. */
    onIssue: (PlaybackDiagnostics.Issue) -> Unit = {},
    client: OkHttpClient = remember { OkHttpClient() },
    /** Where to start, in seconds (the node's remembered position); 0 plays from the top. */
    startSeconds: Double = 0.0,
    /** Where playback has reached, for the consumption session. */
    onProgress: (PlaybackProgress) -> Unit = {},
) {
    val context = LocalContext.current

    // A restart-seekable stream (#433) carries its own offset; a skip rebuilds the URL.
    // A resume on a stream is a restart at that offset — the only seek a stream has.
    var streamStart by remember(target.streamBaseUrl) {
        mutableStateOf(if (target.restartSeekable && startSeconds > 0) startSeconds else target.streamStartSeconds)
    }
    // Resume once, on the first READY of the first URL; a re-plan or a skip must not re-seek.
    var resumed by remember(target.contentUrl) { mutableStateOf(startSeconds <= 0 || target.restartSeekable) }

    val effectiveTarget = if (target.restartSeekable && target.streamBaseUrl != null) target.atStreamStart(streamStart) else target
    val mediaUrl = effectiveTarget.contentUrl

    // Per-media verdicts: a new URL (a re-planned stream, or a restart at a new offset)
    // starts clean.
    var localIssue by remember(mediaUrl) { mutableStateOf<String?>(null) }
    var errorText by remember(mediaUrl) { mutableStateOf<String?>(null) }
    var renderedFrame by remember(mediaUrl) { mutableStateOf(false) }
    var reachedReady by remember(mediaUrl) { mutableStateOf(false) }
    var noFrame by remember(mediaUrl) { mutableStateOf(false) }
    var textTracks by remember(mediaUrl) { mutableStateOf<List<UiTextTrack>>(emptyList()) }
    var selectedTrackId by remember(mediaUrl) { mutableStateOf<String?>(null) }

    val trackSelector = remember(mediaUrl) { DefaultTrackSelector(context) }
    val player = remember(mediaUrl) {
        val dataSourceFactory: DataSource.Factory = HeyarrDataSource.factory(client, effectiveTarget)
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(buildMediaItem(effectiveTarget, title))
                prepare()
                playWhenReady = true
            }
    }

    // Where we are in the SOURCE: a stream's own clock starts at its offset.
    fun sourceSeconds(): Double =
        (if (effectiveTarget.origin == PlaybackTarget.Origin.STREAM) streamStart else 0.0) + player.currentPosition.coerceAtLeast(0L) / 1000.0

    // Pick a text track (or turn subtitles off): an override + enabling the text renderer.
    fun selectText(t: UiTextTrack?) {
        val params = trackSelector.buildUponParameters()
        if (t == null) {
            params.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.trackIndex))
        }
        trackSelector.setParameters(params.build())
        selectedTrackId = t?.id
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val groups = tracks.groups.map { g ->
                    val f = if (g.length > 0) g.getTrackFormat(0) else null
                    PlaybackDiagnostics.TrackGroup(
                        type = g.type,
                        supported = g.isSupported,
                        sampleMime = f?.sampleMimeType,
                        channels = f?.channelCount ?: 0,
                    )
                }
                val issue = PlaybackDiagnostics.assess(groups, effectiveTarget)
                if (issue != null && localIssue == null) {
                    localIssue = issue.message
                    onIssue(issue)
                }
                // Collect the selectable TEXT tracks (heyarr's mov_text surfaces here).
                val collected = ArrayList<UiTextTrack>()
                var n = 0
                tracks.groups.forEachIndexed { gi, g ->
                    if (g.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
                    for (ti in 0 until g.length) {
                        val f = g.getTrackFormat(ti)
                        collected.add(UiTextTrack("g${gi}t$ti", Subtitles.label(f.language, f.label, n), g, ti))
                        n++
                    }
                }
                textTracks = collected
                selectedTrackId = collected.firstOrNull { it.group.isTrackSelected(it.trackIndex) }?.id
            }

            override fun onPlayerError(error: PlaybackException) {
                errorText = PlaybackDiagnostics.describeError(error.errorCodeName, error.message, effectiveTarget)
            }

            override fun onRenderedFirstFrame() { renderedFrame = true }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    reachedReady = true
                    if (!resumed) {
                        resumed = true
                        player.seekTo((startSeconds * 1000).toLong())
                    }
                }
                if (playbackState == Player.STATE_ENDED) onProgress(PlaybackProgress(sourceSeconds(), true, PlaybackProgress.Event.ENDED))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player.playbackState == Player.STATE_ENDED) return
                onProgress(PlaybackProgress(sourceSeconds(), false, if (isPlaying) PlaybackProgress.Event.RESUMED else PlaybackProgress.Event.PAUSED))
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Tick the position while playing; the reporter throttles what actually goes out.
    LaunchedEffect(player) {
        while (true) {
            delay(PROGRESS_TICK_MS)
            if (player.isPlaying) onProgress(PlaybackProgress(sourceSeconds(), false, PlaybackProgress.Event.TICK))
        }
    }
    // Leaving the screen is a stop that keeps the position.
    DisposableEffect(Unit) {
        onDispose { onProgress(PlaybackProgress(sourceSeconds(), false, PlaybackProgress.Event.LEFT)) }
    }

    // AVI-with-no-video: READY, duration known, picture never comes.
    LaunchedEffect(reachedReady, mediaUrl) {
        if (!reachedReady || !effectiveTarget.isVideo) return@LaunchedEffect
        delay(PlaybackDiagnostics.NO_FRAME_GRACE_MS)
        if (!renderedFrame) noFrame = true
    }

    // Skip the transcoded stream by restarting it at a new source offset (#433).
    fun skipStream(deltaSeconds: Double) {
        val posSeconds = player.currentPosition.coerceAtLeast(0L).toDouble() / 1000.0
        streamStart = (streamStart + posSeconds + deltaSeconds).coerceAtLeast(0.0)
    }

    val bannerText = banner ?: localIssue ?: noFrame.takeIf { it }?.let { PlaybackDiagnostics.noFrameMessage(effectiveTarget) }
        ?: effectiveTarget.takeIf { it.origin == PlaybackTarget.Origin.STREAM }?.let { streamNote(it) }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showSeek = effectiveTarget.restartSeekable && effectiveTarget.isVideo

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (errorText != null) {
            ErrorState(title = title, message = errorText!!, onBack = onBack)
            return@Box
        }
        if (landscape && effectiveTarget.isVideo) {
            // Landscape video: the surface fills the screen; the controls (a PlayerView
            // draws its own) and our overlays stay out of the cutout and the nav bar.
            PlayerSurface(player, effectiveTarget, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
            Row(
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack)
                Text(
                    title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (textTracks.isNotEmpty()) SubtitleMenu(textTracks, selectedTrackId) { selectText(it) }
            }
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showSeek) StreamSeekControls(streamStart) { skipStream(it) }
                bannerText?.let { Banner(it, Modifier.padding(top = 8.dp)) }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(onBack)
                    Text(
                        title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp).weight(1f),
                    )
                    if (textTracks.isNotEmpty()) SubtitleMenu(textTracks, selectedTrackId) { selectText(it) }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().let {
                        // Give video a 16:9 stage; an audio item just needs the transport bar.
                        if (effectiveTarget.isVideo) it.aspectRatio(16f / 9f) else it
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    PlayerSurface(player, effectiveTarget, Modifier.fillMaxWidth())
                    if (!effectiveTarget.isVideo) {
                        Text(
                            "♪  $title", color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                if (showSeek) StreamSeekControls(streamStart, Modifier.padding(12.dp)) { skipStream(it) }
                bannerText?.let { Banner(it, Modifier.padding(12.dp)) }
            }
        }
    }
}

@UnstableApi
@Composable
private fun PlayerSurface(player: ExoPlayer, target: PlaybackTarget, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                // Audio-only items have no video track — keep the controls up.
                controllerShowTimeoutMs = if (target.isVideo) 3_000 else 0
                if (!target.seekable) {
                    // A node-repackaged stream can't be range-sought: no ±10 s, a scrubber
                    // that shows position but refuses a drag. A restart-seekable stream gets
                    // our own skip controls instead (StreamSeekControls).
                    setShowFastForwardButton(false)
                    setShowRewindButton(false)
                    findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.isEnabled = false
                }
            }
        },
        modifier = modifier,
    )
}

/** The CC menu: turn subtitles off or pick one of the item's text tracks. */
@UnstableApi
@Composable
private fun SubtitleMenu(tracks: List<UiTextTrack>, selectedId: String?, onSelect: (UiTextTrack?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(if (selectedId == null) "CC" else "CC ●", color = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (selectedId == null) "Subtitles off ✓" else "Subtitles off") },
                onClick = { onSelect(null); open = false },
            )
            tracks.forEach { t ->
                DropdownMenuItem(
                    text = { Text(if (t.id == selectedId) "${t.label} ✓" else t.label) },
                    onClick = { onSelect(t); open = false },
                )
            }
        }
    }
}

/** Explicit skip controls for a restart-seekable stream (#433): each restarts ffmpeg at a new offset. */
@Composable
private fun StreamSeekControls(startSeconds: Double, modifier: Modifier = Modifier, onSkip: (Double) -> Unit) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onSkip(-30.0) }) { Text("⏪ 30s", color = Color.White) }
        Text("from ${clock(startSeconds)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f, fill = false))
        TextButton(onClick = { onSkip(30.0) }) { Text("30s ⏩", color = Color.White) }
    }
}

/** Seconds → `m:ss` / `h:mm:ss` for the restart-seek label. */
private fun clock(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack) { Text("‹ Back", color = Color.White) }
}

/** The non-blocking, honest notice: playback carries on underneath it. */
@Composable
private fun Banner(text: String, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

/** A decoder/renderer failure: say what happened instead of leaving a black surface. */
@Composable
private fun ErrorState(title: String, message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("Can't play this", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
    }
}

private fun streamNote(target: PlaybackTarget): String {
    val why = target.reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
    val seek = if (target.restartSeekable) " Skip restarts it from the new point." else " No seeking yet."
    return "Playing a phone-friendly stream from the server$why.$seek"
}

/** Build the [MediaItem], hinting the container MIME when the target carries one. */
private fun buildMediaItem(target: PlaybackTarget, title: String): MediaItem {
    val builder = MediaItem.Builder()
        .setUri(target.contentUrl)
        .setMediaId(target.contentUrl)
    MediaMime.of(target.mimeType)?.let { builder.setMimeType(it) }
    builder.setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder().setTitle(title).build(),
    )
    return builder.build()
}

/**
 * Map a server MIME to a Media3 container hint where a well-known one applies; an
 * unknown/absent MIME returns null and ExoPlayer sniffs the stream instead.
 */

/** One report from the player: the source position and what just happened. */
data class PlaybackProgress(val seconds: Double, val completed: Boolean, val event: Event) {
    enum class Event { TICK, PAUSED, RESUMED, ENDED, LEFT }
}

private const val PROGRESS_TICK_MS = 5_000L
