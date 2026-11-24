package com.nuvio.tv.core.plugin.cloudstream

import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.nuvio.tv.core.plugin.TestDiagnostics
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiProviderRunnerTest {
    private val loader = mockk<ExternalExtensionLoader>(relaxed = true)
    private val runner = ExternalExtensionRunner(
        loader,
        mockk<TmdbMetadataService>(relaxed = true)
    )

    @Test
    fun failingFirstProviderDoesNotHideSecondProvider() = runBlocking {
        val broken = mockk<TmdbProvider> { every { name } returns "Broken" }
        coEvery { broken.load(any()) } throws NoClassDefFoundError("optional extension dependency")

        val working = mockk<TmdbProvider> { every { name } returns "Working" }
        coEvery { working.load(any()) } returns mockk<MovieLoadResponse> {
            every { dataUrl } returns "opaque-provider-data"
        }
        val mediaUrl = "https://example.org/public-film.mp4"
        coEvery { working.loadLinks("opaque-provider-data", false, any(), any()) } answers {
            arg<(ExtractorLink) -> Unit>(3)(movieLink(mediaUrl))
            true
        }

        every { loader.loadExtension("fixture") } returns listOf(broken, working)
        every { loader.loadExtensionWithDiagnostics("fixture", any()) } returns listOf(broken, working)

        val normal = runner.execute("fixture", "12", "movie", null, null)
        val diagnostic = runner.executeWithDiagnostics(
            "fixture", "12", "movie", null, null, TestDiagnostics()
        )

        assertEquals(listOf(mediaUrl), normal.map { it.url })
        assertEquals(normal, diagnostic)
    }

    @Test
    fun everyProviderInOnePackageRuns() = runBlocking {
        val first = mockk<TmdbProvider> { every { name } returns "First" }
        val second = mockk<TmdbProvider> { every { name } returns "Second" }
        for ((api, host) in listOf(first to "first.example", second to "second.example")) {
            coEvery { api.load(any()) } returns mockk<MovieLoadResponse> {
                every { dataUrl } returns "data-${api.name}"
            }
            coEvery { api.loadLinks(any(), any(), any(), any()) } answers {
                arg<(ExtractorLink) -> Unit>(3)(movieLink("https://$host/film.mp4"))
                true
            }
        }
        every { loader.loadExtension("fixture") } returns listOf(first, second)

        val urls = runner.execute("fixture", "12", "movie", null, null).map { it.url }.toSet()

        assertEquals(
            setOf("https://first.example/film.mp4", "https://second.example/film.mp4"),
            urls
        )
    }

    @Test
    fun languageSelectionFiltersEachApiInsideOnePackage() = runBlocking {
        val store = mockk<com.nuvio.tv.data.local.PluginDataStore>()
        val french = mockk<TmdbProvider> { every { name } returns "French"; every { lang } returns "fr-FR" }
        val english = mockk<TmdbProvider> { every { name } returns "English"; every { lang } returns "en" }
        val universal = mockk<TmdbProvider> { every { name } returns "Universal"; every { lang } returns "multi" }
        every { loader.loadExtension("fixture") } returns listOf(french, english, universal)
        every { store.sourceLanguages } returns kotlinx.coroutines.flow.flowOf(setOf("en"))
        for (api in listOf(english, universal)) {
            coEvery { api.load(any()) } returns mockk<MovieLoadResponse> { every { dataUrl } returns "film" }
            coEvery { api.loadLinks(any(), any(), any(), any()) } answers {
                arg<(ExtractorLink) -> Unit>(3)(movieLink("https://example.org/${api.name}.mp4"))
                true
            }
        }
        val filteredRunner = ExternalExtensionRunner(
            loader,
            mockk<TmdbMetadataService>(relaxed = true),
            store
        )

        assertEquals(2, filteredRunner.execute("fixture", "12", "movie", null, null).size)
        io.mockk.coVerify(exactly = 0) { french.load(any()) }
        io.mockk.coVerify(exactly = 1) { english.load(any()) }
        io.mockk.coVerify(exactly = 1) { universal.load(any()) }
    }

    private fun movieLink(mediaUrl: String): ExtractorLink = mockk {
        every { url } returns mediaUrl
        every { name } returns "Film"
        every { source } returns "Fixture"
        every { type } returns ExtractorLinkType.VIDEO
        every { quality } returns 720
        every { headers } returns emptyMap()
        every { referer } returns ""
    }
}
