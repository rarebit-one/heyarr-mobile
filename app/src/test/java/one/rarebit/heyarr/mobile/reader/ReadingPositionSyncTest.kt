package one.rarebit.heyarr.mobile.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.personalstate.FakeDeviceKey
import one.rarebit.heyarr.mobile.personalstate.FakeServer
import one.rarebit.heyarr.mobile.personalstate.IdentityCrypto
import one.rarebit.heyarr.mobile.personalstate.InMemorySpaceRegistry
import one.rarebit.heyarr.mobile.personalstate.PersonalStateClient
import one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator
import one.rarebit.heyarr.mobile.personalstate.SpaceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The reading-position sync over the real engine + node fake: what save writes, resume reads back. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPositionSyncTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private fun coordinator(): PersonalStateCoordinator {
        var space = 0
        var writer = 0
        val session = SpaceSession(
            client = PersonalStateClient(FakeServer(), "https://node.test", Credential.Session("t")),
            device = FakeDeviceKey(1),
            crypto = IdentityCrypto(),
            newSpaceId = { "space-${space++}" },
            newWriter = { "w${writer++}" },
        )
        return PersonalStateCoordinator(session, InMemorySpaceRegistry())
    }

    @Test
    fun saveThenResume() {
        val coord = coordinator()
        val sync = CoordinatorReadingPositionSync({ coord }, CoroutineScope(dispatcher), io = dispatcher)

        assertNull(sync.resume("book-1"))
        val locator = "{\"href\":\"/chap3.xhtml\",\"locations\":{\"progression\":0.42}}"
        sync.save("book-1", locator)
        assertEquals(locator, sync.resume("book-1"))

        // A later position wins (LWW), and an unrelated book is unaffected.
        sync.save("book-1", "{\"href\":\"/chap9.xhtml\"}")
        assertEquals("{\"href\":\"/chap9.xhtml\"}", sync.resume("book-1"))
        assertNull(sync.resume("book-2"))
    }

    @Test
    fun noOpResumesNothing() {
        assertNull(ReadingPositionSync.NoOp.resume("book-1"))
        ReadingPositionSync.NoOp.save("book-1", "{}") // does not throw
    }

    @Test
    fun nullCoordinatorIsSilent() {
        val sync = CoordinatorReadingPositionSync({ null }, CoroutineScope(dispatcher), io = dispatcher)
        assertNull(sync.resume("book-1"))
        sync.save("book-1", "{}") // no crash, no write
    }
}
