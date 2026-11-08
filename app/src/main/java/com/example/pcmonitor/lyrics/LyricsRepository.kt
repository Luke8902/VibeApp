package com.example.pcmonitor.lyrics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class LyricsProvider(val displayName: String) {
    LRCLIB("LRCLIB · synced"),
    LYRICS_OVH("lyrics.ovh · plain"),
}

class LyricsRepository {
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

    suspend fun find(
        title: String,
        artist: String,
        provider: LyricsProvider,
        durationMs: Long?,
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        when (provider) {
            LyricsProvider.LRCLIB -> {
                val query = "https://lrclib.net/api/search?q=" + URLEncoder.encode("$title $artist", "UTF-8")
                runCatching {
                    client.newCall(Request.Builder().url(query).header("User-Agent", "VibePCMonitor/1.0").build()).execute().use { response ->
                        if (!response.isSuccessful) return@use emptyList()
                        val results = JSONArray(response.body?.string().orEmpty())
                        for (index in 0 until results.length()) {
                            val item = results.optJSONObject(index) ?: continue
                            val synced = item.optString("syncedLyrics").takeIf { it.isNotBlank() } ?: continue
                            val parsed = LrcParser.parse(synced)
                            if (parsed.isNotEmpty()) return@use parsed
                        }
                        emptyList()
                    }
                }.getOrDefault(emptyList())
            }
            LyricsProvider.LYRICS_OVH -> {
                val encodedArtist = URLEncoder.encode(artist, "UTF-8").replace("+", "%20")
                val encodedTitle = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
                val url = "https://api.lyrics.ovh/v1/$encodedArtist/$encodedTitle"
                runCatching {
                    client.newCall(Request.Builder().url(url).header("User-Agent", "VibePCMonitor/1.0").build()).execute().use { response ->
                        if (!response.isSuccessful) return@use emptyList()
                        val text = JSONObject(response.body?.string().orEmpty()).optString("lyrics")
                        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
                        val interval = ((durationMs ?: 0L).takeIf { it > 0L }?.div(lines.size.coerceAtLeast(1)) ?: 4000L)
                            .coerceIn(1500L, 8000L)
                        lines.mapIndexed { index, line -> LyricLine(index * interval, line) }
                    }
                }.getOrDefault(emptyList())
            }
        }
    }

    suspend fun findAlbumArtwork(title: String, artist: String, album: String): Bitmap? =
        withContext(Dispatchers.IO) {
            if (artist.isBlank() || (album.isBlank() && title.isBlank())) return@withContext null
            val coverArtArchiveImage = runCatching {
                val query = buildString {
                    append("release:")
                    append('"')
                    append((album.ifBlank { title }).replace("\"", " "))
                    append('"')
                    append(" AND artist:")
                    append('"')
                    append(artist.replace("\"", " "))
                    append('"')
                }
                val musicBrainz = "https://musicbrainz.org/ws/2/release/?query=" +
                    URLEncoder.encode(query, "UTF-8") + "&fmt=json&limit=1"
                val releaseId = client.newCall(
                    Request.Builder().url(musicBrainz).header("User-Agent", "VibePCMonitor/1.0 (Android app)").build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("MusicBrainz lookup failed.")
                    JSONObject(response.body?.string().orEmpty())
                        .optJSONArray("releases")?.optJSONObject(0)?.optString("id")
                        ?.takeIf(String::isNotBlank)
                        ?: throw IOException("No matching release was found.")
                }
                val coverArtUrl = "https://coverartarchive.org/release/$releaseId"
                val imageUrl = client.newCall(
                    Request.Builder().url(coverArtUrl).header("User-Agent", "VibePCMonitor/1.0").build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Cover Art Archive lookup failed.")
                    JSONObject(response.body?.string().orEmpty())
                        .optJSONArray("images")?.optJSONObject(0)
                        ?.optString("image")?.takeIf(String::isNotBlank)
                        ?: throw IOException("No cover image was found.")
                }
                client.newCall(
                    Request.Builder().url(imageUrl).header("User-Agent", "VibePCMonitor/1.0").build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Cover image download failed.")
                    response.body?.byteStream()?.use(BitmapFactory::decodeStream)
                        ?: throw IOException("Cover image was empty.")
                }
            }
                .getOrNull()
            if (coverArtArchiveImage != null) return@withContext coverArtArchiveImage

            runCatching {
                val query = URLEncoder.encode("$artist ${album.ifBlank { title }}", "UTF-8")
                val deezerUrl = "https://api.deezer.com/search?q=$query"
                val imageUrl = client.newCall(
                    Request.Builder().url(deezerUrl).header("User-Agent", "VibePCMonitor/1.0").build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Deezer lookup failed.")
                    val results = JSONObject(response.body?.string().orEmpty()).optJSONArray("data")
                    val expectedAlbum = album.ifBlank { title }.trim()
                    val match = results?.let { items ->
                        (0 until items.length()).mapNotNull(items::optJSONObject).firstOrNull { item ->
                            val actualArtist = item.optJSONObject("artist")?.optString("name").orEmpty()
                            val actualAlbum = item.optJSONObject("album")?.optString("title").orEmpty()
                            actualArtist.equals(artist, ignoreCase = true) &&
                                (actualAlbum.equals(expectedAlbum, ignoreCase = true) ||
                                    actualAlbum.contains(expectedAlbum, ignoreCase = true) ||
                                    expectedAlbum.contains(actualAlbum, ignoreCase = true))
                        }
                    }
                    match?.optJSONObject("album")?.optString("cover_xl")
                        ?.takeIf(String::isNotBlank) ?: throw IOException("No matching Deezer cover was found.")
                }
                client.newCall(
                    Request.Builder().url(imageUrl).header("User-Agent", "VibePCMonitor/1.0").build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Deezer cover download failed.")
                    response.body?.byteStream()?.use(BitmapFactory::decodeStream)
                }
            }.getOrNull()
        }
}
