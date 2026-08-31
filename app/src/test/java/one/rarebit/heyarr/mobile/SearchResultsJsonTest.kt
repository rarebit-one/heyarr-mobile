package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.search.SearchResultsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsJsonTest {

    @Test fun parsesWorksListWithTypeAndYear() {
        // The shape `GET /api/v1/works` returns: id, content_type, title, year.
        val body = """
            [
              {"id":"w1","content_type":"movie","title":"Dune","year":2021},
              {"id":"w2","content_type":"series","title":"Severance","year":2022}
            ]
        """.trimIndent()
        val results = SearchResultsJson.parse(body)
        assertEquals(2, results.size)
        assertEquals("w1", results[0].workId)
        assertEquals("Dune", results[0].title)
        assertEquals("movie", results[0].type)
        assertEquals(2021, results[0].year)
        assertEquals("series", results[1].type)
        assertEquals(2022, results[1].year)
    }

    @Test fun parsesWorksEnvelope() {
        val body = """{"works":[{"id":"a","title":"One","content_type":"podcast"}],"next":null}"""
        val results = SearchResultsJson.parse(body)
        assertEquals(1, results.size)
        assertEquals("a", results[0].workId)
        assertEquals("podcast", results[0].type)
    }

    @Test fun nullYearBecomesNull() {
        val results = SearchResultsJson.parse("""[{"id":"w","title":"T","year":null}]""")
        assertEquals(1, results.size)
        assertNull(results[0].year)
    }

    @Test fun titleFallsBackToNameThenId() {
        assertEquals("Blue Note", SearchResultsJson.parse("""[{"id":"x","name":"Blue Note"}]""")[0].title)
        assertEquals("only-id", SearchResultsJson.parse("""[{"id":"only-id"}]""")[0].title)
    }

    @Test fun readsPosterWhenPresentElseNull() {
        assertEquals("http://p/x.jpg", SearchResultsJson.parse("""[{"id":"x","poster_url":"http://p/x.jpg"}]""")[0].posterUrl)
        assertNull(SearchResultsJson.parse("""[{"id":"x","title":"T"}]""")[0].posterUrl)
    }

    @Test fun skipsElementsWithoutId() {
        val results = SearchResultsJson.parse("""[{"title":"orphan"},{"id":"ok","title":"Kept"}]""")
        assertEquals(1, results.size)
        assertEquals("ok", results[0].workId)
    }

    @Test fun followableHintFollowsContentType() {
        val series = SearchResultsJson.parse("""[{"id":"s","content_type":"series"}]""")[0]
        val movie = SearchResultsJson.parse("""[{"id":"m","content_type":"movie"}]""")[0]
        assertTrue(series.followable)
        assertTrue(!movie.followable)
    }

    @Test fun emptyOnNonArray() {
        assertEquals(0, SearchResultsJson.parse("""{"error":"nope"}""").size)
    }
}
