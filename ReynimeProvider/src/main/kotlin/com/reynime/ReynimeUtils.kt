package com.reynime

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

object ReynimeUtils {
    fun fixUrl(base: String, url: String): String {
        if (url.startsWith("http", true)) return url
        return runCatching { URI(base).resolve(url).toString() }.getOrDefault(url)
    }

    fun normalizeUrl(raw: String?, baseUrl: String, mainUrl: String): String {
        val url = raw?.trim().orEmpty()
        if (url.isBlank()) return ""
        var cleaned = cleanEscaped(url)
        if (cleaned.startsWith("//")) cleaned = "https:$cleaned"
        if (!cleaned.startsWith("http", true) && !cleaned.startsWith("javascript:", true)) {
            cleaned = fixUrl(baseUrl.ifBlank { mainUrl }, cleaned)
        }
        return cleaned
    }

    fun cleanEscaped(value: String): String = value
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\n", "\n")
        .replace("\\r", "")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .trim()

    fun decodeBase64Payloads(text: String): List<String> {
        val matches = Regex("""([A-Za-z0-9+/]{40,}={0,2})""").findAll(text)
        return matches.mapNotNull { match ->
            runCatching {
                val decoded = String(Base64.getDecoder().decode(match.groupValues[1]))
                decoded.takeIf { it.contains("http") || it.contains("m3u8") || it.contains("mp4") }
            }.getOrNull()
        }.distinct().toList()
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun encode(data: ReynimePlaybackData): String {
        return JSONObject().apply {
            put("pageUrl", data.pageUrl)
            put("episodeId", data.episodeId)
            put("seriesId", data.seriesId)
            put("episodeNumber", data.episodeNumber)
            put("seedSlug", data.seedSlug)
            put("title", data.title)
        }.toString()
    }

    fun parsePlaybackData(data: String, mainUrl: String): ReynimePlaybackData {
        if (data.startsWith("http", true)) return ReynimePlaybackData(pageUrl = data)
        return runCatching {
            val json = JSONObject(data)
            ReynimePlaybackData(
                pageUrl = json.optString("pageUrl", mainUrl),
                episodeId = json.optString("episodeId").ifBlank { null },
                seriesId = json.optString("seriesId").ifBlank { null },
                episodeNumber = json.optString("episodeNumber").ifBlank { null },
                seedSlug = json.optString("seedSlug").ifBlank { null },
                title = json.optString("title").ifBlank { null }
            )
        }.getOrElse { ReynimePlaybackData(pageUrl = mainUrl) }
    }

    fun extractEpisodeNumber(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return Regex("""(?:episode|ep)[^\d]*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
            ?: Regex("""\b(\d{1,4})\b""").find(text)?.groupValues?.getOrNull(1)
    }

    fun isDirectVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(".m3u8", ".mp4", ".mkv", ".webm", "/hls/", "manifest").any { lower.contains(it) }
    }

    fun isSubtitleUrl(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(".srt", ".vtt", ".ass").any { lower.contains(it) }
    }

    fun shouldSkipUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.isBlank() ||
            lower.startsWith("javascript:") ||
            lower.startsWith("mailto:") ||
            lower.startsWith("tel:") ||
            lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("instagram.com") ||
            lower.contains("/login") ||
            lower.contains("/register")
    }
}
