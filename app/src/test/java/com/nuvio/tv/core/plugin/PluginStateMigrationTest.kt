package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.ExternalPluginEntry
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RepositoryType
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stored plugin state written before the CloudStream delta must keep loading as JavaScript. */
class PluginStateMigrationTest {
    private val moshi = Moshi.Builder().build()

    @Test
    fun oldRepositoryJsonDefaultsToNuvioJs() {
        val repo = moshi.adapter(PluginRepository::class.java)
            .fromJson("""{"id":"repo","name":"Repo","url":"https://example.org/manifest.json"}""")

        assertEquals(RepositoryType.NUVIO_JS, repo?.type)
        assertEquals(true, repo?.enabled)
    }

    @Test
    fun oldPluginEntryJsonHasNoHash() {
        val entry = moshi.adapter(ExternalPluginEntry::class.java)
            .fromJson("""{"internalName":"provider","url":"https://example.org/provider.cs3"}""")

        assertNull(entry?.fileHash)
        assertEquals("", entry?.name)
        assertEquals(1, entry?.version)
    }
}
