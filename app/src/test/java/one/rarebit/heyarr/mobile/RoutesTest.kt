package one.rarebit.heyarr.mobile

import kotlinx.serialization.json.Json
import one.rarebit.heyarr.mobile.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    @Test fun contentTypesMapToHubs() {
        val table = mapOf(
            "movie" to Route.HUB_VIDEO, "series" to Route.HUB_VIDEO, "episode" to Route.HUB_VIDEO,
            "music" to Route.HUB_MUSIC, "album" to Route.HUB_MUSIC, "track" to Route.HUB_MUSIC,
            "book" to Route.HUB_BOOKS, "comic" to Route.HUB_BOOKS, "audiobook" to Route.HUB_BOOKS,
            " Music " to Route.HUB_MUSIC, null to Route.HUB_VIDEO, "whatever" to Route.HUB_VIDEO,
        )
        table.forEach { (kind, hub) -> assertEquals("hubFor($kind)", hub, Route.hubFor(kind)) }
    }

    @Test fun everyHubListsAtLeastOneContentType() {
        Route.hubs.forEach { assertTrue(it, Route.contentTypesOf(it).isNotEmpty()) }
        assertEquals(listOf("movie", "series"), Route.contentTypesOf(Route.HUB_VIDEO))
    }

    @Test fun onlyThePlayerIsFullScreen() {
        assertTrue(Route.isFullScreen(Route.Player))
        assertFalse(Route.isFullScreen(Route.Home))
        assertFalse(Route.isFullScreen(Route.WorkDetail("w1")))
        assertFalse(Route.isFullScreen(null))
    }

    @Test fun routesWithArgumentsRoundTrip() {
        val detail = Route.WorkDetail(id = "w:1/x", title = "Dune", manage = true)
        assertEquals(detail, Json.decodeFromString<Route.WorkDetail>(Json.encodeToString(Route.WorkDetail.serializer(), detail)))
        val hub = Route.Hub(Route.HUB_BOOKS)
        assertEquals(hub, Json.decodeFromString<Route.Hub>(Json.encodeToString(Route.Hub.serializer(), hub)))
        val src = Route.SourceDetail("s9")
        assertEquals(src, Json.decodeFromString<Route.SourceDetail>(Json.encodeToString(Route.SourceDetail.serializer(), src)))
    }

    @Test fun titleHintDefaultsToNull() {
        assertEquals(null, Route.WorkDetail("w1").title)
        assertFalse(Route.WorkDetail("w1").manage)
    }
}
