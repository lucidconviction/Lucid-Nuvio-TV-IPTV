package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvOrgUrlTest {

    @Test
    fun `duplicate matching ignores case and trailing slashes`() {
        assertEquals(
            "https://iptv-org.github.io/iptv/countries/us.m3u",
            normalizeIptvOrgUrl(" HTTPS://iptv-org.github.io/iptv/countries/US.m3u/ ")
        )
    }
}
