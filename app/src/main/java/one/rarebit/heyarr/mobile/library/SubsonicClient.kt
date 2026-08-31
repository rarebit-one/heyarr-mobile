package one.rarebit.heyarr.mobile.library

/**
 * STUB for heyarr's **Subsonic** compatibility reach — the other way a client can
 * browse the library (the mobile-client contract lists Subsonic/OPDS/DLNA as compat
 * adapters alongside the native `/api/v1`). Per the plan's DECISIONS LOG the
 * first-party client is the product and compat adapters are *reach*, so this
 * scaffold demonstrates the **native** path ([LibraryClient]) and leaves Subsonic as
 * a documented seam.
 *
 * Subsonic browse is the classic `/rest/getAlbumList2` / `/rest/getMusicDirectory`
 * surface with `?u=&t=&s=&f=json` auth params. Wiring it (and choosing whether to
 * ship it at all, vs. leaning entirely on native) is a follow-up — the value of this
 * stub is to mark the fork in the road so it is a decision, not an omission.
 */
class SubsonicClient(
    @Suppress("unused") private val baseUrl: String,
) {
    /** The Subsonic REST base. Native browse ([LibraryClient]) is the demonstrated path. */
    fun restBase(): String = baseUrl.trimEnd('/') + "/rest"

    /** TODO(subsonic-reach): implement getAlbumList2 → a browse list, if we ship this reach. */
    fun listAlbums(): List<Work> =
        throw NotImplementedError("Subsonic reach is a documented stub; use LibraryClient (native /api/v1/works).")
}
