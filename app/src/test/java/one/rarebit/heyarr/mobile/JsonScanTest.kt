package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.net.JsonScan
import one.rarebit.heyarr.mobile.net.Timestamps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonScanTest {

    @Test fun topLevelFieldNeverMatchesANestedSameNamedKey() {
        // An asset carries its own `id` AND a nested object with an `id`; the reader
        // must return the asset's, whichever comes first in the text.
        val obj = """{"acquisition":{"id":"nested","state":"x"},"id":"outer","size":12}"""
        assertEquals("outer", JsonScan.stringField(obj, "id"))
        assertEquals("nested", JsonScan.stringField(JsonScan.objectAt(obj, "acquisition")!!, "id"))
        assertEquals(12L, JsonScan.longField(obj, "size"))
        assertNull("no top-level state", JsonScan.stringField(obj, "state"))
    }

    @Test fun keyInAStringValueIsNotAKey() {
        val obj = """{"title":"the \"id\": trick","id":"real"}"""
        assertEquals("real", JsonScan.stringField(obj, "id"))
        assertEquals("the \"id\": trick", JsonScan.stringField(obj, "title"))
    }

    @Test fun nullAndMissingAreNull() {
        val obj = """{"a":null,"b":"x"}"""
        assertNull(JsonScan.stringField(obj, "a"))
        assertNull(JsonScan.stringField(obj, "c"))
        assertNull(JsonScan.boolField(obj, "a"))
    }

    @Test fun objectsOfReadsBareArrayAndEnvelope() {
        assertEquals(2, JsonScan.objectsOf("""[{"id":"1"},{"id":"2"}]""", listOf("items")).size)
        assertEquals(1, JsonScan.objectsOf("""{"items":[{"id":"1","nested":{"id":"z"}}],"next_cursor":"c"}""", listOf("items")).size)
        assertTrue(JsonScan.objectsOf("""{"error":"x"}""", listOf("items")).isEmpty())
    }

    @Test fun decodesEscapesThroughJsonEscapes() {
        assertEquals("Tom & Jerry", JsonScan.stringField("""{"t":"Tom & Jerry"}""", "t"))
    }

    @Test fun whitespaceAndNewlinesBetweenKeyAndValue() {
        val obj = "{\n  \"id\" :\n \"w1\",\n  \"year\" : 1999,\n  \"monitor\":\tfalse\n}"
        assertEquals("w1", JsonScan.stringField(obj, "id"))
        assertEquals(1999, JsonScan.intField(obj, "year"))
        assertEquals(false, JsonScan.boolField(obj, "monitor"))
    }

    // ── Timestamps ────────────────────────────────────────────────────────────────

    @Test fun parsesGoRfc3339WithAndWithoutFraction() {
        val base = java.time.Instant.parse("2026-09-01T11:00:30Z").toEpochMilli()
        assertEquals(base, Timestamps.epochMillis("2026-09-01T11:00:30Z"))
        assertEquals(base + 123, Timestamps.epochMillis("2026-09-01T11:00:30.123456Z"))
        assertEquals("an offset is honoured", base, Timestamps.epochMillis("2026-09-01T19:00:30+08:00"))
        assertNull(Timestamps.epochMillis("yesterday"))
        assertNull(Timestamps.epochMillis(null))
    }

    @Test fun recentFirstOrdersByStampWithUnknownsLast() {
        val items = listOf("a" to "2026-01-01T00:00:00Z", "b" to null, "c" to "2026-03-01T00:00:00Z", "d" to "bad", "e" to "2026-02-01T00:00:00Z")
        val ordered = Timestamps.recentFirst(items) { it.second }.map { it.first }
        assertEquals(listOf("c", "e", "a", "b", "d"), ordered)
    }
}
