package com.youtube

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object YouTubeUtils {
    private val duplicateSlashRegex = Regex("""(?<!:)//+""")

    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cookie" to "CONSENT=YES+cb.20210328-17-p0.en+FX+667; PREF=hl=id&gl=ID",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    fun normalizeUrl(value: String?, baseUrl: String = YouTubeSeeds.MAIN_URL): String {
        val clean = value.orEmpty().decodeJsonString().trim().trim('"', '\'')
        if (clean.isBlank() || clean.startsWith("javascript:", true) || clean.startsWith("data:", true)) return ""
        val normalized = when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> YouTubeSeeds.MAIN_URL + clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
        return normalized.replace(duplicateSlashRegex, "/")
    }

    fun channelVideosUrl(url: String): String {
        val clean = url.trimEnd('/')
        return when {
            clean.contains("/videos", true) -> clean
            clean.contains("/streams", true) -> clean
            clean.contains("/shorts", true) -> clean
            clean.contains("/playlist", true) -> clean
            clean.contains("/@", true) || clean.contains("/channel/", true) || clean.contains("/c/", true) || clean.contains("/user/", true) -> "$clean/videos"
            else -> clean
        }
    }

    fun rssUrl(channelId: String): String = "${YouTubeSeeds.MAIN_URL}/feeds/videos.xml?channel_id=$channelId"

    fun searchUrl(query: String): String = "${YouTubeSeeds.MAIN_URL}/results?search_query=${encode(query)}&sp=EgIQAQ%253D&gl=ID&hl=id"

    fun canonicalWatchUrl(videoId: String): String = "${YouTubeSeeds.MAIN_URL}/watch?v=$videoId"

    fun embedUrl(videoId: String): String = "${YouTubeSeeds.MAIN_URL}/embed/$videoId"

    fun videoIdFromUrl(url: String): String? {
        val decoded = decode(url)
        listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""/(?:embed|shorts|live)/([A-Za-z0-9_-]{11})"""),
            Regex("""/watch/([A-Za-z0-9_-]{11})""")
        ).forEach { regex ->
            regex.find(decoded)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    fun String.decodeJsonString(): String {
        var text = this
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#039;", "'")
            .replace("&nbsp;", " ")
        Regex("""\\u([0-9a-fA-F]{4})""").findAll(text).forEach { match ->
            val code = match.groupValues.getOrNull(1)?.toIntOrNull(16)
            if (code != null) text = text.replace(match.value, code.toChar().toString())
        }
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    fun cleanTitle(value: String?): String {
        return value.orEmpty()
            .decodeJsonString()
            .replace(Regex("""(?i)\s+-\s+YouTube\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun isBadYouTubeUrl(url: String): Boolean {
        val clean = url.lowercase()
        return clean.isBlank() ||
            clean.contains("/redirect?") ||
            clean.contains("accounts.google") ||
            clean.contains("policies.google") ||
            clean.contains("support.google") ||
            clean.contains("google.com/preferences")
    }

    fun findMatchingBrace(text: String, openIndex: Int): Int {
        if (openIndex !in text.indices || text[openIndex] != '{') return -1
        var depth = 0
        var inString = false
        var escaped = false
        for (index in openIndex until text.length) {
            val char = text[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                when (char) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
        }
        return -1
    }

    fun extractObjectAfter(text: String, marker: String): String? {
        val markerIndex = text.indexOf(marker)
        if (markerIndex < 0) return null
        val openIndex = text.indexOf('{', markerIndex + marker.length)
        if (openIndex < 0) return null
        val closeIndex = findMatchingBrace(text, openIndex)
        if (closeIndex <= openIndex) return null
        return text.substring(openIndex, closeIndex + 1)
    }

    fun extractObjectsByKey(json: String, key: String): List<String> {
        val objects = arrayListOf<String>()
        val marker = "\"$key\":{" 
        var index = json.indexOf(marker)
        while (index >= 0) {
            val openIndex = json.indexOf('{', index + marker.length - 1)
            val closeIndex = findMatchingBrace(json, openIndex)
            if (openIndex >= 0 && closeIndex > openIndex) {
                objects.add(json.substring(openIndex, closeIndex + 1))
                index = json.indexOf(marker, closeIndex + 1)
            } else {
                index = json.indexOf(marker, index + marker.length)
            }
        }
        return objects
    }

    fun firstJsonValue(text: String, key: String): String? {
        return Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.decodeJsonString()
            ?.takeIf { it.isNotBlank() }
    }

    fun textFromJsonObject(text: String, key: String): String? {
        val block = extractObjectAfter(text, "\"$key\":") ?: return null
        return Regex(""""simpleText"\s*:\s*"((?:\\.|[^"\\])*)""", RegexOption.IGNORE_CASE)
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.decodeJsonString()
            ?.takeIf { it.isNotBlank() }
            ?: Regex(""""text"\s*:\s*"((?:\\.|[^"\\])*)""", RegexOption.IGNORE_CASE)
                .find(block)
                ?.groupValues
                ?.getOrNull(1)
                ?.decodeJsonString()
                ?.takeIf { it.isNotBlank() }
    }
}
