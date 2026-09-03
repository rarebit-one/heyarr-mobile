package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.library.WorkPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `PATCH /works/{id}` body (#428). The load-bearing distinction is omit-vs-clear on
 * the year: an omitted field is left alone, `"year": 0` clears it — two different requests.
 */
class WorkPatchTest {

    @Test fun omitsUntouchedFields() {
        assertEquals("""{"title":"The Conversation"}""", WorkPatch(title = "The Conversation").body())
        assertEquals("""{"content_type":"movie"}""", WorkPatch(contentType = "movie").body())
    }

    @Test fun yearOmitIsNotYearClear() {
        // A set year is sent as typed; clearYear sends 0; neither sends nothing.
        assertEquals("""{"year":1974}""", WorkPatch(year = 1974).body())
        assertEquals("""{"year":0}""", WorkPatch(clearYear = true).body())
        assertEquals("{}", WorkPatch().body())
    }

    @Test fun clearYearWinsOverASetYear() {
        // Defensive: if both are given, the clear is the explicit intent.
        assertEquals("""{"year":0}""", WorkPatch(year = 1974, clearYear = true).body())
    }

    @Test fun combinesFieldsAndEscapesTheTitle() {
        assertEquals(
            """{"title":"A \"Quoted\" Title","year":2001,"content_type":"movie"}""",
            WorkPatch(title = "A \"Quoted\" Title", year = 2001, contentType = "movie").body(),
        )
    }

    @Test fun isEmptyOnlyWhenNothingChanges() {
        assertTrue(WorkPatch().isEmpty)
        assertFalse(WorkPatch(clearYear = true).isEmpty)
        assertFalse(WorkPatch(title = "x").isEmpty)
    }
}
