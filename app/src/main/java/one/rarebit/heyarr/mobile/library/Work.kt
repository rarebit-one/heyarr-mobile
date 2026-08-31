package one.rarebit.heyarr.mobile.library

/**
 * A library entry as browsed from heyarr's native resources API
 * (`GET /api/v1/works`). Deliberately a thin projection — id, a display title, and a
 * kind — of the far richer server model (§ works/editions/assets). The scaffold's
 * job is to prove the browse list is wired to the contract, not to model the whole
 * catalog.
 *
 * [blobHash] / [mime] are the OPTIONAL playback handles: when a browse row carries a
 * primary asset's content hash, the M10 player can stream it directly
 * (`/api/v1/blobs/{hash}/content`) — that is the "tap → it plays" path. When absent
 * (a work whose row does not inline an asset), playback resolves through the
 * enrolment-gated plan negotiation instead — see `playback/PlaybackClient`.
 */
data class Work(
    val id: String,
    val title: String,
    val kind: String? = null,
    /** A directly-streamable asset's BLAKE3 content hash, when the browse row carries one. */
    val blobHash: String? = null,
    /** The primary asset's MIME, when known — drives video-vs-audio and the container hint. */
    val mime: String? = null,
) {
    /** True when this row can be streamed directly (the player has a hash to point at). */
    val isPlayable: Boolean get() = !blobHash.isNullOrBlank()
}
