package one.rarebit.heyarr.mobile.playback

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/**
 * The M10 player. Streams a [PlaybackTarget] over the authenticated, range-capable
 * endpoint through an ExoPlayer built on [HeyarrDataSource]. The [PlayerView]
 * supplies the transport controls and handles **both** video (renders to the
 * surface) and audio (controller-only, with the item title shown) items.
 *
 * The screen draws edge-to-edge but keeps everything a finger needs — the Back
 * button, the title, the transport controls, the banner — inside
 * `WindowInsets.safeDrawing` (status bar, navigation bar, the camera cutout in
 * landscape), so the Back button is never under the status bar.
 *
 * It is HONEST about what the phone can't do (heyarr-core #432): a track group Media3
 * has no decoder for (the AC-3 5.1 case) raises a banner via [PlaybackDiagnostics]
 * and [onIssue] (so the app can re-plan for a stream), a decoder/renderer failure
 * shows an error state instead of a black surface, and a video stream that reaches
 * READY without ever rendering a frame (AVI) says so after a short grace.
 *
 * The ExoPlayer is owned by the composition and **released** when the screen leaves it
 * (`DisposableEffect`), so a backgrounded or dismissed player does not leak a codec or
 * hold the audio focus.
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
) {
    val context = LocalContext.current

    // Per-target verdicts: a new target (a re-planned stream) starts clean.
    var localIssue by remember(target.contentUrl) { mutableStateOf<String?>(null) }
    var errorText by remember(target.contentUrl) { mutableStateOf<String?>(null) }
    var renderedFrame by remember(target.contentUrl) { mutableStateOf(false) }
    var reachedReady by remember(target.contentUrl) { mutableStateOf(false) }
    var noFrame by remember(target.contentUrl) { mutableStateOf(false) }

    val player = remember(target.contentUrl) {
        val dataSourceFactory: DataSource.Factory = HeyarrDataSource.factory(client, target)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(buildMediaItem(target, title))
                prepare()
                playWhenReady = true
            }
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
                val issue = PlaybackDiagnostics.assess(groups, target)
                if (issue != null && localIssue == null) {
                    localIssue = issue.message
                    onIssue(issue)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorText = PlaybackDiagnostics.describeError(error.errorCodeName, error.message, target)
            }

            override fun onRenderedFirstFrame() { renderedFrame = true }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) reachedReady = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // AVI-with-no-video: READY, duration known, picture never comes.
    LaunchedEffect(reachedReady, target.contentUrl) {
        if (!reachedReady || !target.isVideo) return@LaunchedEffect
        delay(PlaybackDiagnostics.NO_FRAME_GRACE_MS)
        if (!renderedFrame) noFrame = true
    }

    val bannerText = banner ?: localIssue ?: noFrame.takeIf { it }?.let { PlaybackDiagnostics.noFrameMessage(target) }
        ?: target.takeIf { it.origin == PlaybackTarget.Origin.STREAM }?.let { streamNote(it) }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (errorText != null) {
            ErrorState(title = title, message = errorText!!, onBack = onBack)
            return@Box
        }
        if (landscape && target.isVideo) {
            // Landscape video: the surface fills the screen; the controls (a PlayerView
            // draws its own) and our overlays stay out of the cutout and the nav bar.
            PlayerSurface(player, target, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
            Row(
                modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack)
                Text(
                    title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            bannerText?.let {
                Banner(it, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp))
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
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth().let {
                        // Give video a 16:9 stage; an audio item just needs the transport bar.
                        if (target.isVideo) it.aspectRatio(16f / 9f) else it
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    PlayerSurface(player, target, Modifier.fillMaxWidth())
                    if (!target.isVideo) {
                        Text(
                            "♪  $title", color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
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
                    // A node-repackaged stream can't seek in v1: no ±10 s, a scrubber
                    // that shows position but refuses a drag.
                    setShowFastForwardButton(false)
                    setShowRewindButton(false)
                    findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.isEnabled = false
                }
            }
        },
        modifier = modifier,
    )
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
    return "Playing a phone-friendly stream from the server$why — no seeking yet."
}

/** Build the [MediaItem], hinting the container MIME when the target carries one. */
private fun buildMediaItem(target: PlaybackTarget, title: String): MediaItem {
    val builder = MediaItem.Builder()
        .setUri(target.contentUrl)
        .setMediaId(target.contentUrl)
    mediaMimeType(target.mimeType)?.let { builder.setMimeType(it) }
    builder.setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder().setTitle(title).build(),
    )
    return builder.build()
}

/**
 * Map a server MIME to a Media3 container hint where a well-known one applies; an
 * unknown/absent MIME returns null and ExoPlayer sniffs the stream instead.
 */
private fun mediaMimeType(mime: String?): String? = when (mime?.lowercase()) {
    "video/mp4", "audio/mp4", "video/quicktime" -> MimeTypes.VIDEO_MP4
    "video/webm", "audio/webm" -> MimeTypes.VIDEO_WEBM
    "video/x-matroska", "video/matroska" -> MimeTypes.VIDEO_MATROSKA
    "audio/mpeg", "audio/mp3" -> MimeTypes.AUDIO_MPEG
    "audio/flac" -> MimeTypes.AUDIO_FLAC
    "audio/ogg", "application/ogg" -> MimeTypes.AUDIO_OGG
    "audio/aac" -> MimeTypes.AUDIO_AAC
    else -> null
}
