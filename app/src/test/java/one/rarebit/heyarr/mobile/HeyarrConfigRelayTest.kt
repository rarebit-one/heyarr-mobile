package one.rarebit.heyarr.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class HeyarrConfigRelayTest {
    @Test fun relayDefaultsToTheNodesPairV1Mount() {
        assertEquals("http://h:7777/pair/v1", HeyarrConfig(baseUrl = "http://h:7777/").effectiveRelayBase)
        assertEquals("https://relay.example", HeyarrConfig(baseUrl = "http://h", relayBaseUrl = "https://relay.example/").effectiveRelayBase)
    }
}
