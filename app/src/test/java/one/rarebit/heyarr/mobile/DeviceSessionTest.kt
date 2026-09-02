package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.auth.DeviceCredential
import one.rarebit.heyarr.mobile.auth.DeviceSession
import one.rarebit.heyarr.mobile.auth.PossessionProof
import one.rarebit.heyarr.mobile.net.DeviceAuthTransport
import one.rarebit.heyarr.mobile.net.HttpResponse
import one.rarebit.heyarr.mobile.net.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSessionTest {

    private val seed = ByteArray(32) { 0x02 }
    private val cert = "eyJ2IjoyfQ.c2ln" // any opaque token — the proof hashes it as presented
    private var signs = 0
    private val prover = DeviceCredential.Prover { signs++; JdkEd25519.sign(seed, it) }

    @Test fun reusesAProofUntilTheSkewWindowThenRemints() {
        var now = 1_000_000L
        val s = DeviceSession(cert, prover, clock = { now }, ttlSeconds = 120)
        val a = s.current()
        assertSame(a, s.current())
        now += 80 // 40s left: still outside the 30s skew → reuse
        assertSame(a, s.current())
        now += 15 // 25s left: inside the skew → proactive re-mint
        val b = s.current()
        assertNotEquals(a, b)
        assertEquals(2, signs)
        assertEquals(now + 120, PossessionProof.parse(b.proof).expiresAt)
    }

    /** Scripted transport: statuses served in order; records the headers it saw. */
    private class Scripted(vararg statuses: Int) : HttpTransport {
        private val queue = ArrayDeque(statuses.toList())
        val seen = ArrayList<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            seen += headers
            return HttpResponse(queue.removeFirst(), "")
        }
        override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) = get(url, headers)
    }

    @Test fun transportRestampsStaleDeviceHeaderAndRetriesOnceOn401() {
        var now = 1_000_000L
        // A ticking clock, so the re-minted proof (deterministic Ed25519) differs by `iat`.
        val s = DeviceSession(cert, prover, clock = { now++ }, ttlSeconds = 120)
        val stale = Credential.Device(cert, "stale.proof")
        val inner = Scripted(401, 200)
        val t = DeviceAuthTransport(inner) { s }

        val resp = t.get("https://h/api/v1/works", stale.asHeader())
        assertEquals(200, resp.status)
        assertEquals(2, inner.seen.size)
        // Neither attempt carried the client's stale snapshot; the second carried a re-minted proof.
        val first = inner.seen[0]["Authorization"]!!
        val second = inner.seen[1]["Authorization"]!!
        assertTrue(first.startsWith("Device $cert~") && !first.endsWith("stale.proof"))
        assertTrue(second.startsWith("Device $cert~"))
        assertNotEquals(first, second)
        assertEquals(2, signs)
    }

    @Test fun transportSurfacesASecond401AsIs() {
        val s = DeviceSession(cert, prover, clock = { 5L }, ttlSeconds = 120)
        val inner = Scripted(401, 401, 200)
        val resp = DeviceAuthTransport(inner) { s }.get("u", Credential.Device(cert, "x").asHeader())
        assertEquals(401, resp.status)
        assertEquals(2, inner.seen.size)
    }

    @Test fun transportLeavesBearerAndAnonymousRequestsAlone() {
        val inner = Scripted(401, 401)
        val t = DeviceAuthTransport(inner) { DeviceSession(cert, prover, { 1L }) }
        assertEquals(401, t.get("u", Credential.Session("tok").asHeader()).status)
        assertEquals(401, t.get("u").status)
        assertEquals("Bearer tok", inner.seen[0]["Authorization"])
        assertEquals(0, signs)
    }

    @Test fun noSessionMeansPassThrough() {
        val inner = Scripted(401)
        val t = DeviceAuthTransport(inner) { null }
        assertEquals(401, t.get("u", Credential.Device(cert, "p").asHeader()).status)
        assertEquals("Device $cert~p", inner.seen[0]["Authorization"])
    }
}
