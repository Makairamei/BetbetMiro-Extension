package com.KRX18

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

class PlayKrx18 : ExtractorApi() {
    override val name = "PlayKrx18"
    override val mainUrl = "https://krx18.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = normalizeUrl(url, referer ?: mainUrl) ?: return
        val pageReferer = referer ?: mainUrl

        val response = runCatching {
            app.get(
                pageUrl,
                referer = pageReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to pageReferer
                ),
                timeout = 20L
            )
        }.getOrNull() ?: return

        val emitted = linkedSetOf<String>()
        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()
        val html = response.text.cleanEscaped()

        if (html.trimStart().startsWith("#EXTM3U")) {
            emitDirect(pageUrl, pageReferer, callback)
            return
        }

        collectDocumentCandidates(response.document, pageUrl, directLinks, embedLinks, subtitleCallback)
        extractPlayableUrls(html).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }
        }

        val decoded = runCatching { URLDecoder.decode(html, "UTF-8") }.getOrDefault(html)
        if (decoded != html) {
            extractPlayableUrls(decoded.cleanEscaped()).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }
        }

        directLinks.forEach { link ->
            if (emitted.add(link)) emitDirect(link, pageUrl, callback)
        }

        if (emitted.isNotEmpty()) return

        embedLinks
            .filterNot { shouldSkip(it) }
            .distinct()
            .take(8)
            .forEach { embed ->
                val success = runCatching {
                    loadExtractor(embed, pageUrl, subtitleCallback, callback)
                }.getOrDefault(false)

                if (success) {
                    emitted.add(embed)
                    return
                }

                val nestedResponse = runCatching {
                    app.get(
                        embed,
                        referer = pageUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "*/*",
                            "Referer" to pageUrl
                        ),
                        timeout = 15L
                    )
                }.getOrNull() ?: return@forEach

                collectDocumentCandidates(nestedResponse.document, embed, directLinks, embedLinks, subtitleCallback)
                extractPlayableUrls(nestedResponse.text.cleanEscaped()).forEach { nested ->
                    val fixed = normalizeUrl(nested, embed)?.replace(".txt", ".m3u8") ?: return@forEach
                    if (isDirectMedia(fixed) && emitted.add(fixed)) {
                        emitDirect(fixed, embed, callback)
                    }
                }
            }
    }

    private suspend fun collectDocumentCandidates(
        document: Document,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        document.select(
            "meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], " +
                "meta[name=twitter:player], iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "video[src], video[data-src], video source[src], source[src], embed[src], object[data], " +
                "a[href], [data-src], [data-video], [data-file], [data-url], [data-embed], [data-iframe]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }

        document.select("track[src], a[href$=.vtt], a[href$=.srt]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }.trim()
            val subUrl = normalizeUrl(raw, baseUrl) ?: return@forEach
            val label = element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } }
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val fixed = normalizeUrl(raw, baseUrl)
            ?.replace(".txt", ".m3u8")
            ?.takeIf { it.isNotBlank() && !shouldSkip(it) }
            ?: return

        when {
            isDirectMedia(fixed) -> directLinks.add(fixed)
            fixed.startsWith("http", true) && isLikelyEmbed(fixed) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirect(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = link.cleanEscaped().replace(".txt", ".m3u8")
        if (shouldSkip(fixed)) return

        if (fixed.contains(".m3u8", true)) {
            generateM3u8(
                source = name,
                streamUrl = fixed,
                referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            ).forEach(callback)
            return
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = fixed,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(fixed).takeIf { it != Qualities.Unknown.value } ?: qualityFromUrl(fixed)
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            }
        )
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val clean = text.cleanEscaped()
        val urls = linkedSetOf<String>()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoUrl|video_url|hls|hlsUrl|hls_url|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter { isDirectMedia(it) || isLikelyEmbed(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:krx18|embed|player|stream|filemoon|streamwish|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|hglink|hgcloud|majorplay|jeniusplay)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .forEach { urls.add(it) }

        return urls.filterNot { shouldSkip(it) }.toList()
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String? {
        val clean = raw.cleanEscaped()
            .takeIf { it.isNotBlank() }
            ?: return null

        if (clean.startsWith("javascript", true) || clean.startsWith("data:image", true)) return null

        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: mainUrl
                "$origin$clean"
            }
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    private fun isDirectMedia(url: String): Boolean {
        return Regex("""(?i)\.(m3u8|mp4|webm)(?:$|[?#&])""").containsMatchIn(url)
    }

    private fun isLikelyEmbed(url: String): Boolean {
        val value = url.lowercase()
        return listOf(
            "krx18", "embed", "player", "stream", "filemoon", "streamwish", "wishfast",
            "dood", "streamtape", "vidhide", "vidguard", "voe", "mixdrop", "mp4upload",
            "hglink", "hgcloud", "majorplay", "jeniusplay"
        ).any { value.contains(it) }
    }

    private fun shouldSkip(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("mailto:") ||
            value.contains("/ads/") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("analytics") ||
            value.contains("tracking") ||
            value.contains("banner") ||
            value.contains("histats") ||
            value.contains("about:blank")
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }
}
