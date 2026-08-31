package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.library.WorksJson
import org.junit.Assert.assertEquals
import org.junit.Test

class WorksJsonTest {

    @Test fun parsesBareArray() {
        val body = """
            [
              {"id":"w1","title":"Dune","media_type":"movie"},
              {"id":"w2","name":"Blue Note","kind":"album"}
            ]
        """.trimIndent()
        val works = WorksJson.parse(body)
        assertEquals(2, works.size)
        assertEquals("w1", works[0].id)
        assertEquals("Dune", works[0].title)
        assertEquals("movie", works[0].kind)
        // Falls back to `name` for the title, and reads `kind`.
        assertEquals("Blue Note", works[1].title)
        assertEquals("album", works[1].kind)
    }

    @Test fun parsesItemsEnvelope() {
        val body = """{"items":[{"id":"a","title":"One"}],"next":null}"""
        val works = WorksJson.parse(body)
        assertEquals(1, works.size)
        assertEquals("a", works[0].id)
        assertEquals("One", works[0].title)
    }

    @Test fun skipsElementsWithoutId() {
        val body = """[{"title":"orphan"},{"id":"ok","title":"Kept"}]"""
        val works = WorksJson.parse(body)
        assertEquals(1, works.size)
        assertEquals("ok", works[0].id)
    }

    @Test fun titleFallsBackToIdWhenAbsent() {
        val works = WorksJson.parse("""[{"id":"only-id"}]""")
        assertEquals(1, works.size)
        assertEquals("only-id", works[0].title)
    }

    @Test fun toleratesNestedBracesInStrings() {
        // A nested object + a bracket inside a string must not fool the splitter.
        val body = """[{"id":"w","title":"A [weird] title","meta":{"x":"y"}}]"""
        val works = WorksJson.parse(body)
        assertEquals(1, works.size)
        assertEquals("A [weird] title", works[0].title)
    }

    @Test fun emptyOnNonArray() {
        assertEquals(0, WorksJson.parse("""{"error":"nope"}""").size)
    }
}
