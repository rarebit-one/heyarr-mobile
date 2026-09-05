package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.consumption.Position
import one.rarebit.heyarr.mobile.reader.InMemoryReadingPositionStore
import one.rarebit.heyarr.mobile.reader.ReaderPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderPositionTest {
    @Test fun pageComesFromTheLocatorsPosition() {
        val locator = """{"href":"/ch3.xhtml","type":"application/xhtml+xml","locations":{"progression":0.42,"position":57,"totalProgression":0.31}}"""
        assertEquals(57, ReaderPosition.pageOf(locator))
        assertNull(ReaderPosition.pageOf("""{"href":"/a","locations":{"progression":0.1}}"""))
        assertNull(ReaderPosition.pageOf("""{"href":"/a","locations":{"position":0}}"""))
        assertNull(ReaderPosition.pageOf("""{"href":"/a"}"""))
        assertNull(ReaderPosition.pageOf(""))
    }

    @Test fun pagePositionsAreThePageUnit() {
        assertEquals(Position("57", "page"), Position.page(57))
        assertEquals(Position("0", "page"), Position.page(-2))
        assertEquals(57.0, Position.page(57).magnitude, 0.0)
    }

    @Test fun storeRoundTrips() {
        val s = InMemoryReadingPositionStore()
        assertNull(s.locator("a1"))
        s.put("a1", "{}")
        assertEquals("{}", s.locator("a1"))
    }
}
