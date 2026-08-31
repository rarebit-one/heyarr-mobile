package one.rarebit.heyarr.mobile.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient

/**
 * The M10 player. Streams a [PlaybackTarget]'s blob over the authenticated,
 * range-capable endpoint through an ExoPlayer built on [HeyarrDataSource]. The
 * [PlayerView] supplies the transport controls (play/pause, a scrubber, seek) for
 * free, and handles **both** video (renders to the surface) and audio (controller-only,
 * with the item title shown) items.
 *
 * The ExoPlayer is owned by the composition and **released** when the screen leaves it
 * (`DisposableEffect`), so a backgrounded or dismissed player does not leak a codec or
 * hold the audio focus. Building the graph is compile-checked in CI; that a real codec
 * decodes the bytes is the phone-gated half.
 */
@UnstableApi
@Composable
fun PlayerScreen(
    target: PlaybackTarget,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    client: OkHttpClient = remember { OkHttpClient() },
) {
    val context = LocalContext.current

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
        onDispose { player.release() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
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
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        // Audio-only items have no video track — keep the controls up.
                        controllerShowTimeoutMs = if (target.isVideo) 3_000 else 0
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!target.isVideo) {
                Text(
                    "♪  $title",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
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
