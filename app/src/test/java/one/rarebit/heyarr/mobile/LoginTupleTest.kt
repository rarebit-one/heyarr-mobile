package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.login.LoginTuple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LoginTupleTest {

    @Test fun encodesKeysSortedIdBeforeRp() {
        val s = LoginTuple.encode(rp = "https://heyarr.example", id = "abc123")
        assertEquals("voidbind:login?id=abc123&rp=https%3A%2F%2Fheyarr.example", s)
    }

    @Test fun roundTrips() {
        val enc = LoginTuple.encode(rp = "https://a.b/c", id = "id-1")
        val p = LoginTuple.decode(enc)
        assertEquals("https://a.b/c", p.rp)
        assertEquals("id-1", p.id)
    }

    @Test fun decodeIsTolerantOfKeyOrder() {
        val p = LoginTuple.decode("voidbind:login?rp=https%3A%2F%2Fx.y&id=z")
        assertEquals("https://x.y", p.rp)
        assertEquals("z", p.id)
    }

    @Test fun rejectsWrongScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            LoginTuple.decode("https://not-voidbind?id=x&rp=y")
        }
    }

    @Test fun rejectsMissingField() {
        assertThrows(IllegalArgumentException::class.java) {
            LoginTuple.decode("voidbind:login?id=x")
        }
    }
}
