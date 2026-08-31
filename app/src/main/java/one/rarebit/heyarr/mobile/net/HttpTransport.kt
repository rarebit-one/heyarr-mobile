package one.rarebit.heyarr.mobile.net

/**
 * An opaque HTTP response: the status code and the raw body string.
 *
 * Shape deliberately mirrors voidbind-kmp's `net.HttpResponse` so that, when the
 * real `voidbind-client` artifact is published, the login seam can be swapped for
 * it with minimal churn (see settings.gradle.kts + login/VoidbindLogin.kt).
 */
data class HttpResponse(val status: Int, val body: String)

/**
 * The pluggable HTTP seam. `login/`, `library/`, `playback/` and `personalstate/`
 * code depend only on this interface, so unit tests inject a fake and the Android
 * app injects the OkHttp actual — no network in CI.
 *
 * Calls are BLOCKING by deliberate choice (mirrors voidbind-kmp): the app drives
 * them off the main thread (Dispatchers.IO).
 */
interface HttpTransport {
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
    fun post(url: String, body: String? = null, contentType: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse
}
