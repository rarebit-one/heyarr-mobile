package one.rarebit.heyarr.mobile

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.preview.FakeHeyarrTransport
import one.rarebit.heyarr.mobile.preview.Fixtures
import one.rarebit.heyarr.mobile.settings.InMemorySettingsStore
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.state.ExternalMetadata
import one.rarebit.heyarr.mobile.state.RecentSearches
import one.rarebit.heyarr.mobile.theme.HeyarrTheme
import one.rarebit.heyarr.mobile.ui.screens.DetailPlayback
import one.rarebit.heyarr.mobile.ui.screens.DetailScreen
import one.rarebit.heyarr.mobile.ui.screens.DetailState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders real screens on a device against the preview node (FakeHeyarrTransport), so
 * whatever only Android can break — a regex the phone's ICU engine refuses, a
 * nested-scroll layout Compose rejects at measure time — fails here, not in the
 * user's hands. Both crashes below shipped past the JVM unit tests (#47, #49).
 */
@RunWith(AndroidJUnit4::class)
class DetailScreenSmokeTest {
    @get:Rule val compose = createComposeRule()

    private fun session(): AppSession {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val api = HeyarrApi(FakeHeyarrTransport(), "https://node.example:7777", Credential.Session("tok"))
        return AppSession(
            api = api,
            defaultProfile = "everyday",
            settings = InMemorySettingsStore(),
            external = ExternalMetadata.none(),
            recent = RecentSearches(File(ctx.cacheDir, "smoke-recent")),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        )
    }

    private val noPlayback = DetailPlayback(
        playVideo = { _, _, _, _, _, _, _, _, _ -> },
        playAudio = { _, _, _ -> },
        read = { _, _ -> },
    )

    private fun renders(workId: String, type: String, title: String) {
        val s = session()
        compose.setContent {
            HeyarrTheme {
                DetailScreen(s, Route.Detail(workId, type, title), DetailState(workId), noPlayback, onBack = {}, onOpen = {}, onWant = { _, _ -> })
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(title).assertExists()
    }

    @Test fun aMovieDetailRenders() = renders(Fixtures.SINTEL, "MOVIE", "Sintel")

    @Test fun aFeedDetailRenders() = renders("w-cloudflare", "FEED", "Cloudflare Blog")

    @Test fun aSeriesDetailRenders() = renders(Fixtures.YELLOWSTONE, "SERIES", "Yellowstone")
}
