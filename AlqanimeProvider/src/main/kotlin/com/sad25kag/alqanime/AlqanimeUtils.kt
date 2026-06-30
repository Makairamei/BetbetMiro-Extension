package com.sad25kag.alqanime

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Qualities
import org.jsoup.nodes.Element
import java.net.URI

fun providerHeaders(mainUrl: String) = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.5",
    "Referer" to mainUrl
)

fun String.cleanEscaped(): String {
    return trim()
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u003A", ":")
        .replace("\\u0026", "&")
        .replace("\\u003D", "=")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")
        .replace("\\\"", "\"")
        .trim('"', '\'', ',', ';')
        .trim()
}

fun MainAPI.normalizeUrl(url: String?, baseUrl: String = mainUrl): String? {
    val clean = url?.cleanEscaped().orEmpty()
    if (clean.isBlank() || clean == "#" || clean.startsWith("javascript:", true)) return null

    return when {
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = runCatching {
                val uri = URI(baseUrl)
                "${uri.scheme}://${uri.host}"
            }.getOrDefault(mainUrl)
            origin + clean
        }
        else -> runCatching { URI(baseUrl).resolve(clean).toString() }
            .getOrElse { fixUrl(clean) }
    }
}

fun MainAPI.imageUrl(element: Element): String? {
    val direct = listOf(
        "data-src",
        "data-lazy-src",
        "data-original",
        "data-bg",
        "src",
        "content"
    ).firstNotNullOfOrNull { key ->
        element.attr(key)
            .trim()
            .takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
    }

    val srcset = element.attr("srcset")
        .ifBlank { element.attr("data-srcset") }
        .split(",")
        .map { it.trim().substringBefore(" ").trim() }
        .firstOrNull { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }

    return fixUrlNull(direct ?: srcset)
}

fun String.fixQuality(): Int = when {
    contains("2160", true) -> Qualities.P2160.value
    contains("1080", true) -> Qualities.P1080.value
    contains("720", true) -> Qualities.P720.value
    contains("480", true) -> Qualities.P480.value
    contains("360", true) -> Qualities.P360.value
    else -> Qualities.Unknown.value
}

fun String.isArchiveDownloadUrl(): Boolean {
    val lower = substringBefore("?").lowercase()
    return lower.endsWith(".zip") ||
        lower.endsWith(".rar") ||
        lower.endsWith(".7z") ||
        lower.endsWith(".tar") ||
        lower.endsWith(".gz") ||
        lower.endsWith(".apk") ||
        lower.contains("-zip") ||
        lower.contains("/zip")
}

fun String.isPlayableMediaUrl(): Boolean {
    val lower = lowercase()
    return lower.contains(".m3u8") ||
        lower.contains(".mp4") ||
        lower.contains(".webm") ||
        lower.contains("/api/file/")
}

fun String.isOuoUrl(): Boolean {
    val host = runCatching { URI(this).host.orEmpty().lowercase() }.getOrDefault("")
    return host == "ouo.io" || host.endsWith(".ouo.io") || host == "ouo.press" || host.endsWith(".ouo.press")
}

fun String.isResolvedShortlinkTarget(): Boolean {
    val lower = lowercase()
    if (isBlank() || isOuoUrl()) return false
    return isPlayableMediaUrl() ||
        lower.contains("acefile.co") ||
        lower.contains("mediafire.com") ||
        lower.contains("pixeldrain.com") ||
        lower.contains("gofile.io") ||
        lower.contains("resharer.org") ||
        lower.contains("terabox") ||
        lower.contains("uptobox.com") ||
        lower.contains("uptostream.com") ||
        lower.contains("hxdrive") ||
        lower.contains("drive.google.com") ||
        lower.contains("docs.google.com") ||
        lower.contains("onedrive.live.com") ||
        lower.contains("1drv.ms") ||
        lower.contains("mega.nz") ||
        lower.contains("mega.co.nz") ||
        lower.contains("yurinime.com")
}

fun String.isLikelyResolvableUrl(): Boolean {
    val lower = lowercase()
    if (isPlayableMediaUrl()) return true
    return lower.contains("download") ||
        lower.contains("worker") ||
        lower.contains("r2.dev") ||
        lower.contains("workers.dev") ||
        lower.contains("acefile.co") ||
        lower.contains("mediafire.com") ||
        lower.contains("pixeldrain.com") ||
        lower.contains("gofile.io") ||
        lower.contains("yurinime.com") ||
        lower.contains("terabox") ||
        lower.contains("uptobox.com") ||
        lower.contains("uptostream.com") ||
        lower.contains("hxdrive") ||
        lower.contains("drive.google.com") ||
        lower.contains("docs.google.com") ||
        lower.contains("onedrive.live.com") ||
        lower.contains("1drv.ms") ||
        lower.contains("mega.nz") ||
        lower.contains("mega.co.nz")
}

fun String.isExternalDownloadHostUrl(): Boolean {
    val lower = lowercase()
    return lower.contains("drive.google.com") ||
        lower.contains("docs.google.com") ||
        lower.contains("uptobox.com") ||
        lower.contains("uptostream.com") ||
        lower.contains("terabox") ||
        lower.contains("hxdrive") ||
        lower.contains("onedrive.live.com") ||
        lower.contains("1drv.ms") ||
        lower.contains("mega.nz") ||
        lower.contains("mega.co.nz")
}

fun String.externalDownloadHostName(): String {
    val lower = lowercase()
    return when {
        lower.contains("drive.google.com") || lower.contains("docs.google.com") -> "Google Drive"
        lower.contains("uptobox.com") || lower.contains("uptostream.com") -> "Uptobox"
        lower.contains("terabox") -> "TeraBox"
        lower.contains("hxdrive") -> "HxDrive"
        lower.contains("onedrive.live.com") || lower.contains("1drv.ms") -> "OneDrive"
        lower.contains("mega.nz") || lower.contains("mega.co.nz") -> "Mega"
        else -> "Direct"
    }
}