package com.nuvio.tv.music

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Device storage and audio downloads for the Music page. */
@Singleton
class MusicPlatform @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: MusicHttp,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadLibrary(): String? = preferences.getString(LIBRARY_KEY, null)

    fun saveLibrary(json: String) {
        preferences.edit().putString(LIBRARY_KEY, json).apply()
    }

    suspend fun downloadAudio(url: String, id: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "music").apply { mkdirs() }
            val safeId = id.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(80).ifBlank { "track" }
            val existing = listOf(File(dir, "$safeId.m4a"), File(dir, "$safeId.webm"))
                .firstOrNull { it.isFile && it.length() > 1024L }
            if (existing != null) return@withContext existing.absolutePath
            val tmp = File(dir, "$safeId.part")
            if (tmp.exists()) tmp.delete()
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", headers["User-Agent"] ?: USER_AGENT)
                    .header("Accept", "*/*")
                if (headers["Range"] == null) builder.header("Range", "bytes=0-")
                http.client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body ?: return@withContext null
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    if (tmp.length() <= 1024L) {
                        tmp.delete()
                        return@withContext null
                    }
                    val extension = when {
                        response.header("Content-Type").orEmpty().contains("webm", ignoreCase = true) -> "webm"
                        else -> "m4a"
                    }
                    val file = File(dir, "$safeId.$extension")
                    if (file.exists()) file.delete()
                    if (!tmp.renameTo(file)) {
                        tmp.delete()
                        return@withContext null
                    }
                    file.absolutePath
                }
            } catch (_: Exception) {
                tmp.delete()
                null
            }
        }

    fun fileExists(path: String): Boolean = runCatching { File(path).isFile }.getOrDefault(false)

    fun deleteFile(path: String) {
        runCatching { File(path).delete() }
    }

    private companion object {
        const val PREFERENCES_NAME = "nuvio_music"
        const val LIBRARY_KEY = "music_library"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"
    }
}
