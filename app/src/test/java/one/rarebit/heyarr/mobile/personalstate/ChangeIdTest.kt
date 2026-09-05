package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.mobile.net.JsonScan
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * The content-addressed change/snapshot id, against heyarr-core's `changeid.json`
 * vectors. The node re-derives these and refuses a mismatch, so a byte-identical
 * result here is the difference between a write being stored and a write being
 * rejected. The vectors give `parents`/`frontier` non-canonical, so this also
 * proves [ChangeId.canonicalParents].
 */
class ChangeIdTest {
    @Test
    fun changeIds() {
        val body = Vectors.load("changeid.json")
        var seen = 0
        for (v in JsonScan.objectsOf(body, listOf("changes"))) {
            val space = JsonScan.stringField(v, "space_id")!!
            val parents = PsJson.stringArray(v, "parents")
            val ct = Base64.getDecoder().decode(JsonScan.stringField(v, "ciphertext_b64")!!)
            val want = JsonScan.stringField(v, "change_id")!!
            assertEquals("change ${JsonScan.stringField(v, "name")}", want, ChangeId.computeChange(space, parents, ct))
            seen++
        }
        assertEquals("expected several change vectors", true, seen >= 4)
    }

    @Test
    fun snapshotIds() {
        val body = Vectors.load("changeid.json")
        var seen = 0
        for (v in JsonScan.objectsOf(body, listOf("snapshots"))) {
            val space = JsonScan.stringField(v, "space_id")!!
            val frontier = PsJson.stringArray(v, "frontier")
            val ct = Base64.getDecoder().decode(JsonScan.stringField(v, "ciphertext_b64")!!)
            val want = JsonScan.stringField(v, "snapshot_id")!!
            assertEquals("snapshot ${JsonScan.stringField(v, "name")}", want, ChangeId.computeSnapshot(space, frontier, ct))
            seen++
        }
        assertEquals("expected snapshot vectors", true, seen >= 2)
    }
}
