package one.rarebit.heyarr.mobile.search

/**
 * One hit from a **source-agnostic content search**. The user types what they want
 * (a show, a movie, a podcast, a channel); heyarr resolves it against the catalogue
 * and returns works — this is a thin projection of one, deliberately mirroring
 * [one.rarebit.heyarr.mobile.library.Work] but carrying the extra fields a "what do I
 * want to acquire" list needs (a [year] to disambiguate, a [posterUrl] when the
 * server has one).
 *
 * Crucially there is **no indexer/source field**: the user expresses intent, the
 * server routes to sources. A result is something you can then **Get once** (a
 * one-off acquisition) or **Follow** (an ongoing subscription) — see [AcquireClient].
 *
 * [workId] is heyarr's work id (from `GET /api/v1/works`), the handle a one-off want
 * points at. [type] is the server's `content_type` (movie, series, podcast, …) and
 * is the hint for whether Following (ongoing) makes sense.
 */
data class SearchResult(
    val workId: String,
    val title: String,
    val type: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
) {
    /**
     * A hint — not a hard rule — for whether an ongoing **Follow** is meaningful:
     * series/shows/podcasts/channels/feeds emit new items over time, a movie is a
     * one-off. The UI surfaces both actions regardless; this only steers emphasis.
     */
    val followable: Boolean
        get() = type?.lowercase()?.let { t ->
            FOLLOWABLE_TYPES.any { t.contains(it) }
        } ?: false

    private companion object {
        val FOLLOWABLE_TYPES = listOf("series", "show", "season", "episode", "podcast", "channel", "feed")
    }
}
