package one.rarebit.heyarr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import one.rarebit.heyarr.mobile.nav.Route

/**
 * A poster: the image at [url] in the aspect its hub expects (2:3 for video and books,
 * square for music), with a per-kind glyph standing in while it loads or when the node
 * has none (a 404 on the artwork route is the normal case for an unlabelled work, not
 * an error worth a broken-image icon).
 */
@Composable
fun Poster(url: String?, kind: String?, contentDescription: String?, modifier: Modifier = Modifier) {
    val hub = Route.hubFor(kind)
    val ratio = if (hub == Route.HUB_MUSIC) 1f else 2f / 3f
    val box = modifier.aspectRatio(ratio).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
    if (url == null) {
        Placeholder(hub, box)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = box,
        loading = { Placeholder(hub, Modifier.fillMaxSize()) },
        error = { Placeholder(hub, Modifier.fillMaxSize()) },
    )
}

@Composable
private fun Placeholder(hub: String, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            when (hub) {
                Route.HUB_MUSIC -> "♫"
                Route.HUB_BOOKS -> "📖"
                else -> "🎬"
            },
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
