package one.rarebit.heyarr.mobile.library

/**
 * A library entry as browsed from heyarr's native resources API
 * (`GET /api/v1/works`). Deliberately a thin projection — id, a display title, and a
 * kind — of the far richer server model (§ works/editions/assets). The scaffold's
 * job is to prove the browse list is wired to the contract, not to model the whole
 * catalog.
 */
data class Work(
    val id: String,
    val title: String,
    val kind: String? = null,
)
