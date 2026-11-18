package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunM3uPromptTest {

    @Test
    fun `a first run without playlists is offered M3U setup`() {
        assertTrue(shouldOfferM3uSetup(hasSeenPrompt = false, hasPlaylists = false))
    }

    @Test
    fun `users with playlists are never offered setup`() {
        assertFalse(shouldOfferM3uSetup(hasSeenPrompt = false, hasPlaylists = true))
    }

    @Test
    fun `the offer is shown only once`() {
        assertFalse(shouldOfferM3uSetup(hasSeenPrompt = true, hasPlaylists = false))
    }

    @Test
    fun `the sources route opens Playlists and Guides`() {
        assertEquals("live_tv_settings", Screen.LiveTvSettings.createRoute())
        assertEquals("live_tv_settings?openSources=true", Screen.LiveTvSettings.createRoute(openSources = true))
    }
}
