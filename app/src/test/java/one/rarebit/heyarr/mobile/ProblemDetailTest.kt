package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.net.ProblemDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProblemDetailTest {

    @Test fun readsDetailFromProblemBody() {
        val body = """{"type":"about:blank","title":"Bad Request","status":400,"detail":"nope, not allowed"}"""
        assertEquals("nope, not allowed", ProblemDetail.of(body))
    }

    @Test fun nullWhenNoDetail() {
        assertNull(ProblemDetail.of("""{"title":"Bad Request","status":400}"""))
        assertNull(ProblemDetail.of(""))
        assertNull(ProblemDetail.of("not json"))
    }

    @Test fun messagePrefersDetailElseFallsBackToStatus() {
        assertEquals(
            "a followed source needs a feed identity",
            ProblemDetail.message("""{"detail":"a followed source needs a feed identity"}""", 400, "follow"),
        )
        assertEquals("follow failed: HTTP 404", ProblemDetail.message("", 404, "follow"))
    }
}
