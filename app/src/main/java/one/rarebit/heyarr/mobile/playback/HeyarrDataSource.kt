package one.rarebit.heyarr.mobile.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient

/**
 * The bridge from a [PlaybackTarget] to a Media3 [DataSource.Factory] that streams the
 * authenticated, range-capable blob endpoint.
 *
 * Two things matter here and both are ADR-0013's design, not ours to reinvent:
 *
 *  1. **Auth on every read.** The blob endpoint requires the caller's credential, so
 *     the target's `Authorization` header is set as a *default request property* on
 *     the factory — ExoPlayer copies it onto every HTTP request it makes for this
 *     media item, including the many small range reads a seek produces.
 *  2. **Range/206 is Media3's, unchanged.** [OkHttpDataSource] issues `Range` headers
 *     and consumes `206 Partial Content` itself; a scrub becomes a fresh ranged read
 *     rather than a re-download from zero — the M10 win. We add auth and get seeking
 *     for free; we do not (and must not) add a player-shaped byte route or signed URL.
 *
 * Pure header assembly lives in [authRequestProperties] so the auth-injection contract
 * is unit-tested on plain JVM; wiring it into a live [OkHttpDataSource] and pulling a
 * real codec's bytes is the phone-gated half.
 */
@UnstableApi
object HeyarrDataSource {

    /**
     * The request-property map ExoPlayer applies to every (ranged) read for a target —
     * just the `Authorization` header today. Pure + unit-tested.
     */
    fun authRequestProperties(target: PlaybackTarget): Map<String, String> = target.authHeaders()

    /**
     * A Media3 [DataSource.Factory] for [target]: an OkHttp-backed HTTP data source
     * carrying the auth header on every range read. [client] is shared with the rest of
     * the app so connection pools and timeouts are one policy.
     */
    fun factory(client: OkHttpClient, target: PlaybackTarget): DataSource.Factory =
        OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(authRequestProperties(target))
}
