package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.device.PairInvite
import one.rarebit.voidbind.Invite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanned/pasted-invite gate. Valid inputs are minted by the library's own
 * [Invite.encode] (the wire contract lives there, not here); the near-misses a camera
 * is likely to see are rejected with a message that names the problem.
 */
class PairInviteTest {
    private val relay = "http://192.168.16.224:7777/pair"
    private val session = "s3ss10n"
    private val salt = ByteArray(16) { it.toByte() }
    private val invite = Invite.encode(relay, session, salt)

    private fun invalid(raw: String): String {
        val r = PairInvite.check(raw)
        assertTrue("expected Invalid for <$raw>, got $r", r is PairInvite.Invalid)
        return (r as PairInvite.Invalid).message
    }

    @Test
    fun `a library-minted invite is accepted verbatim with its parts`() {
        val r = PairInvite.check(invite)
        assertTrue(r is PairInvite.Valid)
        r as PairInvite.Valid
        assertEquals(invite, r.inviteQr)
        assertEquals(relay, r.relay)
        assertEquals(session, r.session)
    }

    @Test
    fun `surrounding whitespace from a paste is trimmed, the tuple itself untouched`() {
        val r = PairInvite.check("  \n$invite\n ")
        assertTrue(r is PairInvite.Valid)
        assertEquals(invite, (r as PairInvite.Valid).inviteQr)
    }

    @Test
    fun `key order does not matter - a Go-rendered invite parses too`() {
        val goStyle = "voidbind:pair?relay=http%3A%2F%2Fmac.local%3A8788&salt=" +
            "000102030405060708090a0b0c0d0e0f&session=abc&v=2"
        val r = PairInvite.check(goStyle)
        assertTrue("$r", r is PairInvite.Valid)
        assertEquals("http://mac.local:8788", (r as PairInvite.Valid).relay)
        assertEquals("abc", r.session)
    }

    @Test
    fun `empty input is refused with a prompt to scan or paste`() {
        assertTrue(invalid("").contains("scan", ignoreCase = true))
        assertTrue(invalid("   \n").contains("paste", ignoreCase = true))
    }

    @Test
    fun `a Voidbind LOGIN tuple is named as the wrong kind of code`() {
        val msg = invalid("voidbind:login?id=abc&rp=https%3A%2F%2Fheyarr")
        assertTrue(msg, msg.contains("LOGIN"))
        assertTrue(msg, msg.contains("pair-initiate"))
    }

    @Test
    fun `an arbitrary QR payload is refused and briefly echoed`() {
        val msg = invalid("https://example.com/menu")
        assertTrue(msg, msg.contains("Not a Voidbind pairing invite"))
        assertTrue(msg, msg.contains("https://example.com/menu"))
        assertTrue(msg, msg.contains("voidbind:pair?v=2"))
    }

    @Test
    fun `a long foreign payload is only peeked at, never echoed whole`() {
        val long = "x".repeat(500)
        val msg = invalid(long)
        assertTrue(msg, msg.contains("x".repeat(40) + "…"))
        assertTrue(msg, !msg.contains("x".repeat(41)))
    }

    @Test
    fun `wrong version, short salt and missing session are the parser's refusals`() {
        val v1 = invite.replace("v=2", "v=1")
        assertTrue(invalid(v1).contains("version"))

        val shortSalt = Invite.encode(relay, session, salt).replace(
            "salt=000102030405060708090a0b0c0d0e0f", "salt=0001",
        )
        assertTrue(invalid(shortSalt).contains("salt"))

        val noSession = "voidbind:pair?v=2&relay=http%3A%2F%2Fr&salt=000102030405060708090a0b0c0d0e0f"
        assertTrue(invalid(noSession).contains("session"))
    }

    @Test
    fun `a truncated or garbled tuple is a malformed invite, not a crash`() {
        val msg = invalid("voidbind:pair?v=2&relay=http%3A%2F%2Fr&session=a&salt=zz")
        assertTrue(msg, msg.startsWith("Malformed pairing invite"))
        invalid("voidbind:pair?v=2&relay=%2")
    }
}
