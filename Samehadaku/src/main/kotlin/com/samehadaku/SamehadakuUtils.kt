package com.samehadaku

import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object SamehadakuUtils {
    private val duplicateSlashRegex = Regex("""(?<!:)//+""")
    private val rootRegex = Regex("""^https?://[^/]+""")

    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    fun normalizeUrl(value: String?, baseUrl: String, mainUrl: String = SamehadakuSeeds.MAIN_URL): String {
        val clean = value.orEmpty().cleanEscaped().trim().trim('"', '\'')
        if (clean.isBlank() || clean.equals("null", true) || clean.startsWith("javascript:", true) || clean.startsWith("data:", true)) return ""
        val normalized = when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> (rootRegex.find(baseUrl)?.value ?: mainUrl) + clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
        return normalized.replace(duplicateSlashRegex, "/")
    }

    fun buildPageUrl(template: String, page: Int): String {
        val cleanTemplate = template.trim()
        if (cleanTemplate.contains("%page%")) {
            val pagePart = if (page <= 1) "" else "page/$page/"
            return cleanTemplate.replace("%page%", pagePart).replace(duplicateSlashRegex, "/")
        }
        if (page <= 1) return cleanTemplate
        return cleanTemplate.trimEnd('/') + "/page/$page/"
    }

    fun titleBloatClean(value: String): String {
        return value.cleanEscaped()
            .replace(Regex("(?i)^\\s*(?:TV|OVA|ONA|Special|Movie)\\s+\\d+(?:\\.\\d+)?\\s+"), "")
            .replace(Regex("(?i)^\\s*TOP\\s*\\d+\\s+"), "")
            .replace(Regex("(?i)\\s+(?:Completed|Ongoing|Finished Airing|Currently Airing)\\s*$"), "")
            .replace(Regex("(?i)\\b(Nonton|Streaming|Download|Anime|Subtitle|Sub Indo|Sub Indonesia|Batch|HD|Terbaru)\\b"), " ")
            .replace(Regex("(?i)\\bSamehadaku\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':')
    }

    fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#8217;", "'")
            .replace("&#8211;", "-")
            .replace("&#8212;", "-")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun getType(value: String?): TvType {
        val clean = value.orEmpty()
        return when {
            clean.contains("movie", true) || clean.contains("film", true) -> TvType.AnimeMovie
            clean.contains("ova", true) || clean.contains("ona", true) || clean.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    fun getStatus(value: String?): ShowStatus? {
        val clean = value.orEmpty()
        return when {
            clean.contains("ongoing", true) || clean.contains("currently airing", true) -> ShowStatus.Ongoing
            clean.contains("completed", true) || clean.contains("finished", true) || clean.contains("tamat", true) -> ShowStatus.Completed
            else -> null
        }
    }

    fun parseYear(value: String?): Int? {
        return Regex("""(?:19|20)\d{2}""").find(value.orEmpty())?.value?.toIntOrNull()
    }

    fun parseEpisodeNumber(value: String?): Int? {
        val clean = value.orEmpty().cleanEscaped()
        listOf(
            Regex("""(?i)episode\s*(\d{1,4})"""),
            Regex("""(?i)eps?\s*(\d{1,4})"""),
            Regex("""(?:^|\D)(\d{1,4})(?:\D|$)""")
        ).forEach { regex ->
            regex.find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    fun String.fixQuality(): Int {
        val clean = uppercase().replace(" ", "")
        return when {
            clean.contains("4K") || clean.contains("2160") -> Qualities.P2160.value
            clean.contains("1440") -> 1440
            clean.contains("FULLHD") || clean.contains("1080") -> Qualities.P1080.value
            clean.contains("MP4HD") || clean == "HD" || clean.contains("720") -> Qualities.P720.value
            clean.contains("480") -> Qualities.P480.value
            clean.contains("360") -> Qualities.P360.value
            else -> clean.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
        }
    }

    fun isVideoUrl(url: String): Boolean {
        return url.contains(Regex("""\.(?:m3u8|mp4|mkv|webm)(?:[?#].*)?$""", RegexOption.IGNORE_CASE))
    }

    fun isSubtitleUrl(url: String): Boolean {
        return url.contains(Regex("""\.(?:srt|vtt|ass)(?:[?#].*)?$""", RegexOption.IGNORE_CASE))
    }

    fun shouldSkipUrl(url: String): Boolean {
        val clean = url.lowercase()
        return clean.isBlank() ||
            clean.startsWith("#") ||
            clean.contains("facebook.com") ||
            clean.contains("twitter.com") ||
            clean.contains("x.com") ||
            clean.contains("whatsapp.com") ||
            clean.contains("telegram") ||
            clean.contains("instagram.com") ||
            clean.contains("google.com/s2/favicons") ||
            clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") || clean.endsWith(".webp") || clean.endsWith(".gif") || clean.endsWith(".svg")
    }

    fun originOf(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(url.substringBefore("/", url))
    }

    fun logSafe(throwable: Throwable) {
        runCatching { logError(throwable) }
    }
}
