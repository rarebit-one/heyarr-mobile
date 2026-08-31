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

    /**
     * DELETE — needed by the followed-sources unfollow route
     * (`DELETE /api/v1/followed-sources/{id}?keep_archive=…`). Defaulted to a `405`
     * so the many existing test fakes (and any transport that never deletes) keep
     * compiling; the OkHttp actual and any fake exercising unfollow override it.
     */
    fun delete(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        HttpResponse(405, "")
}
