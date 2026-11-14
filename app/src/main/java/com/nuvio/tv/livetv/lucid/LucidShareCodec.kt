package com.nuvio.tv.livetv.lucid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A Lucid TV pack as one portable code. The JSON is gzipped and Base64-url encoded, so a small
 * setup fits in a message or a QR code; large setups use a `.lucid` file or the phone upload page.
 * No server is involved.
 */
object LucidShareCodec {

    const val PREFIX = "lucid1:"
    private const val MAX_INPUT = 1_500_000
    private const val MAX_PACK_BYTES = 4 * 1024 * 1024

    fun encode(packJson: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(packJson.toByteArray(Charsets.UTF_8)) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** Accepts a bare code, a `nuvio://lucid?pack=...` link, or a raw pack JSON. */
    fun decode(text: String?): List<LucidStoredSpec>? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_INPUT) return null
        val code = (when {
            raw.startsWith(PREFIX) -> raw
            raw.startsWith("nuvio://", ignoreCase = true) -> queryValue(raw, "pack")
            raw.startsWith("{") -> return LucidSpecCodec.decodePack(raw).ifEmpty { null }
            else -> return null
        } ?: return null).removePrefix(PREFIX)
        if (code.isEmpty() || code.length > MAX_INPUT) return null
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(code)
            val json = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_PACK_BYTES) error("That pack is too large.")
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
            LucidSpecCodec.decodePack(json)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun queryValue(url: String, key: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        return query.split('&').firstNotNullOfOrNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else {
                val k = part.substring(0, i)
                if (!k.equals(key, ignoreCase = true)) null
                else java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }
        }
    }
}
