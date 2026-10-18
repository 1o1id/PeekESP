package com.rhshourav.peekesp

import com.rhshourav.peekesp.data.RelayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayConfigTest {
    @Test fun trimsSpacesAndTrailingSlashes() {
        assertEquals("https://relay.example.com", RelayConfig.clean("  https://relay.example.com//  "))
    }

    @Test fun keepsAPath() {
        assertEquals("https://example.com/peek", RelayConfig.clean("https://example.com/peek/"))
    }

    @Test fun rejectsPlainHttpAndOtherSchemes() {
        assertNull(RelayConfig.clean("http://relay.example.com"))
        assertNull(RelayConfig.clean("ftp://relay.example.com"))
        assertNull(RelayConfig.clean("relay.example.com"))
    }

    @Test fun rejectsMissingHostAndGarbage() {
        assertNull(RelayConfig.clean("https://"))
        assertNull(RelayConfig.clean("https://exa mple.com"))
        assertNull(RelayConfig.clean(""))
    }
}