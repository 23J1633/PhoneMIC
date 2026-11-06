package com.phonemic

import org.junit.Assert.*
import org.junit.Test

class LanEndpointTest {
    @Test fun acceptsAddressesUsedInRoutedLans() {
        for ((input, host, port) in listOf(
            Triple(" 10.23.40.5 ", "10.23.40.5", 8765),
            Triple("172.20.1.5:8765", "172.20.1.5", 8765),
            Triple("receiver.office.example:9876", "receiver.office.example", 9876),
            Triple("https://192.168.1.5", "192.168.1.5", 8765),
            Triple("https://192.168.1.5:443/", "192.168.1.5", 443),
            Triple("fd12:3456::42", "fd12:3456::42", 8765),
            Triple("[fd12:3456::42]:9876", "fd12:3456::42", 9876)
        )) {
            val endpoint = LanEndpoint.parse(input)
            assertEquals(input, host, endpoint.host)
            assertEquals(input, port, endpoint.port)
            assertEquals(endpoint, LanEndpoint.parse(LanEndpoint.address(endpoint)))
        }
    }

    @Test fun rejectsAmbiguousAndInsecureTargets() {
        for (input in listOf("", "http://10.0.0.2", "https://user:pass@10.0.0.2", "10.0.0.2/path", "10.0.0.2?x=1", "10.0.0.2:99999", "10.0.0.2#fragment")) {
            try { LanEndpoint.parse(input); fail("Accepted $input") } catch (_: IllegalArgumentException) {}
        }
    }
}
