package one.rarebit.heyarr.mobile.personalstate

/**
 * The identity a playlist / starred / history entry carries in the CRDT (issue #41,
 * Option B). Historically an entry was a bare **work id**; to express a per-track or
 * per-episode item we tag it with a [kind] so a resolver can branch — an
 * [ItemKind.ASSET] / [ItemKind.ITEM] id resolves asset→work (or item→work) for
 * display, a [ItemKind.WORK] id resolves straight through [LibraryClient.getWork].
 *
 * ## Wire encoding (the compact `kind:id`)
 * The CRDT — and the `internal/device/gateway` Subsonic surface behind it — store the
 * entry id as an **opaque string, verbatim** (heyarr-core `gateway/response.go`), so
 * the tag has to travel *inside* that one string and round-trip unchanged. The
 * encoding is:
 *
 *  - `work`  → **bare id** (no prefix), e.g. `018f…c3`
 *  - `asset` → `asset:<id>`, e.g. `asset:018f…9a`
 *  - `item`  → `item:<id>`
 *
 * Work is left bare on purpose: it is byte-for-byte what every entry looked like
 * before this change, so **every existing stored id keeps working** (an untagged
 * legacy id decodes as [ItemKind.WORK]) and a phone that adds only whole works writes
 * exactly what it did before — no migration, no duplicate entries, nothing for the
 * gateway to special-case. Only the genuinely new shapes (a track / an episode) take a
 * prefix, and the gateway renders whatever id it holds as a Subsonic song id either
 * way.
 *
 * [decode] recognises a prefix only when it is one of the known non-work kinds; any
 * other string — including a work id that happens to contain a `:` — is a bare work
 * id. Prefixes are drawn from a tiny fixed set, so a real heyarr id (a UUID or a
 * BLAKE3 hex) can never be mistaken for a tag.
 */
internal enum class ItemKind(val prefix: String) {
    WORK(""),
    ASSET("asset"),
    ITEM("item"),
}

internal data class ItemRef(val kind: ItemKind, val id: String) {
    /** The opaque CRDT/gateway id: bare for a work, `kind:id` otherwise. */
    fun encode(): String = if (kind == ItemKind.WORK) id else "${kind.prefix}:$id"

    companion object {
        fun work(id: String) = ItemRef(ItemKind.WORK, id)
        fun asset(id: String) = ItemRef(ItemKind.ASSET, id)
        fun item(id: String) = ItemRef(ItemKind.ITEM, id)

        /** The tagged kinds, longest-lived first — WORK is the implicit bare default and not listed. */
        private val TAGGED = listOf(ItemKind.ASSET, ItemKind.ITEM)

        /** Parse a stored/opaque id back to its [ItemRef]; an untagged id is a legacy work id. */
        fun decode(raw: String): ItemRef {
            val colon = raw.indexOf(':')
            if (colon > 0) {
                val prefix = raw.substring(0, colon)
                val kind = TAGGED.firstOrNull { it.prefix == prefix }
                if (kind != null) return ItemRef(kind, raw.substring(colon + 1))
            }
            return ItemRef(ItemKind.WORK, raw)
        }
    }
}
