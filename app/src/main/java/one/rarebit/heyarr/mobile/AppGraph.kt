package one.rarebit.heyarr.mobile

import android.app.Application
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import one.rarebit.heyarr.mobile.net.AuthHeaderSource
import one.rarebit.heyarr.mobile.net.AuthInterceptor
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.net.OkHttpTransport
import one.rarebit.heyarr.mobile.playback.AudioPlayer
import one.rarebit.heyarr.mobile.playback.InProcessAudioPlayer
import one.rarebit.heyarr.mobile.settings.PrefsSettingsStore
import one.rarebit.heyarr.mobile.settings.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * The process-wide object graph — by hand, on purpose. A container would add a
 * compiler plugin and a second way of building objects for a single module with a
 * dozen ViewModels, and the one genuinely awkward binding (a credential that swaps
 * shape mid-session) is already solved by keying ViewModels on the credential class.
 *
 * What lives here is what must be ONE instance: the OkHttp client (connection pool,
 * and the [AuthInterceptor] that lets posters and range reads carry the credential
 * without ever seeing it), the settings store, and the live auth header source.
 */
@UnstableApi
class AppGraph(app: Application, scope: CoroutineScope) {

    val settings: SettingsStore = PrefsSettingsStore(app)

    /** Swapped by the ViewModel as the credential changes; read per request. */
    val authHeader = AuthHeaderSource()

    /** The configured node, resolved fresh each time so a Settings change applies. */
    fun baseUrl(): String =
        HeyarrConfig.resolve(settings.baseUrlOverride, settings.qualityProfileOverride).baseUrl

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(baseUrl = ::baseUrl, header = authHeader))
        .build()

    /** The raw transport over the shared client; the ViewModel wraps it for Device auth. */
    val rawTransport: HttpTransport = OkHttpTransport(okHttp)

    /** The audio queue — one player for the process, so music outlives the screen that started it. */
    val audio: AudioPlayer by lazy { InProcessAudioPlayer(app, okHttp, scope) }
}
