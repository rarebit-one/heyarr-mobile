package one.rarebit.heyarr.mobile.nav

import kotlinx.serialization.Serializable

/**
 * The signed-in navigation graph, as typed routes.
 *
 * The rule that matters: a route carries **identifiers and display hints only**.
 * Never a [one.rarebit.heyarr.mobile.playback.PlaybackTarget], never a
 * [one.rarebit.heyarr.mobile.auth.Credential] — those live in app/ViewModel state and a
 * route re-resolves them by id. [Player] is argless for exactly that reason: what is
 * playing lives in the playback coordinator, the route only puts the player in front.
 */
sealed interface Route {

    // ── Top level (the bottom bar) ───────────────────────────────────────────────
    @Serializable data object Home : Route
    @Serializable data object Search : Route
    /** Library management — the old flat list, followed sources, the editorial surface. */
    @Serializable data object Manage : Route
    @Serializable data object Device : Route

    // ── Browse ───────────────────────────────────────────────────────────────────
    /** One media hub: [kind] is a [Hub] key (`video` | `music` | `books`). */
    @Serializable data class Hub(val kind: String) : Route

    /** One work. [title] is a hint so the screen can show something before it loads. */
    @Serializable data class WorkDetail(val id: String, val title: String? = null, val manage: Boolean = false) : Route

    // ── Music ────────────────────────────────────────────────────────────────────
    @Serializable data object Artists : Route
    @Serializable data class Artist(val name: String) : Route
    @Serializable data class Album(val workId: String, val title: String? = null) : Route

    // ── Books ────────────────────────────────────────────────────────────────────
    /** The reading entry point for one work (its readable files). */
    @Serializable data class Reader(val workId: String, val title: String? = null) : Route

    // ── Playlists (encrypted personal state) ─────────────────────────────────────
    /** This device's playlists — folded from the encrypted spaces it can decrypt. */
    @Serializable data object Playlists : Route

    /** One playlist. [spaceId] is the personal-state space; [title] a display hint. */
    @Serializable data class Playlist(val spaceId: String, val title: String? = null) : Route

    // ── Acquisition ──────────────────────────────────────────────────────────────
    @Serializable data object Wants : Route
    @Serializable data class WantDetail(val id: String) : Route

    // ── Followed sources ─────────────────────────────────────────────────────────
    @Serializable data object Following : Route
    @Serializable data class SourceDetail(val id: String) : Route

    // ── Full-screen ──────────────────────────────────────────────────────────────
    /** The player. Argless: the item lives in the playback coordinator. */
    @Serializable data object Player : Route

    companion object {
        const val HUB_VIDEO = "video"
        const val HUB_MUSIC = "music"
        const val HUB_BOOKS = "books"

        /** The hubs, in bar order. */
        val hubs: List<String> = listOf(HUB_VIDEO, HUB_MUSIC, HUB_BOOKS)

        /**
         * Which hub a server `content_type` (or a client `kind`) belongs to. Pure, so the
         * mapping is a table in a test rather than a surprise on a device. Unknown kinds
         * fall into video — the shelf that renders a poster and plays a file works for
         * anything.
         */
        fun hubFor(contentType: String?): String = when (contentType?.lowercase()?.trim()) {
            "music", "album", "track", "artist", "audio" -> HUB_MUSIC
            "book", "comic", "audiobook", "publication", "magazine", "paper", "document" -> HUB_BOOKS
            else -> HUB_VIDEO
        }

        /** The server content types a hub lists, in chip order. */
        fun contentTypesOf(hub: String): List<String> = when (hub) {
            HUB_MUSIC -> listOf("music")
            HUB_BOOKS -> listOf("book")
            else -> listOf("movie", "series")
        }

        /** A human label for a hub key. */
        fun hubLabel(hub: String): String = when (hub) {
            HUB_MUSIC -> "Music"
            HUB_BOOKS -> "Books"
            else -> "Video"
        }

        /** Routes that own the whole screen: no top bar, no bottom bar, no mini-player. */
        fun isFullScreen(route: Route?): Boolean = route is Player
    }
}
