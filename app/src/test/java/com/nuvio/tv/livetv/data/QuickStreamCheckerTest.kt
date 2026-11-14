package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.data.QuickStreamChecker
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickStreamCheckerTest {

    private fun channel(url: String) = LiveChannel(
        key = url,
        sourceId = "test",
        sourceName = "Test",
        name = "Test",
        tvgId = null,
        tvgName = null,
        logo = null,
        groupId = "test",
        group = "Test",
        number = 1,
        url = url,
    )

    @Test
    fun notFoundIsDead() = runBlocking {
        QuickStreamChecker.clearCache()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val result = QuickStreamChecker.checkBatch(
                listOf(channel(server.url("/dead").toString())),
                assumeAliveOnUnknown = false,
            )
            assertFalse(result.values.single())
        }
    }

    @Test
    fun manifestIsAlive() = runBlocking {
        QuickStreamChecker.clearCache()
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                    .setBody("#EXTM3U\n#EXT-X-VERSION:3\n")
            )
            val result = QuickStreamChecker.checkBatch(
                listOf(channel(server.url("/live.m3u8").toString())),
                assumeAliveOnUnknown = false,
            )
            assertTrue(result.values.single())
        }
    }

    @Test
    fun unknownIsHiddenWhenTheCallerNeedsWorkingStreams() = runBlocking {
        QuickStreamChecker.clearCache()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(500))
            val url = server.url("/unknown").toString()
            assertFalse(
                QuickStreamChecker.checkBatch(
                    listOf(channel(url)),
                    assumeAliveOnUnknown = false,
                    timeoutMs = 2_000,
                    retryTimeoutMs = 2_000,
                ).values.single()
            )
        }
    }
}
