package com.sad25kag.mynimeku

import com.lagradost.cloudstream3.Qualities
import com.lagradost.cloudstream3.USER_AGENT
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.jsoup.nodes.Element

object MynimekuUtils {
    fun pageHeaders(referer: String = MynimekuSeeds.MAIN_URL): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    fun imageHeaders(referer: String = MynimekuSeeds.MAIN_URL): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    fun mediaHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*"
    )

    fun mediaHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "*/*"
    )

    fun normalizeUrl(raw: String?, base: String = MynimekuSeeds.MAIN_URL): String {
        val clean = cleanCandidate(raw)
        if (clean.isBlank()) return MynimekuSeeds.MAIN_URL
        val fixed = when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> runCatching { URI(base).resolve(clean).toString() }.getOrDefault("${MynimekuSeeds.MAIN_URL}$clean")
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(base).resolve(clean).toString() }.getOrDefault("${MynimekuSeeds.MAIN_URL}/${clean.trimStart('/')}")
        }

        return fixed
            .replace("http://www.mynimeku.com", MynimekuSeeds.MAIN_URL, ignoreCase = true)
            .replace("https://mynimeku.com", MynimekuSeeds.MAIN_URL, ignoreCase = true)
            .replace("http://mynimeku.com", MynimekuSeeds.MAIN_URL, ignoreCase = true)
            .substringBefore("#")
    }

    fun resolveUrlOrNull(raw: String?, base: String = MynimekuSeeds.MAIN_URL): String? {
        val clean = cleanCandidate(raw)
        if (!isUsableValue(clean)) return null
        return normalizeUrl(clean, base)
    }

    fun cleanCandidate(raw: String?): String {
        return raw.orEmpty()
            .trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace(" ", "%20")
    }

    fun isUsableValue(value: String): Boolean {
        return value.isNotBlank() &&
            value != "#" &&
            !value.equals("none", true) &&
            !value.equals("null", true) &&
            !value.startsWith("javascript", true) &&
            !value.startsWith("about:", true) &&
            !value.startsWith("data:", true) &&
            !value.startsWith("blob:", true) &&
            !value.startsWith("mailto:", true)
    }

    fun isMynimekuContent(url: String): Boolean {
        val normalized = normalizeUrl(url)
        val lower = normalized.lowercase()
        val path = runCatching { URI(lower).path.orEmpty() }.getOrDefault(lower)
        if (!lower.startsWith(MynimekuSeeds.MAIN_URL.lowercase())) return false
        if (path.contains("/genre/") || path.contains("/tag/") || path.contains("/season/") ||
            path.contains("/studio/") || path.contains("/producer/") || path.contains("/licensor/") ||
            path.contains("/full-list") || path.contains("/latest-series") || path.contains("/page/") ||
            path.contains("/wp-json")
        ) return false

        return path.startsWith("/series/") || path.startsWith("/episode/") || path.startsWith("/anime/")
    }

    fun isAdOrTracker(url: String): Boolean {
        val lower = url.lowercase()
        val host = runCatching { URI(lower).host.orEmpty() }.getOrDefault("")
        val path = runCatching { URI(lower).path.orEmpty() }.getOrDefault(lower)

        if (MynimekuSeeds.AD_HOST_KEYWORDS.any { host.contains(it) }) return true
        if (MynimekuSeeds.SOCIAL_HOST_KEYWORDS.any { host.contains(it) }) return true
        if (path.contains("/ads/") || path.contains("/adserve") || path.contains("/banner/") || path.contains("/promo/")) return true
        if (lower.contains("slot") && !host.contains("mynimeku.com")) return true
        return false
    }

    fun isBadContentOrPlayer(url: String): Boolean {
        val lower = url.lowercase()
        if (isAdOrTracker(lower)) return true
        return lower.contains("mailto:") ||
            lower.contains("/wp-content/themes/") ||
            lower.endsWith(".css") ||
            lower.endsWith(".js") ||
            lower.endsWith(".ico") ||
            lower.endsWith(".svg") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".avif") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".woff") ||
            lower.endsWith(".woff2") ||
            lower.endsWith(".ttf")
    }

    fun isValidImage(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.startsWith("data:")) return false
        if (lower.contains("svg%3csvg")) return false
        if (lower.endsWith(".svg")) return false
        if (lower.contains("icon-mynimeku")) return false
        if (lower.contains("/assets/img/preview")) return false
        return !isAdOrTracker(lower)
    }

    fun Element.imageCandidate(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        val fromPicture = selectFirst("picture source[srcset]")
            ?.attr("srcset")
            ?.substringBefore(" ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val attrs = listOf(
            image?.attr("abs:data-src"),
            image?.attr("abs:data-lazy-src"),
            image?.attr("abs:data-original"),
            image?.attr("abs:data-echo"),
            image?.attr("abs:srcset")?.substringBefore(" "),
            fromPicture,
            image?.attr("abs:src"),
            image?.attr("data-src"),
            image?.attr("data-lazy-src"),
            image?.attr("data-original"),
            image?.attr("data-echo"),
            image?.attr("srcset")?.substringBefore(" "),
            image?.attr("src")?.substringBefore(" ")
        )
        return attrs.map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:", true) }
    }

    fun Element.iframeCandidate(): String? {
        return attr("data-litespeed-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
    }

    fun cleanTitle(raw: String): String {
        var title = raw
            .replace(Regex("""(?i)\b(?:subtitle indonesia|sub indo|streaming|download|nonton)\b"""), " ")
            .replace(Regex("""(?i)\s+episode\s*\d+(?:\.\d+)?\s*$"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '|')

        if (title.equals("MyNimeku", true)) title = ""
        return title
    }

    fun cleanEpisodeTitle(raw: String): String {
        return raw.replace(Regex("""(?i)\s+subtitle\s+indonesia\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '|')
    }

    fun isNoiseText(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.isBlank() ||
            lower == "mynimeku" ||
            lower == "home" ||
            lower == "search" ||
            lower == "download" ||
            lower.contains("fan discussion") ||
            lower.contains("0 komentar") ||
            lower.contains("simpan nama") ||
            lower.contains("bandar slot") ||
            lower.contains("depo 1x") ||
            lower.contains("komentar saya berikutnya")
    }

    fun parseEpisodeNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(?i)\b(?:episode|eps|ep)\s*[-:.]?\s*(\d+)\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?i)(?:episode|eps|ep)-(\d+)""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""^\s*0*(\d+)\s*$""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    fun qualityFrom(text: String): Int {
        return when {
            text.contains("2160", true) || text.contains("4K", true) -> Qualities.P2160.value
            text.contains("1080", true) -> Qualities.P1080.value
            text.contains("720", true) -> Qualities.P720.value
            text.contains("480", true) -> Qualities.P480.value
            text.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    fun isMyPlayerku(url: String): Boolean {
        return runCatching { URI(url).host.orEmpty().equals(MynimekuSeeds.PLAYER_HOST, true) }.getOrDefault(false)
    }

    fun isFileMydriveku(url: String): Boolean {
        return runCatching { URI(url).host.orEmpty().equals(MynimekuSeeds.FILE_HOST, true) }.getOrDefault(false)
    }

    fun mydrivekuToPlayer(url: String): String? {
        val lower = url.lowercase()
        val token = url.substringAfter("/api/v1/", "").trim('/')
        if (token.isBlank()) return null
        return when {
            lower.contains("/api/v1/drive/") -> "https://${MynimekuSeeds.PLAYER_HOST}/drive/${token.substringAfter("drive/")}"
            lower.contains("/api/v1/public/") -> "https://${MynimekuSeeds.PLAYER_HOST}/public/${token.substringAfter("public/")}"
            lower.contains("/api/v1/private/") -> "https://${MynimekuSeeds.PLAYER_HOST}/private/${token.substringAfter("private/")}"
            lower.contains("/api/v1/proxy/") -> "https://${MynimekuSeeds.PLAYER_HOST}/proxy/${token.substringAfter("proxy/")}"
            else -> null
        }
    }

    fun isGoogleDriveApiMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("www.googleapis.com/drive/v2/files/") && lower.contains("alt=media")
    }

    fun isWorkersMedia(url: String): Boolean {
        return runCatching { URI(url).host.orEmpty().lowercase().endsWith("workers.dev") }.getOrDefault(false)
    }

    fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(url.substringBefore("?").lowercase())
        return path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            path.endsWith(".webm") ||
            path.endsWith(".mkv") ||
            path.endsWith(".mov") ||
            lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("redirector.googlevideo.com/videoplayback") ||
            isGoogleDriveApiMedia(url) ||
            isWorkersMedia(url)
    }

    fun isM3u8(url: String): Boolean = url.substringBefore("?").lowercase().endsWith(".m3u8") || url.lowercase().contains(".m3u8?")

    fun isPlayableCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (isBadContentOrPlayer(lower)) return false
        return isDirectMedia(url) ||
            isMyPlayerku(url) ||
            isFileMydriveku(url) ||
            isGoogleDriveApiMedia(url) ||
            lower.contains("googlevideo.com") ||
            lower.contains("drive.google.com") ||
            lower.contains("blogger.com/video.g") ||
            lower.contains("/embed") ||
            lower.contains("embed/") ||
            lower.contains("player") ||
            lower.contains("stream") ||
            lower.contains("video") ||
            lower.contains("mp4upload") ||
            lower.contains("filemoon") ||
            lower.contains("streamtape") ||
            lower.contains("voe.sx") ||
            lower.contains("vidhide") ||
            lower.contains("dood")
    }

    fun canonical(url: String): String {
        return runCatching {
            val uri = URI(url)
            val host = uri.host.orEmpty().removePrefix("www.").lowercase()
            val path = uri.path.orEmpty().trimEnd('/').lowercase()
            "$host$path"
        }.getOrDefault(url.substringBefore("?").trimEnd('/').lowercase())
    }

    fun encodeBundle(referer: String, players: List<MynimekuPlayer>): String {
        if (players.isEmpty()) return referer
        val payload = players.take(32).joinToString("||") { p ->
            listOf(p.url, p.label, p.group, p.sourcePage).joinToString("~~") { URLEncoder.encode(it, "UTF-8") }
        }
        return "mynimeku::${URLEncoder.encode(referer, "UTF-8")}:::$payload"
    }

    fun decodeBundle(data: String): Pair<String, List<MynimekuPlayer>>? {
        val prefix = "mynimeku::"
        if (!data.startsWith(prefix)) return null
        val parts = data.removePrefix(prefix).split(":::", limit = 2)
        if (parts.size != 2) return null
        val referer = URLDecoder.decode(parts[0], "UTF-8")
        val players = parts[1].split("||").mapNotNull { raw ->
            val fields = raw.split("~~")
            val url = fields.getOrNull(0)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
            if (url.isBlank()) return@mapNotNull null
            MynimekuPlayer(
                url = url,
                label = fields.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty(),
                group = fields.getOrNull(2)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty(),
                sourcePage = fields.getOrNull(3)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
            )
        }
        return referer to players
    }

    fun decodeJsEscapes(input: String): String {
        var output = input
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        return output
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\=", "=")
            .replace("\\&", "&")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
    }

    fun intToRadix(value: Int, radix: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (value == 0) return "0"
        var number = value
        val output = StringBuilder()
        val safeRadix = radix.coerceIn(2, chars.length)
        while (number > 0) {
            output.insert(0, chars[number % safeRadix])
            number /= safeRadix
        }
        return output.toString()
    }
}
