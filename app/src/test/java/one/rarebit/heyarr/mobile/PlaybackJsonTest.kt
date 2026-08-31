package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.playback.PlaybackJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackJsonTest {

    @Test fun parsesDirectPlanWithContentUrl() {
        val body = """
            {"asset_id":"a1","device_id":"d1","decision":"DIRECT",
             "content_url":"https://h.example/api/v1/blobs/deadbeef/content","remote":false}
        """.trimIndent()
        val plan = PlaybackJson.parse(body)
        assertEquals("https://h.example/api/v1/blobs/deadbeef/content", plan.contentUrl)
        assertEquals("DIRECT", plan.decision)
        assertTrue(plan.isDirect)
        assertTrue(plan.isPlayable)
    }

    @Test fun parsesStartResponseToken() {
        val body = """{"session_id":"s1","content_url":"https://h/x","token":"play-tok","decision":"DIRECT"}"""
        val plan = PlaybackJson.parse(body)
        assertEquals("play-tok", plan.token)
        assertEquals("https://h/x", plan.contentUrl)
    }

    @Test fun nonDirectPlanHasNoUrl() {
        // A TRANSCODE/REMUX plan carries no content_url — not directly playable.
        val body = """{"decision":"TRANSCODE","remote":false}"""
        val plan = PlaybackJson.parse(body)
        assertNull(plan.contentUrl)
        assertFalse(plan.isDirect)
        assertFalse(plan.isPlayable)
    }

    @Test fun tolerantOfMissingFields() {
        val plan = PlaybackJson.parse("""{"remote":true}""")
        assertNull(plan.contentUrl)
        assertNull(plan.token)
        assertNull(plan.decision)
    }
}
