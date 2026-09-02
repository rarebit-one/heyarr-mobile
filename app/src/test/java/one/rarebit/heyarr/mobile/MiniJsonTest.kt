package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.login.LoginTuple
import one.rarebit.heyarr.mobile.login.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniJsonTest {

    /** Verbatim shape of the live heyarr-core `POST /login` body (Go HTML-escapes `&`). */
    private val liveCreate =
        """{"id":"f83b2db4adb4392c43a7a2274c33d799","qr":"voidbind:login?id=f83b2db4adb4392c43a7a2274c33d799&rp=http%3A%2F%2F192.168.16.224%3A7777"}"""

    @Test fun decodesUnicodeEscapesFromTheLiveBroker() {
        val qr = MiniJson.stringField(liveCreate, "qr")!!
        assertEquals(
            "voidbind:login?id=f83b2db4adb4392c43a7a2274c33d799&rp=http%3A%2F%2F192.168.16.224%3A7777",
            qr,
        )
        val parsed = LoginTuple.decode(qr)
        assertEquals("http://192.168.16.224:7777", parsed.rp)
        assertEquals("f83b2db4adb4392c43a7a2274c33d799", parsed.id)
    }

    @Test fun keepsSimpleEscapesAndNulls() {
        assertEquals("a\"b\nc<", MiniJson.stringField("""{"x":"a\"b\nc<"}""", "x"))
        assertNull(MiniJson.stringField("""{"x":null}""", "x"))
        assertNull(MiniJson.stringField("""{"y":"1"}""", "x"))
    }
}
