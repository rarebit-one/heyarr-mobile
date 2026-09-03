package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.mobile.search.FollowedSource

/**
 * UI state for the work detail screen. The transitions are pure functions on
 * [Loaded] so they are unit-tested on plain JVM without Compose or coroutines — the
 * same stance as `SearchUiState` / `AcquireState`.
 */
sealed interface WorkDetailUiState {
    data object Loading : WorkDetailUiState

    data class Error(val message: String) : WorkDetailUiState

    /**
     * The loaded detail. [work] is the `GET /works/{id}` view (the tapped row until
     * that read lands); [assets] the files with sizes; [wants] the desired items;
     * [source] the followed source that projects onto this work, if any (matched by
     * `work_id` from the Following list). [notices] are per-target one-line outcomes
     * of the last management action (keyed by want/asset id, or [NOTICE_WORK] for a
     * work-level one); [busy] the targets with an action in flight.
     */
    data class Loaded(
        val work: Work,
        val assets: List<WorkAsset> = emptyList(),
        val wants: List<Want> = emptyList(),
        val source: FollowedSource? = null,
        val notices: Map<String, String> = emptyMap(),
        val busy: Set<String> = emptySet(),
        /** Set when the assets or wants read failed; the header still shows. */
        val partialError: String? = null,
        /** True once `DELETE /works/{id}` took — the host pops back to the list (#428). */
        val deleted: Boolean = false,
    ) : WorkDetailUiState {

        fun starting(target: String): Loaded = copy(busy = busy + target, notices = notices - target)

        fun noticed(target: String, message: String): Loaded =
            copy(busy = busy - target, notices = notices + (target to message))

        /** The work's catalogue facts were corrected in place (a `PATCH /works/{id}` response). */
        fun workReplaced(updated: Work): Loaded =
            copy(work = updated, busy = busy - NOTICE_WORK, notices = notices + (NOTICE_WORK to "Saved."))

        /** The work was removed: mark it so the host navigates away (#428). */
        fun workDeleted(): Loaded = copy(busy = busy - NOTICE_WORK, deleted = true)

        /** A want was cancelled: drop it, clear its notice. */
        fun wantRemoved(wantId: String): Loaded =
            copy(wants = wants.filterNot { it.id == wantId }, busy = busy - wantId, notices = notices - wantId)

        /** A want was updated in place (a PATCH response). */
        fun wantReplaced(updated: Want): Loaded =
            copy(wants = wants.map { if (it.id == updated.id) updated else it }, busy = busy - updated.id, notices = notices - updated.id)

        /** An asset was removed from the catalog: drop it, clear its notice. */
        fun assetRemoved(assetId: String): Loaded =
            copy(assets = assets.filterNot { it.id == assetId }, busy = busy - assetId, notices = notices - assetId)

        /** Whether the tapped work can be played at all: any playable asset. */
        val playable: List<WorkAsset> get() = assets.filter { it.isPlayable }
    }

    companion object {
        const val NOTICE_WORK = "work"

        /**
         * The followed source behind a work, by `work_id`. A followed source projects
         * items onto wants for its work (ADR-0057) and the list view carries only the
         * `work_id`, so the join is client-side. Pure — unit-tested.
         */
        fun sourceFor(workId: String, sources: List<FollowedSource>): FollowedSource? =
            sources.firstOrNull { it.workId == workId }
    }
}
