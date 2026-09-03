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

    @Test fun parsesOptionalPlaybackHandles() {
        val body = """
            [
              {"id":"w1","title":"Dune","media_type":"movie","blob_hash":"deadbeef","mime":"video/mp4"},
              {"id":"w2","title":"No Asset"}
            ]
        """.trimIndent()
        val works = WorksJson.parse(body)
        assertEquals("deadbeef", works[0].blobHash)
        assertEquals("video/mp4", works[0].mime)
        assertEquals(true, works[0].isPlayable)
        // A row without a hash is not directly playable.
        assertEquals(null, works[1].blobHash)
        assertEquals(false, works[1].isPlayable)
    }

    @Test fun parsesExternalIdsOnTheSingleWorkRead() {
        // GET /works/{id} inlines external_ids (#431); the list read carries none.
        val one = WorksJson.parseOne(
            """{"id":"w1","content_type":"movie","work_key":"k","title":"Dune","sort_title":"dune","year":2021,"external_ids":{"tmdb":"438631","imdb":"tt1160419"},"attributes":{},"created_at":"x","updated_at":"y"}""",
        )!!
        assertEquals(mapOf("tmdb" to "438631", "imdb" to "tt1160419"), one.externalIds)
        // No external_ids on a plain list row → empty, not an error.
        assertEquals(emptyMap<String, String>(), WorksJson.parse("""[{"id":"w2","title":"No ids"}]""")[0].externalIds)
    }
}
